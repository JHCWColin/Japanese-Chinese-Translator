package com.jhcwcolin.jctranslator;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.jhcwcolin.jctranslator.data.HistoryDb;
import com.jhcwcolin.jctranslator.data.HistoryEntry;
import com.jhcwcolin.jctranslator.engine.CjkText;
import com.jhcwcolin.jctranslator.engine.LangDetect;
import com.jhcwcolin.jctranslator.engine.MarianTranslator;
import com.jhcwcolin.jctranslator.engine.ModelStore;
import com.jhcwcolin.jctranslator.engine.TextSegmenter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Process-wide translation state and worker. Activities only render this state, so a screen
 * rotation (activity re-creation) never interrupts a running translation.
 *
 * <p>All public fields and methods must be used from the main thread.
 */
public final class TranslatorController {

    public enum Phase { IDLE, INSTALLING, LOADING, TRANSLATING }

    public interface Listener {
        void onTranslatorStateChanged();
    }

    private static final String READY_STATUS = "就绪";
    private static final String READY_DETAIL = "完全离线 · 翻译在本机完成，无需联网";

    private static TranslatorController instance;

    public static synchronized TranslatorController get(Context c) {
        if (instance == null) {
            instance = new TranslatorController(c.getApplicationContext());
        }
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();

    // ---- state shown by the UI (main thread) --------------------------------------------------
    public Direction direction;
    public String draft = "";
    public String result = "";
    public Phase phase = Phase.IDLE;
    public String status = READY_STATUS;
    public String detail = READY_DETAIL;
    public boolean failed;

    private long startedAt;
    private String progressNote = "";

    // ---- engine (worker thread) ---------------------------------------------------------------
    private MarianTranslator translator;
    private File loadedDir;
    private int loadedThreads;
    private volatile MarianTranslator active;
    private volatile boolean cancelRequested;

    private TranslatorController(Context app) {
        this.app = app;
        Prefs.init(app);
        direction = Prefs.lastDirection();
    }

    public boolean isBusy() {
        return phase != Phase.IDLE;
    }

    // ---- listeners ----------------------------------------------------------------------------

    public void addListener(Listener l) {
        if (!listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void changed() {
        for (Listener l : new ArrayList<>(listeners)) {
            l.onTranslatorStateChanged();
        }
    }

    // ---- simple state changes -----------------------------------------------------------------

    public void setDirection(Direction d) {
        if (isBusy() || d == direction) {
            return;
        }
        direction = d;
        Prefs.setLastDirection(d);
        changed();
    }

    /** Swaps the language direction; an existing translation becomes the new input. */
    public void swapDirection() {
        if (isBusy()) {
            return;
        }
        direction = direction.flip();
        Prefs.setLastDirection(direction);
        if (!result.isEmpty()) {
            draft = result;
            result = "";
        }
        failed = false;
        status = "已互换翻译方向";
        detail = direction.label();
        changed();
    }

    /** Clears input, result and status. */
    public void clearAll() {
        if (isBusy()) {
            return;
        }
        draft = "";
        result = "";
        failed = false;
        status = READY_STATUS;
        detail = READY_DETAIL;
        changed();
    }

    /** Shows a stored history entry; nothing is translated again. */
    public boolean showEntry(HistoryEntry e) {
        if (isBusy()) {
            return false;
        }
        direction = e.direction;
        Prefs.setLastDirection(direction);
        draft = e.source;
        result = e.translation;
        failed = false;
        status = "已载入历史记录";
        detail = e.direction.label() + " · 直接显示已保存的译文，未重新翻译";
        changed();
        return true;
    }

    public void setNote(String status, String detail) {
        if (isBusy()) {
            return;
        }
        this.status = status;
        this.detail = detail;
        failed = false;
        changed();
    }

    public static void copyText(Context c, String label, String text) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
        }
    }

    // ---- translate ----------------------------------------------------------------------------

    private static final class Job {
        final String text;
        final Direction dir;
        final ModelChoice model;
        final int beams;
        final int threads;
        final int maxChars;
        final boolean lowMemory;
        final boolean saveHistory;
        final int historyMax;
        final boolean autoCopy;

        Job(String text, Direction dir, boolean lowMemory) {
            this.text = text;
            this.dir = dir;
            this.model = Prefs.model(dir);
            this.beams = Prefs.beams();
            this.threads = Prefs.effectiveThreads();
            this.maxChars = Prefs.maxSegmentChars();
            this.lowMemory = lowMemory;
            this.saveHistory = Prefs.historyEnabled();
            this.historyMax = Prefs.historyMax();
            this.autoCopy = Prefs.autoCopy();
        }
    }

    public void translate(String rawText) {
        if (isBusy()) {
            return;
        }
        String text = rawText == null ? "" : rawText.trim();
        draft = rawText == null ? "" : rawText;
        if (text.isEmpty()) {
            failed = false;
            status = "请先输入要翻译的文字";
            detail = "";
            changed();
            return;
        }
        Direction dir = direction;
        if (Prefs.autoDetect()) {
            int guess = LangDetect.guess(text);
            if (guess == LangDetect.JAPANESE) {
                dir = Direction.JA_ZH;
            } else if (guess == LangDetect.CHINESE) {
                dir = Direction.ZH_JA;
            }
            if (dir != direction) {
                direction = dir;
                Prefs.setLastDirection(dir);
            }
        }
        final Job job = new Job(text, dir, Prefs.lowMemory(app));

        main.removeCallbacks(idleRelease);
        cancelRequested = false;
        phase = Phase.TRANSLATING;
        failed = false;
        result = "";
        startedAt = SystemClock.elapsedRealtime();
        progressNote = "正在准备…";
        status = "正在翻译，请稍候…";
        composeDetail();
        main.removeCallbacks(ticker);
        main.postDelayed(ticker, 5000);
        changed();

        exec.execute(new Runnable() {
            @Override
            public void run() {
                runJob(job);
            }
        });
    }

    public void cancel() {
        if (!isBusy()) {
            return;
        }
        cancelRequested = true;
        MarianTranslator t = active;
        if (t != null) {
            t.cancel();
        }
        status = "正在取消…";
        changed();
    }

    /** Frees the ONNX sessions (the app keeps working; they are re-created on demand). */
    public void releaseModel() {
        if (isBusy()) {
            return;
        }
        exec.execute(new Runnable() {
            @Override
            public void run() {
                if (translator != null) {
                    translator.close();
                }
            }
        });
    }

    // ---- idle release (low-memory mode) ---------------------------------------------------------

    private static final long IDLE_RELEASE_MS = 45_000;

    private final Runnable idleRelease = new Runnable() {
        @Override
        public void run() {
            releaseModel();
        }
    };

    // ---- progress ticker (updates "waited N seconds" while busy) -------------------------------

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!isBusy()) {
                return;
            }
            composeDetail();
            changed();
            main.postDelayed(this, 5000);
        }
    };

    private void composeDetail() {
        long sec = (SystemClock.elapsedRealtime() - startedAt) / 1000;
        detail = sec >= 5 ? progressNote + " · 已等待 " + sec + " 秒" : progressNote;
    }

    private void publish(final Phase p, final String headline, final String note) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!isBusy()) {
                    return;
                }
                phase = p;
                status = cancelRequested ? "正在取消…" : headline;
                progressNote = note;
                composeDetail();
                changed();
            }
        });
    }

    // ---- worker -------------------------------------------------------------------------------

    private void closeEngine() {
        if (translator != null) {
            translator.close();
            translator = null;
            loadedDir = null;
        }
    }

    private void runJob(Job job) {
        try {
            final ModelChoice model = job.model;
            if (!ModelStore.isBundled(app, model)) {
                throw new java.io.IOException("安装包中没有「" + model.title + "」模型，请在设置中换用其他模型");
            }
            if (!ModelStore.isInstalled(app, model)) {
                final String head = "首次使用「" + model.title + "」：正在安装模型…";
                publish(Phase.INSTALLING, head,
                        "模型已内置在安装包中，只需解压一次（" + model.size + "，约 1～5 分钟），请勿退出");
                ModelStore.install(app, model, new ModelStore.Progress() {
                    @Override
                    public void onProgress(String message) {
                        publish(Phase.INSTALLING, head, message);
                    }
                });
                if (cancelRequested) {
                    throw new MarianTranslator.CancelledException();
                }
            }

            File modelDir = ModelStore.dirFor(app, model, job.dir);
            if (translator == null || !modelDir.equals(loadedDir) || loadedThreads != job.threads) {
                closeEngine();
                publish(Phase.LOADING, "正在加载翻译词表…", job.dir.label() + " · " + model.title);
                translator = new MarianTranslator(modelDir, job.threads);
                loadedDir = modelDir;
                loadedThreads = job.threads;
            }
            translator.setLanguages(model.sourceToken(job.dir), model.targetToken(job.dir));
            final MarianTranslator t = translator;
            active = t;
            t.resetCancel();
            if (cancelRequested) {
                throw new MarianTranslator.CancelledException();
            }

            List<List<String>> lines = TextSegmenter.split(job.text, job.maxChars);
            final int total = TextSegmenter.countSegments(lines);
            if (total == 0) {
                finishOk(job, "");
                return;
            }

            final String loadHint = job.lowMemory ? "省内存模式：分步加载，请耐心等待" : "首次加载较慢，请耐心等待";
            if (!t.isEncoderLoaded()) {
                publish(Phase.LOADING, "正在加载翻译模型（1/2）…", job.dir.label() + " · " + model.title + " · " + loadHint);
                t.loadEncoder();
            }

            // phase 1: encode every segment
            List<List<MarianTranslator.Encoded>> encoded = new ArrayList<>();
            int n = 0;
            for (List<String> line : lines) {
                List<MarianTranslator.Encoded> le = new ArrayList<>();
                for (String seg : line) {
                    if (cancelRequested) {
                        throw new MarianTranslator.CancelledException();
                    }
                    publish(Phase.TRANSLATING, "正在翻译，请稍候…", "分析原文 " + (n + 1) + " / " + total + " 段");
                    le.add(t.encode(seg));
                    n++;
                }
                encoded.add(le);
            }
            if (job.lowMemory) {
                t.closeEncoder();
            }

            if (!t.isDecoderLoaded()) {
                publish(Phase.LOADING, "正在加载翻译模型（2/2）…", job.dir.label() + " · " + model.title + " · " + loadHint);
                t.loadDecoder();
            }

            // phase 2: decode
            StringBuilder out = new StringBuilder();
            n = 0;
            for (int li = 0; li < encoded.size(); li++) {
                List<String> parts = new ArrayList<>();
                for (MarianTranslator.Encoded e : encoded.get(li)) {
                    if (cancelRequested) {
                        throw new MarianTranslator.CancelledException();
                    }
                    publish(Phase.TRANSLATING, "正在翻译，请稍候…", "翻译第 " + (n + 1) + " / " + total + " 段");
                    parts.add(CjkText.tidy(t.decode(e, job.beams), job.dir.targetIsJapanese()));
                    n++;
                }
                if (li > 0) {
                    out.append('\n');
                }
                out.append(CjkText.join(parts));
            }

            finishOk(job, out.toString());
        } catch (MarianTranslator.CancelledException e) {
            if (job.lowMemory && translator != null) {
                translator.close();
            }
            finishFailed("已取消", "本次翻译已停止，结果未保存", false);
        } catch (Throwable e) {
            closeEngine();
            finishFailed("翻译失败", describe(e), true);
        } finally {
            active = null;
        }
    }

    private static String describe(Throwable e) {
        if (e instanceof OutOfMemoryError) {
            return "内存不足。请关闭其他应用后重试，或在设置中选择「省内存」模式、降低翻译质量。";
        }
        if (e instanceof UnsatisfiedLinkError) {
            return "本设备的处理器架构不受支持（无法加载推理库）：" + e.getMessage();
        }
        String m = e.getMessage();
        if (m != null && (m.contains("alloc") || m.contains("memory"))) {
            return "内存不足：" + m + "。请关闭其他应用，或在设置中选择「省内存」模式。";
        }
        return e.getClass().getSimpleName() + (m == null ? "" : "：" + m);
    }

    private void finishOk(final Job job, final String translation) {
        if (job.saveHistory && !translation.isEmpty()) {
            try {
                HistoryDb.get(app).save(job.dir, job.text, translation, job.historyMax);
            } catch (RuntimeException ignored) {
                // history is a convenience; a database problem must not lose the translation
            }
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                main.removeCallbacks(ticker);
                double sec = (SystemClock.elapsedRealtime() - startedAt) / 1000.0;
                phase = Phase.IDLE;
                failed = false;
                result = translation;
                status = translation.isEmpty() ? "没有可翻译的内容" : "翻译完成";
                detail = String.format(Locale.US, "用时 %.1f 秒 · %s · %s", sec, job.dir.label(), job.model.title)
                        + (job.saveHistory && !translation.isEmpty() ? " · 已保存到历史记录" : "");
                if (job.autoCopy && !translation.isEmpty()) {
                    copyText(app, "translation", translation);
                    detail += " · 译文已复制";
                }
                if (job.lowMemory) {
                    main.removeCallbacks(idleRelease);
                    main.postDelayed(idleRelease, IDLE_RELEASE_MS);
                }
                changed();
            }
        });
    }

    private void finishFailed(final String headline, final String message, final boolean isError) {
        main.post(new Runnable() {
            @Override
            public void run() {
                main.removeCallbacks(ticker);
                phase = Phase.IDLE;
                failed = isError;
                status = headline;
                detail = message;
                changed();
            }
        });
    }
}
