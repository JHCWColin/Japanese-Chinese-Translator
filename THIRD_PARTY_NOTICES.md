# 第三方组件与许可

本项目的 APK 内置了下列第三方成果，请在再分发时保留此说明。

## 翻译模型

| 方向 | 来源 | 许可 |
|---|---|---|
| 日语 → 中文 | [`shun89/opus-mt-ja-zh`](https://huggingface.co/shun89/opus-mt-ja-zh)（revision `0728b51b`） | Apache-2.0（模型页声明） |
| 中文 → 日语 | [`shun89/opus-mt-zh-ja`](https://huggingface.co/shun89/opus-mt-zh-ja)（revision `8cf1db76`） | 模型页**未声明许可**，属于 OPUS-MT 的衍生模型 |

两者都是基于 [Helsinki-NLP OPUS-MT](https://github.com/Helsinki-NLP/Opus-MT)（Marian NMT，**CC BY 4.0**）的 ja↔zh 微调权重。

构建时由 `tools/prepare_models.py` 下载原始 PyTorch 权重，转换为 ONNX 并做动态 int8 量化后打入 APK；仓库中**不包含**模型文件。

> 如果你要公开分发 APK，请自行确认上述模型的许可条款是否满足你的用途；`shun89/opus-mt-zh-ja` 未声明许可，作者可能保留权利。

## 推理引擎

- [ONNX Runtime](https://github.com/microsoft/onnxruntime)（`com.microsoft.onnxruntime:onnxruntime-android`），MIT License。

## 分词

- 应用内的 SentencePiece（BPE / unigram + `nmt_nfkc` 归一化）是对 [google/sentencepiece](https://github.com/google/sentencepiece)（Apache-2.0）行为的纯 Java 重新实现，只读取标准 `.spm` 模型文件，已与官方库的输出逐条对拍。

## 构建工具（仅构建时使用，不打入 APK）

- PyTorch、Transformers、Optimum、ONNX、ONNX Runtime（Python）、sentencepiece（Python）。
