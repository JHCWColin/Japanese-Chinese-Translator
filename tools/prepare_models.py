#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Prepare the offline translation models that get bundled into the APK.

Three selectable models (the user picks one per direction in the app):

  opus-mt   Marian / OPUS-MT, ~77M parameters, one model per direction (default, small and fast)
              shun89/opus-mt-ja-zh   Japanese -> Chinese
              shun89/opus-mt-zh-ja   Chinese  -> Japanese
  m2m100    facebook/m2m100_418M            multilingual, both directions in one model (MIT)
  nllb      facebook/nllb-200-distilled-600M  multilingual, both directions in one model (CC-BY-NC-4.0)

The two multilingual models have huge vocabularies (128k / 256k tokens) of which a ja<->zh
translator needs a fraction.  Before export the vocabulary is pruned to CJK / kana / ASCII /
punctuation tokens (embedding matrix, SentencePiece model and vocab.txt are rewritten
consistently).  That roughly halves RAM use and speeds up the output projection, which matters on
a 1 GB device.

Pipeline for each model:
    1. download the PyTorch checkpoint from HuggingFace (or use --src-dir for opus-mt)
    2. (multilingual only) prune the vocabulary
    3. export to ONNX with a KV cache (optimum):  encoder + merged decoder
    4. dynamic int8 quantisation (weights int8, ~4x smaller)
    5. write tokenizer assets (SentencePiece models, id-ordered vocab.txt) and meta.properties

Output layout (consumed by the Android app, see ModelStore.java):
    <out>/models/opus-mt/<ja-zh|zh-ja>/{encoder.onnx,decoder.onnx,source.spm,target.spm,vocab.txt,meta.properties}
    <out>/models/opus-mt/VERSION
    <out>/models/<m2m100|nllb>/{encoder.onnx,decoder.onnx,source.spm,vocab.txt,meta.properties}
    <out>/models/<m2m100|nllb>/VERSION        (target.spm is omitted: the app reuses source.spm)

Usage:
    python tools/prepare_models.py --out app/src/main/assets [--only opus-mt|m2m100|nllb] [--src-dir DIR] [--work DIR] [--smoke]
"""
import argparse
import hashlib
import json
import shutil
import sys
import time
from pathlib import Path

# Pinned revisions keep builds reproducible.
OPUS = {
    "ja-zh": {"repo": "shun89/opus-mt-ja-zh", "revision": "0728b51b9be02330f7bce262a4d47f611fd3a2a4"},
    "zh-ja": {"repo": "shun89/opus-mt-zh-ja", "revision": "8cf1db76350924be2aa40817e54ccdf411436592"},
}
OPUS_FILES = [
    "config.json", "generation_config.json", "source.spm", "target.spm",
    "vocab.json", "tokenizer_config.json", "pytorch_model.bin",
]
MULTI = {
    "m2m100": {
        "repo": "facebook/m2m100_418M",
        "revision": "55c2e61bbf05dfb8d7abccdc3fae6fc8512fd636",
        "files": ["config.json", "generation_config.json", "pytorch_model.bin", "sentencepiece.bpe.model",
                  "special_tokens_map.json", "tokenizer_config.json", "vocab.json"],
        "spm": "sentencepiece.bpe.model",
        "langs": {"ja": "__ja__", "zh": "__zh__"},
    },
    "nllb": {
        "repo": "facebook/nllb-200-distilled-600M",
        "revision": "f8d333a098d19b4fd9a8b18f94170487ad3f821d",
        "files": ["config.json", "generation_config.json", "pytorch_model.bin", "sentencepiece.bpe.model",
                  "special_tokens_map.json", "tokenizer.json", "tokenizer_config.json"],
        "spm": "sentencepiece.bpe.model",
        "langs": {"ja": "jpn_Jpan", "zh": "zho_Hans"},
    },
}
ALL_KEYS = ["opus-mt"] + list(MULTI)
PIPELINE_VERSION = "q2"
# Of the ASCII-only (mostly European-language) SentencePiece tokens longer than 2 characters, keep
# this many by SentencePiece score.  Names, English words and numbers are still covered; anything
# else falls back to shorter pieces.
ASCII_KEEP = 24000


def log(msg):
    print("[prepare_models] " + msg, flush=True)


def snapshot(repo, revision, files, local_dir):
    from huggingface_hub import snapshot_download
    return Path(snapshot_download(repo_id=repo, revision=revision or None,
                                  allow_patterns=files, local_dir=str(local_dir)))


def export_onnx(model_dir, out_dir):
    from optimum.exporters.onnx import main_export
    out_dir.mkdir(parents=True, exist_ok=True)
    main_export(
        model_name_or_path=str(model_dir),
        output=str(out_dir),
        task="text2text-generation-with-past",
        device="cpu",
        do_validation=False,
    )
    files = {n: out_dir / (n + ".onnx") for n in ("encoder_model", "decoder_model", "decoder_with_past_model")}
    for f in files.values():
        if not f.exists():
            sys.exit("optimum export did not produce %s (found: %s)" % (f.name, sorted(p.name for p in out_dir.iterdir())))
    return files


def quantize(src, dst):
    from onnxruntime.quantization import QuantType, quantize_dynamic
    quantize_dynamic(
        model_input=str(src),
        model_output=str(dst),
        weight_type=QuantType.QInt8,
        per_channel=True,
        reduce_range=False,
    )
    log("quantized %s: %.1f MB -> %.1f MB" % (src.name, src.stat().st_size / 1e6, dst.stat().st_size / 1e6))


def convert_to_onnx(model_dir, work_dir, dst):
    """Export + quantise encoder and merged decoder into dst/encoder.onnx and dst/decoder.onnx."""
    if work_dir.exists():
        shutil.rmtree(work_dir)
    files = export_onnx(model_dir, work_dir)
    dst.mkdir(parents=True, exist_ok=True)
    quantize(files["encoder_model"], dst / "encoder.onnx")
    # The merged (If-based) decoder cannot be quantised directly because the quantiser
    # does not descend into sub-graphs.  Quantise both decoders separately and merge
    # afterwards; identical weights are de-duplicated by the merge.
    qdir = work_dir / "q"
    qdir.mkdir(exist_ok=True)
    quantize(files["decoder_model"], qdir / "decoder_q.onnx")
    quantize(files["decoder_with_past_model"], qdir / "decoder_with_past_q.onnx")
    from optimum.onnx import merge_decoders
    merge_decoders(qdir / "decoder_q.onnx", qdir / "decoder_with_past_q.onnx",
                   save_path=dst / "decoder.onnx", strict=False)
    log("merged decoder: %.1f MB" % ((dst / "decoder.onnx").stat().st_size / 1e6))
    shutil.rmtree(work_dir, ignore_errors=True)  # free disk for the next model


def write_vocab_list(table, dst):
    for i, tok in enumerate(table):
        if tok is None:
            table[i] = "<unused_%d>" % i
        if "\n" in table[i] or "\r" in table[i]:
            sys.exit("vocab token %d contains a newline" % i)
    dst.write_text("\n".join(table) + "\n", encoding="utf-8")
    return len(table)


def write_vocab(vocab_json, dst):
    vocab = json.loads(Path(vocab_json).read_text(encoding="utf-8"))
    table = [None] * (max(vocab.values()) + 1)
    for tok, idx in vocab.items():
        if table[idx] is None:
            table[idx] = tok
    return write_vocab_list(table, dst)


def write_meta(model_dir, vocab_size, dst, extra=None):
    cfg = json.loads((model_dir / "config.json").read_text(encoding="utf-8"))
    gen = json.loads((model_dir / "generation_config.json").read_text(encoding="utf-8"))
    heads = cfg["decoder_attention_heads"]
    meta = {
        "vocab_size": vocab_size,
        "decoder_start_token_id": cfg["decoder_start_token_id"],
        "eos_token_id": cfg["eos_token_id"],
        "pad_token_id": cfg["pad_token_id"],
        "decoder_layers": cfg["decoder_layers"],
        "decoder_heads": heads,
        "head_dim": cfg["d_model"] // heads,
        "max_length": gen.get("max_length", 512),
        "num_beams": gen.get("num_beams", 4),
    }
    meta.update(extra or {})
    dst.write_text("".join("%s=%s" % kv + chr(10) for kv in meta.items()), encoding="utf-8")
    return meta


# ------------------------------------------------------------------------------------------------
# opus-mt
# ------------------------------------------------------------------------------------------------

def build_opus(out_models, work, src_dir):
    revs = []
    for name, spec in OPUS.items():
        t0 = time.time()
        log("=== opus-mt %s (%s)" % (name, spec["repo"]))
        if src_dir:
            model_dir = Path(src_dir) / name
            if not (model_dir / "pytorch_model.bin").exists():
                sys.exit("missing %s" % (model_dir / "pytorch_model.bin"))
            revs.append(spec.get("revision") or "local")
        else:
            model_dir = snapshot(spec["repo"], spec["revision"], OPUS_FILES, work / "hf" / name)
            revs.append(spec["revision"] or "main")
        dst = out_models / "opus-mt" / name
        convert_to_onnx(model_dir, work / "onnx" / name, dst)
        shutil.copyfile(model_dir / "source.spm", dst / "source.spm")
        shutil.copyfile(model_dir / "target.spm", dst / "target.spm")
        size = write_vocab(model_dir / "vocab.json", dst / "vocab.txt")
        meta = write_meta(model_dir, size, dst / "meta.properties")
        log("%s done in %.0fs, meta=%s" % (name, time.time() - t0, meta))
    write_version(out_models / "opus-mt", revs)


# ------------------------------------------------------------------------------------------------
# multilingual models with vocabulary pruning
# ------------------------------------------------------------------------------------------------

def _char_ok(ch):
    c = ord(ch)
    return (
        c < 0x80                      # ASCII
        or 0xA0 <= c <= 0xFF          # Latin-1 punctuation / symbols
        or 0x370 <= c <= 0x3FF        # Greek (maths / science)
        or 0x2000 <= c <= 0x2BFF      # general punctuation, arrows, maths, box drawing, symbols
        or 0x3000 <= c <= 0x30FF      # CJK punctuation, hiragana, katakana
        or 0x31F0 <= c <= 0x31FF      # katakana phonetic extensions
        or 0x3200 <= c <= 0x33FF      # enclosed / compatibility CJK
        or 0x3400 <= c <= 0x4DBF      # CJK ext A
        or 0x4E00 <= c <= 0x9FFF      # CJK unified ideographs
        or 0xF900 <= c <= 0xFAFF      # CJK compatibility ideographs
        or 0xFE30 <= c <= 0xFE4F      # CJK compatibility forms
        or 0xFF00 <= c <= 0xFFEF      # half / full width forms
        or 0x20000 <= c <= 0x2A6DF    # CJK ext B
    )


def select_tokens(vocab, spm_scores, wanted_specials):
    """vocab: token -> old id; returns the sorted list of old ids to keep."""
    keep = set()
    ascii_long = []
    for tok, idx in vocab.items():
        if idx < 4 or tok in wanted_specials:
            keep.add(idx)
            continue
        if tok not in spm_scores:
            continue  # added tokens such as the other ~200 language codes
        body = tok.replace("▁", "")
        if not body or not all(_char_ok(ch) for ch in body):
            continue
        if all(ord(ch) < 0x80 for ch in body) and len(body) > 2:
            ascii_long.append((spm_scores[tok], idx))
        else:
            keep.add(idx)
    ascii_long.sort(reverse=True)
    keep.update(idx for _, idx in ascii_long[:ASCII_KEEP])
    return sorted(keep)


def prune_checkpoint(src_dir, spm_name, wanted_specials, dst_dir):
    """Rewrites model weights, vocab list and SentencePiece model. Returns the new vocab list."""
    import torch
    from sentencepiece import sentencepiece_model_pb2 as spb
    from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

    tok = AutoTokenizer.from_pretrained(str(src_dir))
    vocab = dict(tok.get_vocab())
    spm = spb.ModelProto()
    spm.ParseFromString((src_dir / spm_name).read_bytes())
    scores = {p.piece: p.score for p in spm.pieces}

    keep = select_tokens(vocab, scores, wanted_specials)
    inv = {idx: t for t, idx in vocab.items()}
    table = [inv[i] for i in keep]
    for i, want in enumerate(["<s>", "<pad>", "</s>", "<unk>"]):
        if table[i] != want:
            sys.exit("unexpected special token layout: id %d is %r, expected %r" % (i, table[i], want))
    for t in wanted_specials:
        if t not in table:
            sys.exit("language token %s missing from the vocabulary" % t)
    log("vocabulary pruned: %d -> %d tokens" % (len(vocab), len(keep)))

    model = AutoModelForSeq2SeqLM.from_pretrained(str(src_dir), torch_dtype=torch.float32)
    idx = torch.tensor(keep, dtype=torch.long)
    emb = model.get_input_embeddings()
    new_w = torch.nn.Parameter(emb.weight.data[idx].clone())
    emb.weight = new_w
    emb.num_embeddings = len(keep)
    for m in (model.model.encoder.embed_tokens, model.model.decoder.embed_tokens):
        if m is not emb:
            m.weight = new_w
            m.num_embeddings = len(keep)
    model.lm_head.weight = new_w
    model.lm_head.out_features = len(keep)
    model.config.vocab_size = len(keep)
    model.config.tie_word_embeddings = True
    dst_dir.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(str(dst_dir), safe_serialization=False)
    # keep generation_config.json (save_pretrained writes it, but make sure it exists)
    if not (dst_dir / "generation_config.json").exists():
        shutil.copyfile(src_dir / "generation_config.json", dst_dir / "generation_config.json")
    del model, emb, new_w

    # SentencePiece model without the pruned pieces: the segmentation then never produces a
    # piece that is missing from the model vocabulary.
    kept = set(table)
    pieces = [p for p in spm.pieces if p.type != spb.ModelProto.SentencePiece.NORMAL or p.piece in kept]
    del spm.pieces[:]
    spm.pieces.extend(pieces)
    (dst_dir / "pruned.spm").write_bytes(spm.SerializeToString())
    return table


def build_multi(key, out_models, work):
    spec = MULTI[key]
    t0 = time.time()
    log("=== %s (%s)" % (key, spec["repo"]))
    src = snapshot(spec["repo"], spec["revision"], spec["files"], work / "hf" / key)
    wanted = set(spec["langs"].values())
    pruned = work / "pruned" / key
    table = prune_checkpoint(src, spec["spm"], wanted, pruned)
    shutil.rmtree(work / "hf" / key, ignore_errors=True)  # the original checkpoint is no longer needed

    dst = out_models / key
    convert_to_onnx(pruned, work / "onnx" / key, dst)
    shutil.copyfile(pruned / "pruned.spm", dst / "source.spm")
    size = write_vocab_list(list(table), dst / "vocab.txt")
    meta = write_meta(pruned, size, dst / "meta.properties",
                      {"src_lang_ja": spec["langs"]["ja"], "src_lang_zh": spec["langs"]["zh"]})
    shutil.rmtree(pruned, ignore_errors=True)
    write_version(dst, [spec["revision"]])
    log("%s done in %.0fs, meta=%s" % (key, time.time() - t0, meta))


def write_version(model_root, revs):
    digest = hashlib.sha1((PIPELINE_VERSION + "|" + "|".join(revs)).encode()).hexdigest()[:12]
    (model_root / "VERSION").write_text(digest + "\n", encoding="utf-8")
    log("%s VERSION = %s" % (model_root.name, digest))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, help="assets directory (models/ is created inside)")
    ap.add_argument("--work", default="", help="scratch directory (default: <out>/../.model-work)")
    ap.add_argument("--src-dir", default="", help="opus-mt only: use pre-downloaded <dir>/<ja-zh|zh-ja>/ instead of HuggingFace")
    ap.add_argument("--only", default="", help="process a single model: " + ", ".join(ALL_KEYS))
    ap.add_argument("--smoke", action="store_true", help="run a translation smoke test on the produced files")
    args = ap.parse_args()
    if args.only and args.only not in ALL_KEYS:
        sys.exit("--only must be one of %s" % ALL_KEYS)

    out_models = Path(args.out) / "models"
    work = Path(args.work) if args.work else Path(args.out).resolve().parent / ".model-work"
    work.mkdir(parents=True, exist_ok=True)
    out_models.mkdir(parents=True, exist_ok=True)

    for key in ALL_KEYS:
        if args.only and args.only != key:
            continue
        if key == "opus-mt":
            build_opus(out_models, work, args.src_dir)
        else:
            build_multi(key, out_models, work)
        if args.smoke:
            sys.path.insert(0, str(Path(__file__).resolve().parent))
            import smoke_test
            smoke_test.run(out_models, only=key)


if __name__ == "__main__":
    main()
