package com.jhcwcolin.jctranslator.engine;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.os.StatFs;

import com.jhcwcolin.jctranslator.Direction;
import com.jhcwcolin.jctranslator.ModelChoice;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Copies the models bundled in the APK (assets/models) into the app's private storage, because
 * ONNX Runtime needs real files. Each model is extracted on first use (once per app/model version).
 */
public final class ModelStore {

    public interface Progress {
        void onProgress(String message);
    }

    private static final String[] FILES = {
            "encoder.onnx", "decoder.onnx", "source.spm", "target.spm", "vocab.txt", "meta.properties"
    };
    /** Multilingual models share one SentencePiece model, so they ship without target.spm. */
    private static final String[] FILES_MULTILINGUAL = {
            "encoder.onnx", "decoder.onnx", "source.spm", "vocab.txt", "meta.properties"
    };

    private static String[] filesOf(ModelChoice m) {
        return m.multilingual ? FILES_MULTILINGUAL : FILES;
    }
    /** Free space kept in reserve on top of the files being written. */
    private static final long HEADROOM_BYTES = 120L * 1024 * 1024;

    private ModelStore() {
    }

    public static File root(Context c) {
        return new File(c.getFilesDir(), "models");
    }

    /** Folder holding the files of {@code m} for direction {@code d}. */
    public static File dirFor(Context c, ModelChoice m, Direction d) {
        return m.dir(root(c), d);
    }

    /** The sub-folders of {@code m} that must be present (one per direction, or just one). */
    private static List<Direction> directions(ModelChoice m) {
        return m.multilingual ? Collections.singletonList(Direction.ZH_JA) : Arrays.asList(Direction.values());
    }

    private static String readFirstLine(InputStream in) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = br.readLine();
            return line == null ? "" : line.trim();
        }
    }

    private static String readAssetVersion(Context c, ModelChoice m) throws IOException {
        return readFirstLine(c.getAssets().open("models/" + m.key + "/VERSION"));
    }

    private static String readInstalledVersion(Context c, ModelChoice m) {
        File f = new File(new File(root(c), m.key), "VERSION");
        if (!f.isFile()) {
            return null;
        }
        try {
            return readFirstLine(new FileInputStream(f));
        } catch (IOException e) {
            return null;
        }
    }

    /** Whether the model is bundled in this APK at all. */
    public static boolean isBundled(Context c, ModelChoice m) {
        try {
            readAssetVersion(c, m);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isInstalled(Context c, ModelChoice m) {
        try {
            String want = readAssetVersion(c, m);
            String have = readInstalledVersion(c, m);
            if (have == null || !have.equals(want)) {
                return false;
            }
            for (Direction d : directions(m)) {
                for (String f : filesOf(m)) {
                    if (!new File(dirFor(c, m, d), f).isFile()) {
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static long assetSize(Context c, String path) {
        try (AssetFileDescriptor fd = c.getAssets().openFd(path)) {
            return fd.getLength();
        } catch (IOException e) {
            return 0; // compressed asset: size unknown, rely on the headroom check only
        }
    }

    private static String assetPath(ModelChoice m, Direction d, String name) {
        return "models/" + m.key + (m.multilingual ? "" : "/" + d.dir) + "/" + name;
    }

    /** Extracts one model; other models that are already extracted are left alone. */
    public static void install(Context c, ModelChoice m, Progress progress) throws IOException {
        String version = readAssetVersion(c, m);
        File modelRoot = new File(root(c), m.key);
        deleteRecursively(modelRoot);
        if (!modelRoot.mkdirs() && !modelRoot.isDirectory()) {
            throw new IOException("无法创建模型目录");
        }
        long need = HEADROOM_BYTES;
        for (Direction d : directions(m)) {
            for (String name : filesOf(m)) {
                need += assetSize(c, assetPath(m, d, name));
            }
        }
        StatFs fs = new StatFs(c.getFilesDir().getAbsolutePath());
        if (fs.getAvailableBytes() < need) {
            throw new IOException("存储空间不足：需要约 " + (need / 1024 / 1024) + " MB 可用空间，"
                    + "当前仅剩 " + (fs.getAvailableBytes() / 1024 / 1024) + " MB。请清理后重试。");
        }

        int total = directions(m).size() * filesOf(m).length;
        int done = 0;
        long copied = 0;
        byte[] buf = new byte[256 * 1024];
        for (Direction d : directions(m)) {
            File dir = dirFor(c, m, d);
            if (!dir.mkdirs() && !dir.isDirectory()) {
                throw new IOException("无法创建目录：" + dir);
            }
            for (String name : filesOf(m)) {
                File tmp = new File(dir, name + ".tmp");
                File dst = new File(dir, name);
                try (InputStream in = c.getAssets().open(assetPath(m, d, name));
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    int n;
                    long sinceReport = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        copied += n;
                        sinceReport += n;
                        if (sinceReport >= 8L * 1024 * 1024) {
                            sinceReport = 0;
                            progress.onProgress("已写入 " + (copied / 1024 / 1024) + " MB（第 " + (done + 1) + " / " + total + " 个文件）");
                        }
                    }
                    out.getFD().sync();
                }
                if (!tmp.renameTo(dst)) {
                    throw new IOException("无法写入：" + dst);
                }
                done++;
                progress.onProgress("已写入 " + (copied / 1024 / 1024) + " MB（已完成 " + done + " / " + total + " 个文件）");
            }
        }
        try (OutputStream out = new FileOutputStream(new File(modelRoot, "VERSION"))) {
            out.write((version + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursively(k);
                }
            }
        }
        f.delete();
    }
}
