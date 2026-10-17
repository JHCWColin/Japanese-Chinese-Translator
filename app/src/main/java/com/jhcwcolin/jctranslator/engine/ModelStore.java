package com.jhcwcolin.jctranslator.engine;

import android.content.Context;
import android.os.StatFs;

import com.jhcwcolin.jctranslator.Direction;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Copies the models bundled in the APK (assets/models) into the app's private storage, because
 * ONNX Runtime needs real files. Happens once per app/model version.
 */
public final class ModelStore {

    public interface Progress {
        void onProgress(String message);
    }

    private static final String[] FILES = {
            "encoder.onnx", "decoder.onnx", "source.spm", "target.spm", "vocab.txt", "meta.properties"
    };
    /** Rough space needed for the extracted models plus working room. */
    private static final long REQUIRED_BYTES = 360L * 1024 * 1024;

    private ModelStore() {
    }

    public static File root(Context c) {
        return new File(c.getFilesDir(), "models");
    }

    public static File dirFor(Context c, Direction d) {
        return new File(root(c), d.dir);
    }

    private static String readAssetVersion(Context c) throws IOException {
        try (InputStream in = c.getAssets().open("models/VERSION");
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = br.readLine();
            return line == null ? "" : line.trim();
        }
    }

    private static String readInstalledVersion(Context c) {
        File f = new File(root(c), "VERSION");
        if (!f.isFile()) {
            return null;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            String line = br.readLine();
            return line == null ? null : line.trim();
        } catch (IOException e) {
            return null;
        }
    }

    public static boolean isInstalled(Context c) {
        try {
            String want = readAssetVersion(c);
            String have = readInstalledVersion(c);
            if (have == null || !have.equals(want)) {
                return false;
            }
            for (Direction d : Direction.values()) {
                for (String f : FILES) {
                    if (!new File(dirFor(c, d), f).isFile()) {
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static void install(Context c, Progress progress) throws IOException {
        String version = readAssetVersion(c);
        File root = root(c);
        deleteRecursively(root);
        if (!root.mkdirs() && !root.isDirectory()) {
            throw new IOException("无法创建模型目录");
        }
        StatFs fs = new StatFs(c.getFilesDir().getAbsolutePath());
        if (fs.getAvailableBytes() < REQUIRED_BYTES) {
            throw new IOException("存储空间不足：需要约 " + (REQUIRED_BYTES / 1024 / 1024) + " MB 可用空间，"
                    + "当前仅剩 " + (fs.getAvailableBytes() / 1024 / 1024) + " MB。请清理后重试。");
        }

        int total = Direction.values().length * FILES.length;
        int done = 0;
        long copied = 0;
        byte[] buf = new byte[256 * 1024];
        for (Direction d : Direction.values()) {
            File dir = dirFor(c, d);
            if (!dir.mkdirs() && !dir.isDirectory()) {
                throw new IOException("无法创建目录：" + dir);
            }
            for (String name : FILES) {
                File tmp = new File(dir, name + ".tmp");
                File dst = new File(dir, name);
                try (InputStream in = c.getAssets().open("models/" + d.dir + "/" + name);
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
        try (OutputStream out = new FileOutputStream(new File(root, "VERSION"))) {
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
