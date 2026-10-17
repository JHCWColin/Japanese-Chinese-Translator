package com.jhcwcolin.jctranslator;

import java.io.File;

/**
 * The translation models bundled in the APK. The choice is made per direction (中→日 and 日→中
 * can use different models).
 *
 * <p>{@link #key} is the folder name under assets/models and under files/models. OPUS-MT has one
 * model per direction (sub-folders ja-zh / zh-ja); the multilingual models serve both directions
 * from a single folder and are told the languages through language tokens.
 */
public enum ModelChoice {
    OPUS("opus-mt", "OPUS-MT", "标准版 · 体积小、速度快", "约 290 MB", false, null, null, 146),
    M2M("m2m100", "M2M100", "更聪明 · 多语种模型 4.18 亿参数", "约 0.5 GB", true, "__ja__", "__zh__", 500),
    NLLB("nllb", "NLLB-200", "最聪明 · 多语种模型 6 亿参数，最耗内存", "约 0.7 GB", true, "jpn_Jpan", "zho_Hans", 550),
    // Appended last on purpose: the choice is stored by ordinal. The settings list uses LIST_ORDER.
    OPUS_LITE("opus-lite", "OPUS-MT 精简版", "默认 · 更轻更快，裁剪词表，适合 1 GB 内存设备（译文略逊于标准版）", "约 175 MB", false, null, null, 90);

    /** Order in which the models are offered in the settings. */
    public static final ModelChoice[] LIST_ORDER = {OPUS, OPUS_LITE, M2M, NLLB};

    public final String key;
    public final String title;
    public final String desc;
    public final String size;
    public final boolean multilingual;
    private final String jaToken;
    private final String zhToken;
    /** Approximate size of the weights ONNX Runtime keeps mapped for one direction, in MB. */
    public final int weightMb;

    ModelChoice(String key, String title, String desc, String size, boolean multilingual, String jaToken, String zhToken,
                int weightMb) {
        this.key = key;
        this.title = title;
        this.desc = desc;
        this.size = size;
        this.multilingual = multilingual;
        this.jaToken = jaToken;
        this.zhToken = zhToken;
        this.weightMb = weightMb;
    }

    /** Folder (inside the models root) holding the files for {@code d}. */
    public File dir(File modelsRoot, Direction d) {
        File base = new File(modelsRoot, key);
        return multilingual ? base : new File(base, d.dir);
    }

    /** Language token of the source side, or null for single-direction models. */
    public String sourceToken(Direction d) {
        return multilingual ? (d.targetIsJapanese() ? zhToken : jaToken) : null;
    }

    public String targetToken(Direction d) {
        return multilingual ? (d.targetIsJapanese() ? jaToken : zhToken) : null;
    }

    public static ModelChoice fromId(int id) {
        ModelChoice[] all = values();
        return id >= 0 && id < all.length ? all[id] : OPUS;
    }
}
