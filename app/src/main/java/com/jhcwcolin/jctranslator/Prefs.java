package com.jhcwcolin.jctranslator;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;

/** Typed access to the app settings. Every write bumps {@link #stamp()} so open screens can refresh. */
public final class Prefs {

    public static final int ORIENT_FOLLOW = 0;
    public static final int ORIENT_PORTRAIT = 1;
    public static final int ORIENT_LANDSCAPE = 2;

    public static final int MEMORY_AUTO = 0;
    public static final int MEMORY_LOW = 1;
    public static final int MEMORY_RESIDENT = 2;

    public static final int[] TEXT_SIZES = {16, 18, 20, 22, 24, 28, 32, 36, 40, 48};
    public static final int[] UI_SIZES = {14, 16, 18, 20, 22, 24};
    public static final float[] LINE_SPACING = {1.0f, 1.25f, 1.5f};

    private static SharedPreferences sp;
    private static int stamp = 0;

    private Prefs() {
    }

    public static synchronized void init(Context c) {
        if (sp == null) {
            sp = c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
        }
    }

    public static int stamp() {
        return stamp;
    }

    private static void touch() {
        stamp++;
    }

    // ---- display ----------------------------------------------------------------------------

    public static int orientation() {
        return sp.getInt("orientation", ORIENT_FOLLOW);
    }

    public static void setOrientation(int v) {
        sp.edit().putInt("orientation", v).apply();
        touch();
    }

    /** Value for {@code Activity.setRequestedOrientation}. */
    public static int requestedOrientation() {
        switch (orientation()) {
            case ORIENT_PORTRAIT:
                return ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
            case ORIENT_LANDSCAPE:
                return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
            default:
                return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
    }

    public static boolean dark() {
        return sp.getBoolean("dark", false);
    }

    public static void setDark(boolean v) {
        sp.edit().putBoolean("dark", v).apply();
        touch();
    }

    public static int textSizeIndex() {
        int sz = sp.getInt("text_size", 24);
        int best = 0;
        for (int i = 0; i < TEXT_SIZES.length; i++) {
            if (TEXT_SIZES[i] == sz) {
                best = i;
            }
        }
        return best;
    }

    public static int textSizeSp() {
        return scaled(TEXT_SIZES[textSizeIndex()]);
    }

    public static void setTextSizeIndex(int idx) {
        idx = Math.max(0, Math.min(TEXT_SIZES.length - 1, idx));
        sp.edit().putInt("text_size", TEXT_SIZES[idx]).apply();
        touch();
    }

    public static int uiSizeIndex() {
        int sz = sp.getInt("ui_size", 18);
        int best = 0;
        for (int i = 0; i < UI_SIZES.length; i++) {
            if (UI_SIZES[i] == sz) {
                best = i;
            }
        }
        return best;
    }

    public static int uiSizeSp() {
        return scaled(UI_SIZES[uiSizeIndex()]);
    }

    public static void setUiSizeIndex(int idx) {
        idx = Math.max(0, Math.min(UI_SIZES.length - 1, idx));
        sp.edit().putInt("ui_size", UI_SIZES[idx]).apply();
        touch();
    }

    public static int lineSpacingIndex() {
        return Math.max(0, Math.min(LINE_SPACING.length - 1, sp.getInt("line_spacing", 1)));
    }

    public static float lineSpacing() {
        return LINE_SPACING[lineSpacingIndex()];
    }

    public static void setLineSpacingIndex(int v) {
        sp.edit().putInt("line_spacing", v).apply();
        touch();
    }

    public static boolean bold() {
        return sp.getBoolean("bold", false);
    }

    public static void setBold(boolean v) {
        sp.edit().putBoolean("bold", v).apply();
        touch();
    }

    // ---- appearance personalisation ----------------------------------------------------------

    public static final int[] FONT_SCALES = {80, 90, 100, 110, 125, 150};
    /** Corner radius in dp for the three corner styles: square / slightly rounded / pill. */
    public static final int[] CORNERS_DP = {0, 8, 24};
    public static final int[] BORDERS_DP = {1, 2, 3, 4};
    public static final int[] BUTTON_HEIGHTS_DP = {40, 52, 64, 76};

    public static final int LAYOUT_AUTO = 0;
    public static final int LAYOUT_STACKED = 1;
    public static final int LAYOUT_SIDE = 2;

    private static int scaled(int base) {
        return Math.max(8, Math.round(base * FONT_SCALES[fontScaleIndex()] / 100f));
    }

    public static int fontScaleIndex() {
        return Math.max(0, Math.min(FONT_SCALES.length - 1, sp.getInt("font_scale", 2)));
    }

    public static void setFontScaleIndex(int v) {
        sp.edit().putInt("font_scale", v).apply();
        touch();
    }

    public static int cornerIndex() {
        return Math.max(0, Math.min(CORNERS_DP.length - 1, sp.getInt("corner", 0)));
    }

    public static int cornerDp() {
        return CORNERS_DP[cornerIndex()];
    }

    public static void setCornerIndex(int v) {
        sp.edit().putInt("corner", v).apply();
        touch();
    }

    public static int borderIndex() {
        return Math.max(0, Math.min(BORDERS_DP.length - 1, sp.getInt("border", 1)));
    }

    public static int borderDp() {
        return BORDERS_DP[borderIndex()];
    }

    public static void setBorderIndex(int v) {
        sp.edit().putInt("border", v).apply();
        touch();
    }

    public static int buttonHeightIndex() {
        return Math.max(0, Math.min(BUTTON_HEIGHTS_DP.length - 1, sp.getInt("button_height", 1)));
    }

    public static int buttonHeightDp() {
        return BUTTON_HEIGHTS_DP[buttonHeightIndex()];
    }

    public static void setButtonHeightIndex(int v) {
        sp.edit().putInt("button_height", v).apply();
        touch();
    }

    public static int boxLayout() {
        return sp.getInt("box_layout", LAYOUT_AUTO);
    }

    public static void setBoxLayout(int v) {
        sp.edit().putInt("box_layout", v).apply();
        touch();
    }

    public static boolean showStatus() {
        return sp.getBoolean("show_status", true);
    }

    public static void setShowStatus(boolean v) {
        sp.edit().putBoolean("show_status", v).apply();
        touch();
    }

    public static boolean pureMode() {
        return sp.getBoolean("pure_mode", false);
    }

    public static void setPureMode(boolean v) {
        sp.edit().putBoolean("pure_mode", v).apply();
        touch();
    }

    // ---- input & translation ----------------------------------------------------------------

    public static boolean autoHideKeyboard() {
        return sp.getBoolean("auto_hide_ime", true);
    }

    public static void setAutoHideKeyboard(boolean v) {
        sp.edit().putBoolean("auto_hide_ime", v).apply();
        touch();
    }

    public static boolean lockInputWhileTranslating() {
        return sp.getBoolean("lock_input", true);
    }

    public static void setLockInputWhileTranslating(boolean v) {
        sp.edit().putBoolean("lock_input", v).apply();
        touch();
    }

    public static boolean autoDetect() {
        return sp.getBoolean("auto_detect", true);
    }

    public static void setAutoDetect(boolean v) {
        sp.edit().putBoolean("auto_detect", v).apply();
        touch();
    }

    public static boolean autoCopy() {
        return sp.getBoolean("auto_copy", false);
    }

    public static void setAutoCopy(boolean v) {
        sp.edit().putBoolean("auto_copy", v).apply();
        touch();
    }

    /** Beam width: 1 = fast, 2 = balanced, 4 = precise. */
    public static int beams() {
        int b = sp.getInt("beams", 4);
        return b == 1 || b == 2 ? b : 4;
    }

    public static void setBeams(int v) {
        sp.edit().putInt("beams", v).apply();
        touch();
    }

    /** 0 = automatic (min(cores, 4)). */
    public static int threads() {
        return sp.getInt("threads", 0);
    }

    public static void setThreads(int v) {
        sp.edit().putInt("threads", v).apply();
        touch();
    }

    public static int effectiveThreads() {
        int t = threads();
        if (t > 0) {
            return t;
        }
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    }

    public static int maxSegmentChars() {
        return sp.getInt("max_seg_chars", 100);
    }

    public static void setMaxSegmentChars(int v) {
        sp.edit().putInt("max_seg_chars", v).apply();
        touch();
    }

    public static int memoryMode() {
        return sp.getInt("memory_mode", MEMORY_AUTO);
    }

    public static void setMemoryMode(int v) {
        sp.edit().putInt("memory_mode", v).apply();
        touch();
    }

    /**
     * Resolves {@link #memoryMode()} for {@code model}. Automatic mode keeps the sessions resident
     * only when the memory that is available right now covers both of them (the weights are
     * memory-mapped, plus roughly the same again for ONNX Runtime's working buffers) with a
     * reserve left for the system; otherwise encoder and decoder are loaded one after the other.
     */
    public static boolean lowMemory(Context c, ModelChoice model) {
        int mode = memoryMode();
        if (mode == MEMORY_LOW) {
            return true;
        }
        if (mode == MEMORY_RESIDENT) {
            return false;
        }
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long needBytes = (2L * model.weightMb + 220L) * 1024 * 1024;
        return mi.totalMem < 1024L * 1024 * 1024 && model.weightMb > 200 || mi.availMem < needBytes;
    }

    public static Direction lastDirection() {
        return Direction.fromId(sp.getInt("direction", 0));
    }

    public static void setLastDirection(Direction d) {
        sp.edit().putInt("direction", d.ordinal()).apply(); // no stamp: not a display setting
    }

    /** Model used for one direction (zh to ja and ja to zh are chosen independently). */
    public static ModelChoice model(Direction d) {
        // The target device has 1 GB RAM and commonly has less than 400 MB free after the
        // reader/keyboard are started.  Make the pruned model the first-run default so the
        // standard OPUS-MT remains available as an explicit quality/size choice.
        return ModelChoice.fromId(sp.getInt("model_" + d.dir, ModelChoice.OPUS_LITE.ordinal()));
    }

    public static void setModel(Direction d, ModelChoice m) {
        sp.edit().putInt("model_" + d.dir, m.ordinal()).apply(); // no stamp: not a display setting
    }

    // ---- history -----------------------------------------------------------------------------

    public static boolean historyEnabled() {
        return sp.getBoolean("history_enabled", true);
    }

    public static void setHistoryEnabled(boolean v) {
        sp.edit().putBoolean("history_enabled", v).apply();
        touch();
    }

    public static int historyMax() {
        return sp.getInt("history_max", 500);
    }

    public static void setHistoryMax(int v) {
        sp.edit().putInt("history_max", v).apply();
        touch();
    }

    public static int historyPageSize() {
        return sp.getInt("history_page", 8);
    }

    public static void setHistoryPageSize(int v) {
        sp.edit().putInt("history_page", v).apply();
        touch();
    }
}
