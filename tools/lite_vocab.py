# -*- coding: utf-8 -*-
"""
Vocabulary pruning for the "OPUS-MT lite" models.

The OPUS-MT ja<->zh models share one 65 001-token vocabulary between both languages, and the
embedding matrix (65 001 x 512, stored three times: encoder input, decoder input, output
projection) is about two thirds of the model.  Most tokens are rare.  This module picks the
tokens that actually occur in ja / zh text (counted on a sample of the OPUS-100 corpus) and
rewrites the SentencePiece (BPE) models so that segmentation only ever produces kept tokens.

Everything here is build-time only.
"""
import collections
import sys
from pathlib import Path

SPACE = "▁"
# Pieces produced for the 0.5 % rarest characters are not worth a slot of their own.
MIN_SINGLE_CHAR_COUNT = 2


def log(msg):
    print("[lite_vocab] " + msg, flush=True)


# ---------------------------------------------------------------------------------------------
# corpus
# ---------------------------------------------------------------------------------------------

CORPUS_REPO = "Helsinki-NLP/opus-100"
CORPUS_FILES = {"ja": ("en-ja", "ja"), "zh": ("en-zh", "zh")}


def load_corpus(work_dir, sentences):
    """Returns {'ja': [str], 'zh': [str]} (OPUS-100 sample) or None when it cannot be downloaded."""
    try:
        import pyarrow.parquet as pq
        from huggingface_hub import hf_hub_download
        out = {}
        for lang, (cfg, col) in CORPUS_FILES.items():
            path = hf_hub_download(CORPUS_REPO, "%s/train-00000-of-00001.parquet" % cfg, repo_type="dataset",
                                   local_dir=str(Path(work_dir) / "corpus"))
            pf = pq.ParquetFile(path)
            rows = []
            for rg in range(pf.metadata.num_row_groups):
                for r in pf.read_row_group(rg, columns=["translation"]).to_pylist():
                    t = (r["translation"].get(col) or "").strip()
                    if 2 <= len(t) <= 400:
                        rows.append(t)
                if len(rows) >= sentences:
                    break
            out[lang] = rows[:sentences]
            log("corpus %s: %d sentences" % (lang, len(out[lang])))
        return out
    except Exception as e:  # network, missing pyarrow, schema change ...
        log("WARNING: corpus unavailable (%s: %s); falling back to score based pruning" % (type(e).__name__, e))
        return None


def count_pieces(sp, texts):
    counts = collections.Counter()
    batch = 5000
    for i in range(0, len(texts), batch):
        for pieces in sp.encode(texts[i:i + batch], out_type=str):
            counts.update(pieces)
    return counts


# ---------------------------------------------------------------------------------------------
# selection
# ---------------------------------------------------------------------------------------------

def _parents(piece, scores):
    """Best way to build `piece` by merging two shorter pieces of the same model (BPE closure)."""
    best = None
    best_key = None
    for cut in range(1, len(piece)):
        left, right = piece[:cut], piece[cut:]
        if left in scores and right in scores:
            key = min(scores[left], scores[right])
            if best_key is None or key > best_key:
                best, best_key = (left, right), key
    return best


def select(vocab, models, counts, specials, target):
    """
    vocab   : token -> old id (shared vocabulary)
    models  : side -> {piece: score} of that side's SentencePiece model (normal pieces only)
    counts  : side -> Counter(piece)
    specials: tokens that must be kept (</s>, <unk>, <pad>, ...)
    target  : desired number of kept tokens (the BPE closure may add a few more)
    returns : sorted list of old ids
    """
    keep = {t for t in specials if t in vocab}

    def add_with_ancestors(tok, side_scores):
        stack = [tok]
        while stack:
            t = stack.pop()
            if t in keep or t not in vocab:
                continue
            keep.add(t)
            if len(t) > 1:
                p = _parents(t, side_scores)
                if p:
                    stack.extend(p)

    # 1. single characters that occur at all often enough (anything else would become <unk>)
    for side, sc in models.items():
        for tok, n in counts[side].items():
            body = tok.replace(SPACE, "")
            if len(body) == 1 and n >= MIN_SINGLE_CHAR_COUNT:
                add_with_ancestors(tok, sc)

    # 2. the most frequent pieces overall (summed over both sides), with their merge ancestors
    total = collections.Counter()
    owner = {}
    for side, c in counts.items():
        for tok, n in c.items():
            total[tok] += n
            if tok not in owner or n > counts[owner[tok]][tok]:
                owner[tok] = side
    for tok, _ in total.most_common():
        if len(keep) >= target:
            break
        add_with_ancestors(tok, models[owner[tok]])
    return sorted(vocab[t] for t in keep)


def select_by_score(vocab, models, specials, target, char_ok):
    """Fallback without a corpus: keep script-allowed pieces with the best scores."""
    keep = {t for t in specials if t in vocab}
    ranked = []
    for side, sc in models.items():
        for tok, s in sc.items():
            body = tok.replace(SPACE, "")
            if tok in vocab and body and all(char_ok(ch) for ch in body):
                ranked.append((len(body) > 1, -s, tok))
    ranked.sort()  # single characters first, then by score (higher = better = smaller -score)
    for _, _, tok in ranked:
        if len(keep) >= target:
            break
        keep.add(tok)
    return sorted(vocab[t] for t in keep)


# ---------------------------------------------------------------------------------------------
# SentencePiece model rewrite
# ---------------------------------------------------------------------------------------------

def prune_spm(spm_bytes, kept_tokens):
    """Returns the serialised SentencePiece model with only the kept NORMAL pieces."""
    from sentencepiece import sentencepiece_model_pb2 as spb
    m = spb.ModelProto()
    m.ParseFromString(spm_bytes)
    normal = spb.ModelProto.SentencePiece.NORMAL
    pieces = [p for p in m.pieces if p.type != normal or p.piece in kept_tokens]
    del m.pieces[:]
    m.pieces.extend(pieces)
    return m.SerializeToString()


def spm_scores(spm_bytes):
    from sentencepiece import sentencepiece_model_pb2 as spb
    m = spb.ModelProto()
    m.ParseFromString(spm_bytes)
    normal = spb.ModelProto.SentencePiece.NORMAL
    return {p.piece: p.score for p in m.pieces if p.type == normal}


def evaluate(orig_bytes, pruned_bytes, texts, label):
    """Logs how much the pruned segmentation differs from the original one on held-out text."""
    import sentencepiece as spm
    a = spm.SentencePieceProcessor(model_proto=orig_bytes)
    b = spm.SentencePieceProcessor(model_proto=pruned_bytes)
    unk_a = unk_b = 0
    n_a = n_b = same = 0
    for i in range(0, len(texts), 2000):
        chunk = texts[i:i + 2000]
        for ea, eb in zip(a.encode(chunk, out_type=str), b.encode(chunk, out_type=str)):
            n_a += len(ea)
            n_b += len(eb)
            unk_a += ea.count("<unk>")
            unk_b += eb.count("<unk>")
            same += ea == eb
    log("%s: tokens/sentence %.2f -> %.2f (+%.1f%%), unk %.3f%% -> %.3f%%, identical segmentation %.1f%%" % (
        label, n_a / len(texts), n_b / len(texts), 100.0 * (n_b - n_a) / max(1, n_a),
        100.0 * unk_a / max(1, n_a), 100.0 * unk_b / max(1, n_b), 100.0 * same / len(texts)))
    return (n_b - n_a) / max(1, n_a), unk_b / max(1, n_b)


def select_prefix(vocab, models, specials, target, keep=None):
    """
    Keeps the pieces with the best SentencePiece (BPE merge) scores of both sides, alternating
    between the two models until `target` tokens are selected.  A prefix of a BPE merge list is
    closed under "built from two smaller pieces", so no ancestors have to be added.
    """
    keep = set(keep or ())
    keep.update(t for t in specials if t in vocab)
    ranked = {side: [t for t, _ in sorted(sc.items(), key=lambda kv: -kv[1]) if t in vocab]
              for side, sc in models.items()}
    pos = {side: 0 for side in ranked}
    sides = list(ranked)
    while len(keep) < target and any(pos[s] < len(ranked[s]) for s in sides):
        for side in sides:
            while pos[side] < len(ranked[side]) and ranked[side][pos[side]] in keep:
                pos[side] += 1
            if pos[side] < len(ranked[side]):
                keep.add(ranked[side][pos[side]])
                pos[side] += 1
            if len(keep) >= target:
                break
    return sorted(vocab[t] for t in keep)
