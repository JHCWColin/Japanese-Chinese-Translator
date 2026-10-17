#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Writes reference SentencePiece tokenisations (from the official library) that
EngineSmoke.java compares against the pure-Java implementation.

    python tools/jvmtest/make_sp_reference.py <models_dir> <out.tsv>

Each line:  <direction>\t<text with \\n escaped>\t<pieces joined by a single space>
"""
import sys
from pathlib import Path

import sentencepiece as spm

TEXTS = [
    "今日はとても良い天気ですね。",
    "この電車は東京駅に行きますか？",
    "私は毎朝コーヒーを飲みます。",
    "  先頭と末尾に   空白があります  ",
    "ｶﾀｶﾅ半角とＦＵＬＬＷＩＤＴＨ１２３です",
    "㈱ジャパン、①②③ ㌔ ㍿ ℃",
    "今天天气很好，我们一起去公园散步吧。",
    "请问最近的车站怎么走？",
    "我每天早上都喝咖啡，然后去上班（大概八点）。",
    "Hello world, this is a MIXED text 123 with 日本語 and 中文!",
    "「引用符」『二重』【見出し】《書名》",
    "東京都千代田区丸の内一丁目九番一号",
    "他说：“你好！”然后走了……",
    "価格は￥1,200（税込）です。",
    "Ｃｏｌｏｒ　ｏｆ　ｓｐａｃｅ",
    "line1 line2\ttabbed",
    "𠮷野家で𩸽を食べた。",
    "ありがとうございます!!! すごい～",
    "漢字かな交じり文のテストです。長い文章でも正しく分割されるでしょうか。",
    "这是一个很长的句子，用来测试分词器在遇到标点符号、数字 2024 以及英文单词 translator 时的表现。",
]


def main():
    models = Path(sys.argv[1])
    out = Path(sys.argv[2])
    lines = []
    for d in ("ja-zh", "zh-ja"):
        for side in ("source", "target"):
            sp = spm.SentencePieceProcessor(model_file=str(models / d / (side + ".spm")))
            for t in TEXTS:
                pieces = sp.encode(t, out_type=str)
                lines.append("%s/%s\t%s\t%s" % (d, side, t.replace("\n", "\\n").replace("\t", "\\t"), " ".join(pieces)))
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("wrote", len(lines), "cases")


if __name__ == "__main__":
    main()
