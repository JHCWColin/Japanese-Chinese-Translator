# 日中互译 · Japanese-Chinese Translator

完全离线的 **日语 ⇄ 中文** 翻译 Android 应用。翻译模型**内置在 APK 里**，不联网、不上传任何文字。
为 **Android 8.x、1 GB 内存的墨水屏设备**设计：纯黑白、无动画、粗边框、大按钮。

## 功能

- **日→中 / 中→日 直译**（不经过英语中转），支持多行、长文本（自动按句 / 按分句拆分）
- 墨水屏界面：黑白高对比，按下 / 选中反色；无过渡动画、无阴影、无灰色填充；翻页按钮代替滚动
- 点「翻译」后**自动收起输入法**，并在翻译期间把输入框设为只读；翻译过程有**明确的等待提示**（阶段 + 段数 + 已等待秒数），可随时「停止翻译」
- 设置页：屏幕方向（**跟随系统 / 竖屏 / 横屏**）、配色、输入与译文字号、界面字号、行距、加粗、全屏刷新（清除残影）、翻译质量（束宽）、线程数、内存模式、历史记录选项等
- **历史记录同时保存原文与译文**，查看时直接显示已保存的译文，**不会重新翻译**；支持搜索、分页、复制、删除
- 自动识别输入语言（含日文假名 → 日语；含简体中文特征字 → 中文）

## 模型

设置页最上方可以**分别为「中→日」和「日→中」选择模型**（1 GB 设备默认精简版）：

| 模型 | 来源 | 说明 | 解压后体积 |
|---|---|---|---|
| OPUS-MT 精简版（默认） | [`shun89/opus-mt-ja-zh`](https://huggingface.co/shun89/opus-mt-ja-zh)、[`shun89/opus-mt-zh-ja`](https://huggingface.co/shun89/opus-mt-zh-ja) | 同一 Marian base 裁剪低频词表，适合 1 GB 内存设备 | ~175 MB |
| OPUS-MT（标准版） | 同上 | Marian base（77M 参数），每个方向一个模型，词表完整 | ~290 MB |
| M2M100 | [`facebook/m2m100_418M`](https://huggingface.co/facebook/m2m100_418M)（MIT） | 4.18 亿参数多语种模型，两个方向共用一份，更聪明 | ~0.5 GB |
| NLLB-200 | [`facebook/nllb-200-distilled-600M`](https://huggingface.co/facebook/nllb-200-distilled-600M)（CC BY-NC 4.0，仅限非商业） | 6 亿参数多语种模型，质量最高，也最慢最耗内存 | ~0.7 GB |

说明：

- OPUS-MT 是体积足够小且真正的日↔中直译模型（Helsinki-NLP 官方没有 ja→zh；`opus-mt-tc-big-zh-ja` 只有一个方向且质量一般）。动态 int8 量化后标准版每个方向约 **146 MB**，精简版约 **90 MB**。
- M2M100 / NLLB 的词表很大（12.8 万 / 25.6 万），`tools/prepare_models.py` 会在导出前把词表裁剪到汉字、假名、ASCII 与常用符号（嵌入矩阵、SentencePiece 模型、`vocab.txt` 一并改写），再做 int8 量化，以降低内存占用并加快输出层。
- 多语种模型通过语言标记工作：原文前加源语言标记，解码器首个词强制为目标语言标记。
- 四个模型都内置在 APK 里（标准安装包含 OPUS-MT、精简版和 M2M100；完整安装包再加入 NLLB-200），**只有第一次使用某个模型时才解压它**；每个模型只在用到时才加载。
- 1 GB 内存的设备上，M2M100 / NLLB 会很吃紧（建议保持「省内存」模式、质量选「快速」）；它们的实机表现尚未验证。

推理使用 **ONNX Runtime**（Android 版）；分词是纯 Java 重新实现的 SentencePiece（BPE + `nmt_nfkc` 归一化），已与官方库对拍一致。解码为带 KV 缓存的束搜索（快速 / 均衡 / 精准 = 束宽 1 / 2 / 4）。

> 机器翻译可能出错，重要内容请人工核对。模型许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 安装

在 [Releases](https://github.com/JHCWColin/Japanese-Chinese-Translator/releases) 下载 `JapaneseChineseTranslator-v*.apk` 并安装。

- 系统：Android 8.0+（目标设备 Android 8.1），ARM 32 位或 64 位
- 首次使用某个模型时会把它解压到应用私有目录（精简 OPUS-MT 约 175 MB、标准版约 290 MB，M2M100 / NLLB 各约 0.5 / 0.7 GB，仅一次）。请按用到的模型预留存储
- 升级时请安装同一发布渠道的 APK（签名一致才能覆盖安装并保留历史）

### 内存说明（1 GB 设备）

「设置 → 性能与内存 → 内存模式」：

- **省内存**（内存小于 2 GB 时「自动」选用）：编码器与解码器分步加载以降低峰值内存，空闲 45 秒后自动释放
- **常驻**：模型留在内存里，翻译更快但占用更多
- 应用在后台或系统内存紧张时会自动释放模型

如果翻译失败并提示内存不足：关闭其他应用，选「省内存」，把「翻译质量」调为「快速」，线程数调为 1～2。

## 构建

APK 由 GitHub Actions 构建（`.github/workflows/build-release.yml`）：

1. 下载上述模型的 PyTorch 权重（多语种模型先裁剪词表），用 Optimum 导出 ONNX（编码器 + 带 KV 缓存的解码器），做 int8 量化，生成词表与元数据（`tools/prepare_models.py`）并做一次翻译冒烟测试
2. 把模型放进 `app/src/main/assets/models/`（该目录不入库）
3. `./gradlew assembleRelease` 构建并签名
4. 推送 `v*` 标签时发布到 Releases

发布新版本：

```bash
git tag v1.0.1
git push origin v1.0.1
```

### 签名密钥

在仓库 Settings → Secrets and variables → Actions 中配置 `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`，所有版本即可使用同一签名。没有配置时工作流会临时生成密钥（不同版本之间无法覆盖安装）。

### 本地构建

需要 JDK 17、Android SDK（platform 34、build-tools 34.0.0）和 Python 3.10+：

```bash
pip install torch==2.5.1 --index-url https://download.pytorch.org/whl/cpu
pip install -r tools/requirements.txt
python tools/prepare_models.py --out app/src/main/assets --smoke
./gradlew assembleRelease
```

### 在电脑上测试引擎（无需 Android）

`engine/` 目录是纯 Java（仅依赖 ONNX Runtime），可以在桌面 JVM 上直接测试：

```bash
python tools/jvmtest/make_sp_reference.py app/src/main/assets/models sp_ref.tsv
javac -encoding UTF-8 -cp onnxruntime-1.22.0.jar -d out \
  app/src/main/java/com/jhcwcolin/jctranslator/engine/*.java tools/jvmtest/EngineSmoke.java
java -cp out:onnxruntime-1.22.0.jar EngineSmoke app/src/main/assets/models sp_ref.tsv 4
```

## 目录结构

```
app/src/main/java/com/jhcwcolin/jctranslator/
  engine/      SentencePiece、Marian 束搜索、分句、模型安装（纯 Java，可在 JVM 上测试）
  data/        历史记录（SQLite）
  ui/          墨水屏控件工具、BaseActivity
  MainActivity / HistoryActivity / SettingsActivity / RefreshActivity
  TranslatorController   翻译状态与后台线程（旋转屏幕不会打断翻译）
tools/         模型准备脚本、冒烟测试、JVM 测试
```
