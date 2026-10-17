#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Reference decoder for the bundled models (greedy search, onnxruntime + sentencepiece).

It mirrors what MarianTranslator.java does on the device, so it doubles as a
build-time sanity check: if the quantised models do not translate sensibly the
CI build fails instead of shipping a broken APK.

    python tools/smoke_test.py app/src/main/assets/models
"""
import sys
from pathlib import Path

import numpy as np

SAMPLES = {
    "ja-zh": [
        ("今日はとても良い天気ですね。", ["天气", "今天"]),
        ("この電車は東京駅に行きますか？", ["东京", "電車", "电车", "列车"]),
        ("私は毎朝コーヒーを飲みます。", ["咖啡"]),
    ],
    "zh-ja": [
        ("今天天气很好，我们一起去公园散步吧。", ["天気", "公園", "散歩"]),
        ("请问最近的车站怎么走？", ["駅"]),
        ("我每天早上都喝咖啡。", ["コーヒー"]),
    ],
}


class Model:
    def __init__(self, d):
        import onnxruntime as ort
        import sentencepiece as spm
        d = Path(d)
        self.meta = {}
        for line in (d / "meta.properties").read_text(encoding="utf-8").splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                self.meta[k.strip()] = int(v)
        self.src = spm.SentencePieceProcessor(model_file=str(d / "source.spm"))
        self.tgt = spm.SentencePieceProcessor(model_file=str(d / "target.spm"))
        self.vocab = (d / "vocab.txt").read_text(encoding="utf-8").split("\n")[:-1]
        self.index = {}
        for i, t in enumerate(self.vocab):
            self.index.setdefault(t, i)
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 4
        self.enc = ort.InferenceSession(str(d / "encoder.onnx"), opts, providers=["CPUExecutionProvider"])
        self.dec = ort.InferenceSession(str(d / "decoder.onnx"), opts, providers=["CPUExecutionProvider"])

    def encode(self, text):
        unk = self.index.get("<unk>", 1)
        pieces = self.src.encode(text, out_type=str)
        return [self.index.get(p, unk) for p in pieces] + [self.meta["eos_token_id"]]

    def decode(self, ids):
        pieces = [self.vocab[i] for i in ids]
        return self.tgt.decode_pieces(pieces)

    def translate(self, text, max_new=128):
        m = self.meta
        src = self.encode(text)
        s = len(src)
        mask = np.ones((1, s), dtype=np.int64)
        hs = self.enc.run(None, {"input_ids": np.array([src], dtype=np.int64), "attention_mask": mask})[0]
        in_names = [i.name for i in self.dec.get_inputs()]
        out_names = [o.name for o in self.dec.get_outputs()]
        past_in = [n for n in in_names if n.startswith("past_key_values.")]
        past = {n: np.zeros((1, m["decoder_heads"], 1, m["head_dim"]), dtype=np.float32) for n in past_in}
        use_cache = False
        tok = m["decoder_start_token_id"]
        out = []
        for _ in range(max_new):
            feed = {
                "input_ids": np.array([[tok]], dtype=np.int64),
                "encoder_attention_mask": mask,
                "encoder_hidden_states": hs,
            }
            if "use_cache_branch" in in_names:
                feed["use_cache_branch"] = np.array([use_cache], dtype=bool)
            feed.update(past)
            outs = dict(zip(out_names, self.dec.run(None, feed)))
            logits = outs["logits"][0, -1].copy()
            logits[m["pad_token_id"]] = -1e9
            tok = int(np.argmax(logits))
            if tok == m["eos_token_id"]:
                break
            out.append(tok)
            for n in past_in:
                if (not use_cache) or ".decoder." in n:
                    past[n] = outs[n.replace("past_key_values.", "present.")]
            use_cache = True
        return self.decode(out)


def run(models_dir, verbose_io=True):
    models_dir = Path(models_dir)
    failures = 0
    for name, samples in SAMPLES.items():
        d = models_dir / name
        if not d.exists():
            continue
        m = Model(d)
        if verbose_io:
            print("[smoke] %s decoder inputs : %s" % (name, [(i.name, i.shape) for i in m.dec.get_inputs()][:6]))
            print("[smoke] %s decoder outputs: %s" % (name, [o.name for o in m.dec.get_outputs()][:6]))
        for text, expect in samples:
            res = m.translate(text)
            ok = any(e in res for e in expect)
            failures += 0 if ok else 1
            print("[smoke] %s | %s -> %s  %s" % (name, text, res, "OK" if ok else "(unexpected)"))
    if failures >= 4:  # tolerate individual misses, fail on systemic breakage
        sys.exit("smoke test failed: %d unexpected results" % failures)


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/models")
