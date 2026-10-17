package com.jhcwcolin.jctranslator;

/** Translation direction. {@link #dir} is the model sub-directory name under assets/models. */
public enum Direction {
    ZH_JA("zh-ja", "中文", "日语"),
    JA_ZH("ja-zh", "日语", "中文");

    public final String dir;
    public final String from;
    public final String to;

    Direction(String dir, String from, String to) {
        this.dir = dir;
        this.from = from;
        this.to = to;
    }

    public String label() {
        return from + " → " + to;
    }

    public Direction flip() {
        return this == ZH_JA ? JA_ZH : ZH_JA;
    }

    public boolean targetIsJapanese() {
        return this == ZH_JA;
    }

    public static Direction fromId(int id) {
        Direction[] all = values();
        return id >= 0 && id < all.length ? all[id] : ZH_JA;
    }
}
