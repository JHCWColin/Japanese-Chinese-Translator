#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Quick quality comparison of the converted models on real ja<->zh parallel sentences
(news_commentary ja-zh, formal text that none of the models was pruned on).

    python tools/eval_models.py <models_dir> [key[,key...]] [--n 150] [--work DIR]

Prints chrF (character n-gram F-score against the reference translation) per model and direction.
Greedy decoding, same code path as tools/smoke_test.py.
"""
import argparse
import collections
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))


def load_pairs(work, n):
    import pyarrow.parquet as pq
    from huggingface_hub import hf_hub_download
    path = hf_hub_download("Helsinki-NLP/news_commentary", "ja-zh/train-00000-of-00001.parquet",
                           repo_type="dataset", local_dir=str(Path(work) / "eval"))
    rows = pq.ParquetFile(path).read().to_pylist()
    pairs = []
    for r in rows:
        t = r["translation"]
        ja, zh = (t.get("ja") or "").strip(), (t.get("zh") or "").strip()
        if 12 <= len(ja) <= 110 and 8 <= len(zh) <= 110:
            pairs.append((ja, zh))
    step = max(1, len(pairs) // n)
    return pairs[::step][:n]


def ngrams(s, n):
    s = "".join(s.split())
    return collections.Counter(s[i:i + n] for i in range(len(s) - n + 1))


def chrf(hyp, ref, max_n=6, beta=2.0):
    ps, rs = [], []
    for n in range(1, max_n + 1):
        h, r = ngrams(hyp, n), ngrams(ref, n)
        if not h or not r:
            continue
        match = sum((h & r).values())
        ps.append(match / sum(h.values()))
        rs.append(match / sum(r.values()))
    if not ps:
        return 0.0
    p, r = sum(ps) / len(ps), sum(rs) / len(rs)
    return 0.0 if p + r == 0 else (1 + beta ** 2) * p * r / (beta ** 2 * p + r) * 100


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("models_dir")
    ap.add_argument("keys", nargs="?", default="opus-mt,opus-lite")
    ap.add_argument("--n", type=int, default=150)
    ap.add_argument("--work", default=".model-work")
    args = ap.parse_args()

    from smoke_test import Model
    pairs = load_pairs(args.work, args.n)
    print("evaluating on %d sentence pairs (news_commentary ja-zh)" % len(pairs))
    for key in args.keys.split(","):
        for name, src_i, ref_i in (("ja-zh", 0, 1), ("zh-ja", 1, 0)):
            d = Path(args.models_dir) / key / name
            if not d.exists():
                continue
            m = Model(d)
            t0 = time.time()
            scores, shown = [], 0
            for pair in pairs:
                hyp = m.translate(pair[src_i], max_new=160)
                scores.append(chrf(hyp, pair[ref_i]))
                if shown < 2:
                    print("   %s | %s\n      -> %s\n      ref %s" % (name, pair[src_i], hyp, pair[ref_i]))
                    shown += 1
            print("%-10s %-6s chrF %.2f   (%.1fs)" % (key, name, sum(scores) / len(scores), time.time() - t0), flush=True)


if __name__ == "__main__":
    main()
