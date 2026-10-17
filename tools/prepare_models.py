#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Prepare the offline translation models that get bundled into the APK.

Four selectable models (the user picks one per direction in the app):

  opus-mt   Marian / OPUS-MT, ~77M parameters, one model per direction (default, small and fast)
              shun89/opus-mt-ja-zh   Japanese -> Chinese
              shun89/opus-mt-zh-ja   Chinese  -> Japanese
  opus-lite The same two OPUS-MT models with a pruned vocabulary (65k -> ~28k tokens: the most
            frequent ones on a corpus sample plus the best BPE merges).  The embedding matrix is two thirds of the model, so
            this halves file size and RAM (see tools/lite_vocab.py)
  m2m100    facebook/m2m100_418M            multilingual, both directions in one model (MIT)
  nllb      facebook/nllb-200-distilled-600M  multilingual, both directions in one model (CC-BY-NC-4.0)

The two multilingual models have huge vocabularies (128k / 256k tokens) of which a ja<->zh
translator needs a fraction.  Before export the vocabulary is pruned to CJK / kana / ASCII /
punctuation tokens (embedding matrix, SentencePiece model and vocab.txt are rewritten
consistently).  That roughly halves RAM use and speeds up the output projection, which matters on
a 1 GB device.

Pipeline for each model:
    1. download the PyTorch checkpoint from HuggingFace (or use --src-dir for opus-mt)
    2. (multilingual / lite only) prune the vocabulary
    3. export to ONNX with a KV cache (optimum):  encoder + merged decoder
    4. dynamic int8 quantisation (weights int8, ~4x smaller)
    5. move the weights into an external data file next to each .onnx (the on-device ONNX Runtime
       then memory-maps them instead of copying ~100 MB into the Java/native heap while loading)
    6. write tokenizer assets (SentencePiece models, id-ordered vocab.txt) and meta.properties

Output layout (consumed by the Android app, see ModelStore.java; every file in a folder is copied):
    <out>/models/opus-mt/<ja-zh|zh-ja>/{encoder.onnx,encoder.onnx_data,decoder.onnx,decoder.onnx_data,
                                         source.spm,target.spm,vocab.txt,meta.properties}
    <out>/models/opus-mt/VERSION
    <out>/models/opus-lite/...   (same layout)
    <out>/models/<m2m100|nllb>/{encoder.onnx,decoder.onnx,source.spm,vocab.txt,meta.properties}
    <out>/models/<m2m100|nllb>/VERSION        (target.spm is omitted: the app reuses source.spm)

Usage:
    python tools/prepare_models.py --out app/src/main/assets [--only opus-mt|m2m100|nllb] [--src-dir DIR] [--work DIR] [--smoke]
"""
import argparse
import hashlib
import os
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
ALL_KEYS = ["opus-mt", "opus-lite"] + list(MULTI)
PIPELINE_VERSION = "q3"
# opus-lite: number of vocabulary tokens kept and the size of the corpus sample used to count token
# frequencies.  Measured on news_commentary ja-zh (chrF, original 19.2 / 22.9): 24k 17.5 / 19.9,
# 28k 17.6 / 20.8, 32k 18.4 / 21.6.  LITE_VOCAB / LITE_MODE (corpus|prefix|mix) override for experiments.
LITE_TARGET_VOCAB = int(os.environ.get("LITE_VOCAB", "28000"))
LITE_CORPUS_SENTENCES = 200000
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
    for stale in dst.glob("*.onnx*"):
        stale.unlink()
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
    externalize(dst / "encoder.onnx")
    externalize(dst / "decoder.onnx")


def externalize(path):
    """Moves the weights of an .onnx file into <name>.onnx_data (same folder)."""
    import onnx
    data = path.with_name(path.name + "_data")
    if data.exists():
        data.unlink()
    model = onnx.load(str(path))
    onnx.save_model(model, str(path), save_as_external_data=True, all_tensors_to_one_file=True,
                    location=data.name, size_threshold=1024, convert_attribute=False)
    del model
    log("%s: graph %.2f MB + weights %.1f MB" % (path.name, path.stat().st_size / 1e6, data.stat().st_size / 1e6))


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
# opus-lite: OPUS-MT with a pruned vocabulary
# ------------------------------------------------------------------------------------------------

def prune_marian(src_dir, keep_ids, dst_dir):
    """Rewrites a Marian checkpoint so that only the tokens in keep_ids (old ids, ascending) remain."""
    import torch
    from transformers import MarianMTModel

    model = MarianMTModel.from_pretrained(str(src_dir), torch_dtype=torch.float32)
    cfg = model.config
    remap = {old: new for new, old in enumerate(keep_ids)}
    idx = torch.tensor(keep_ids, dtype=torch.long)
    new_v = len(keep_ids)

    weight = model.get_input_embeddings().weight.data[idx].clone()
    emb = torch.nn.Embedding(new_v, weight.shape[1], padding_idx=remap[cfg.pad_token_id])
    emb.weight = torch.nn.Parameter(weight)
    model.set_input_embeddings(emb)               # shared + encoder + decoder embeddings
    model.lm_head = torch.nn.Linear(weight.shape[1], new_v, bias=False)
    model.lm_head.weight = emb.weight             # tied output projection
    model.final_logits_bias = model.final_logits_bias[:, idx].clone()

    ids = {
        "pad_token_id": remap[cfg.pad_token_id],
        "decoder_start_token_id": remap[cfg.decoder_start_token_id],
        "eos_token_id": remap[cfg.eos_token_id],
        "bos_token_id": remap[cfg.bos_token_id],
        "forced_eos_token_id": remap[cfg.forced_eos_token_id],
        "vocab_size": new_v,
        "decoder_vocab_size": new_v,
        "bad_words_ids": [[remap[cfg.pad_token_id]]],
    }
    for k, v in ids.items():
        setattr(cfg, k, v)
        if hasattr(model.generation_config, k):
            setattr(model.generation_config, k, v)
    dst_dir.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(str(dst_dir), safe_serialization=False)
    del model, emb, weight
    return ids


def build_opus_lite(out_models, work, src_dir):
    import lite_vocab
    from sentencepiece import sentencepiece_model_pb2 as spb  # noqa: F401  (fail early if missing)
    import sentencepiece as spm

    t0 = time.time()
    log("=== opus-lite")
    model_dirs, revs = {}, []
    for name, spec in OPUS.items():
        if src_dir:
            model_dirs[name] = Path(src_dir) / name
            revs.append(spec.get("revision") or "local")
        else:
            model_dirs[name] = snapshot(spec["repo"], spec["revision"], OPUS_FILES, work / "hf" / name)
            revs.append(spec["revision"] or "main")

    # ja side = source.spm of ja-zh, zh side = target.spm of ja-zh (zh-ja uses the same two models)
    spm_bytes = {"ja": (model_dirs["ja-zh"] / "source.spm").read_bytes(),
                 "zh": (model_dirs["ja-zh"] / "target.spm").read_bytes()}
    if (model_dirs["zh-ja"] / "source.spm").read_bytes() != spm_bytes["zh"] or \
            (model_dirs["zh-ja"] / "target.spm").read_bytes() != spm_bytes["ja"]:
        log("WARNING: the zh-ja SentencePiece models differ from the ja-zh ones; pruning uses the ja-zh ones")
    vocab = json.loads((model_dirs["ja-zh"] / "vocab.json").read_text(encoding="utf-8"))
    if vocab != json.loads((model_dirs["zh-ja"] / "vocab.json").read_text(encoding="utf-8")):
        sys.exit("ja-zh and zh-ja use different vocabularies: opus-lite assumes a shared one")
    inv = {i: t for t, i in vocab.items()}
    specials = {inv[i] for i in {0, 1, max(vocab.values())} if i in inv}
    models = {side: lite_vocab.spm_scores(b) for side, b in spm_bytes.items()}

    corpus = lite_vocab.load_corpus(work, LITE_CORPUS_SENTENCES)
    if corpus:
        held = {k: v[-4000:] for k, v in corpus.items()}
        train = {k: v[:-4000] for k, v in corpus.items()}
        counts = {k: lite_vocab.count_pieces(spm.SentencePieceProcessor(model_proto=spm_bytes[k]), train[k])
                  for k in train}
        mode = os.environ.get("LITE_MODE", "mix")
        if mode == "prefix":
            keep_ids = lite_vocab.select_prefix(vocab, models, specials, LITE_TARGET_VOCAB)
        elif mode == "mix":
            first = lite_vocab.select(vocab, models, counts, specials, int(LITE_TARGET_VOCAB * 0.6))
            keep_ids = lite_vocab.select_prefix(vocab, models, specials, LITE_TARGET_VOCAB,
                                                keep={inv[i] for i in first})
        else:
            keep_ids = lite_vocab.select(vocab, models, counts, specials, LITE_TARGET_VOCAB)
    else:
        held = None
        keep_ids = lite_vocab.select_by_score(vocab, models, specials, LITE_TARGET_VOCAB, _char_ok)
    kept_tokens = {inv[i] for i in keep_ids}
    log("vocabulary pruned: %d -> %d tokens" % (len(vocab), len(keep_ids)))
    pruned_spm = {side: lite_vocab.prune_spm(b, kept_tokens) for side, b in spm_bytes.items()}
    if held:
        for side in pruned_spm:
            lite_vocab.evaluate(spm_bytes[side], pruned_spm[side], held[side], "tokenizer " + side)

    table = [inv[i] for i in keep_ids]
    for name, spec in OPUS.items():
        t1 = time.time()
        src_side, tgt_side = ("ja", "zh") if name == "ja-zh" else ("zh", "ja")
        pruned = work / "pruned" / ("lite-" + name)
        prune_marian(model_dirs[name], keep_ids, pruned)
        dst = out_models / "opus-lite" / name
        convert_to_onnx(pruned, work / "onnx" / ("lite-" + name), dst)
        (dst / "source.spm").write_bytes(pruned_spm[src_side])
        (dst / "target.spm").write_bytes(pruned_spm[tgt_side])
        size = write_vocab_list(list(table), dst / "vocab.txt")
        meta = write_meta(pruned, size, dst / "meta.properties")
        shutil.rmtree(pruned, ignore_errors=True)
        log("lite %s done in %.0fs, meta=%s" % (name, time.time() - t1, meta))
    write_version(out_models / "opus-lite", revs + ["v%d" % LITE_TARGET_VOCAB])
    log("opus-lite done in %.0fs" % (time.time() - t0))


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
        elif key == "opus-lite":
            build_opus_lite(out_models, work, args.src_dir)
        else:
            build_multi(key, out_models, work)
        if args.smoke:
            sys.path.insert(0, str(Path(__file__).resolve().parent))
            import smoke_test
            smoke_test.run(out_models, only=key)


if __name__ == "__main__":
    main()
