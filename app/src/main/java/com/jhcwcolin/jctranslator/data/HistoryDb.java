package com.jhcwcolin.jctranslator.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.jhcwcolin.jctranslator.Direction;

import java.util.ArrayList;
import java.util.List;

/** SQLite store of translation history (source + translation, never re-translated on view). */
public final class HistoryDb extends SQLiteOpenHelper {

    private static HistoryDb instance;

    public static synchronized HistoryDb get(Context c) {
        if (instance == null) {
            instance = new HistoryDb(c.getApplicationContext());
        }
        return instance;
    }

    private HistoryDb(Context c) {
        super(c, "history.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE history ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "dir INTEGER NOT NULL,"
                + "src TEXT NOT NULL,"
                + "dst TEXT NOT NULL,"
                + "ts INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_history_ts ON history(ts)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // schema version 1 only
    }

    /** Saves a translation; an identical (direction, source) pair is replaced so it moves to the top. */
    public synchronized void save(Direction dir, String source, String translation, int maxEntries) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("history", "dir = ? AND src = ?", new String[]{String.valueOf(dir.ordinal()), source});
            ContentValues v = new ContentValues();
            v.put("dir", dir.ordinal());
            v.put("src", source);
            v.put("dst", translation);
            v.put("ts", System.currentTimeMillis());
            db.insert("history", null, v);
            if (maxEntries > 0) {
                db.execSQL("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY ts DESC, id DESC LIMIT " + maxEntries + ")");
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static String likeArg(String query) {
        String q = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + q + "%";
    }

    public synchronized int count(String query) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c;
        if (query == null || query.isEmpty()) {
            c = db.rawQuery("SELECT COUNT(*) FROM history", null);
        } else {
            String a = likeArg(query);
            c = db.rawQuery("SELECT COUNT(*) FROM history WHERE src LIKE ? ESCAPE '\\' OR dst LIKE ? ESCAPE '\\'",
                    new String[]{a, a});
        }
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public synchronized List<HistoryEntry> page(String query, int limit, int offset) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c;
        String order = " ORDER BY ts DESC, id DESC LIMIT " + limit + " OFFSET " + offset;
        if (query == null || query.isEmpty()) {
            c = db.rawQuery("SELECT id, dir, src, dst, ts FROM history" + order, null);
        } else {
            String a = likeArg(query);
            c = db.rawQuery("SELECT id, dir, src, dst, ts FROM history WHERE src LIKE ? ESCAPE '\\' OR dst LIKE ? ESCAPE '\\'"
                    + order, new String[]{a, a});
        }
        List<HistoryEntry> out = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                out.add(new HistoryEntry(c.getLong(0), Direction.fromId(c.getInt(1)), c.getString(2), c.getString(3), c.getLong(4)));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public synchronized void delete(long id) {
        getWritableDatabase().delete("history", "id = ?", new String[]{String.valueOf(id)});
    }

    public synchronized void clear() {
        getWritableDatabase().delete("history", null, null);
    }
}
