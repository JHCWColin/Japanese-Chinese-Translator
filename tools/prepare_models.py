#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Prepare the offline translation models that get bundled into the APK.

Source models (Marian / OPUS-MT architecture, ~77M parameters each, direct pairs):
    shun89/opus-mt-ja-zh   Japanese -> Chinese
    shun89/opus-mt-zh-ja   Chinese  -> Japanese

Pipeline for each direction:
    1. download the PyTorch checkpoint from HuggingFace (or use --src-dir)
    2. export to ONNX with a KV cache (optimum):  encoder + merged decoder
    3. dynamic int8 quantisation (weights int8, ~4x smaller)  -> ~145 MB / direction
    4. write tokenizer assets (SentencePiece models, id-ordered vocab.txt) and meta.json

Output layout (consumed by the Android app, see ModelStore.java):
    <out>/models/<dir>/encoder.onnx
    <out>/models/<dir>/decoder.onnx
    <out>/models/<dir>/source.spm
    <out>/models/<dir>/target.spm
    <out>/models/<dir>/vocab.txt
    <out>/models/<dir>/meta.properties
    <out>/models/VERSION

Usage:
    python tools/prepare_models.py --out app/src/main/assets [--src-dir DIR] [--work DIR] [--smoke]
"""
import argparse
import hashlib
import json
import shutil
import sys
import time
from pathlib import Path

# Pinned revisions keep builds reproducible.  "main" is used when the pin is empty.
MODELS = {
    "ja-zh": {"repo": "shun89/opus-mt-ja-zh", "revision": "0728b51b9be02330f7bce262a4d47f611fd3a2a4"},
    "zh-ja": {"repo": "shun89/opus-mt-zh-ja", "revision": "8cf1db76350924be2aa40817e54ccdf411436592"},
}
FILES = [
    "config.json", "generation_config.json", "source.spm", "target.spm",
    "vocab.json", "tokenizer_config.json", "pytorch_model.bin",
]
PIPELINE_VERSION = "q1"


def log(msg):
    print("[prepare_models] " + msg, flush=True)


def fetch(name, spec, src_dir, work):
    """Return a directory containing the HF files for one direction."""
    if src_dir:
        d = Path(src_dir) / name
        if not (d / "pytorch_model.bin").exists():
            sys.exit("missing %s" % (d / "pytorch_model.bin"))
        return d, spec.get("revision") or "local"
    from huggingface_hub import snapshot_download
    path = snapshot_download(
        repo_id=spec["repo"],
        revision=spec["revision"] or None,
        allow_patterns=FILES,
        local_dir=str(work / "hf" / name),
    )
    return Path(path), spec.get("revision") or "main"


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


def write_vocab(vocab_json, dst):
    vocab = json.loads(Path(vocab_json).read_text(encoding="utf-8"))
    size = max(vocab.values()) + 1
    table = [None] * size
    for tok, idx in vocab.items():
        if table[idx] is None:
            table[idx] = tok
    for i, tok in enumerate(table):
        if tok is None:
            table[i] = "<unused_%d>" % i
        if "\n" in table[i] or "\r" in table[i]:
            sys.exit("vocab token %d contains a newline" % i)
    dst.write_text("\n".join(table) + "\n", encoding="utf-8")
    return size


def write_meta(model_dir, vocab_size, dst):
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
    dst.write_text("".join("%s=%s" % kv + chr(10) for kv in meta.items()), encoding="utf-8")
    return meta


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, help="assets directory (models/ is created inside)")
    ap.add_argument("--work", default="", help="scratch directory (default: <out>/../.model-work)")
    ap.add_argument("--src-dir", default="", help="use pre-downloaded <dir>/<ja-zh|zh-ja>/ instead of HuggingFace")
    ap.add_argument("--only", default="", help="process a single direction (ja-zh or zh-ja)")
    ap.add_argument("--smoke", action="store_true", help="run a translation smoke test on the produced files")
    args = ap.parse_args()

    out_models = Path(args.out) / "models"
    work = Path(args.work) if args.work else Path(args.out).resolve().parent / ".model-work"
    work.mkdir(parents=True, exist_ok=True)
    out_models.mkdir(parents=True, exist_ok=True)

    revs = []
    for name, spec in MODELS.items():
        if args.only and args.only != name:
            continue
        t0 = time.time()
        log("=== %s (%s)" % (name, spec["repo"]))
        model_dir, rev = fetch(name, spec, args.src_dir, work)
        revs.append(rev)

        exp = work / "onnx" / name
        if exp.exists():
            shutil.rmtree(exp)
        files = export_onnx(model_dir, exp)

        dst = out_models / name
        dst.mkdir(parents=True, exist_ok=True)
        quantize(files["encoder_model"], dst / "encoder.onnx")
        # The merged (If-based) decoder cannot be quantised directly because the quantiser
        # does not descend into sub-graphs.  Quantise both decoders separately and merge
        # afterwards; identical weights are de-duplicated by the merge.
        qdir = exp / "q"
        qdir.mkdir(exist_ok=True)
        quantize(files["decoder_model"], qdir / "decoder_q.onnx")
        quantize(files["decoder_with_past_model"], qdir / "decoder_with_past_q.onnx")
        from optimum.onnx import merge_decoders
        merge_decoders(qdir / "decoder_q.onnx", qdir / "decoder_with_past_q.onnx",
                       save_path=dst / "decoder.onnx", strict=False)
        log("merged decoder: %.1f MB" % ((dst / "decoder.onnx").stat().st_size / 1e6))
        shutil.copyfile(model_dir / "source.spm", dst / "source.spm")
        shutil.copyfile(model_dir / "target.spm", dst / "target.spm")
        size = write_vocab(model_dir / "vocab.json", dst / "vocab.txt")
        meta = write_meta(model_dir, size, dst / "meta.properties")
        log("%s done in %.0fs, meta=%s" % (name, time.time() - t0, meta))

    digest = hashlib.sha1((PIPELINE_VERSION + "|" + "|".join(revs)).encode()).hexdigest()[:12]
    (out_models / "VERSION").write_text(digest + "\n", encoding="utf-8")
    log("VERSION = " + digest)

    if args.smoke:
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        import smoke_test
        smoke_test.run(out_models)


if __name__ == "__main__":
    main()
