package com.jhcwcolin.jctranslator.data;

import com.jhcwcolin.jctranslator.Direction;

/** One saved translation: both the source text and the finished translation are stored. */
public final class HistoryEntry {
    public final long id;
    public final Direction direction;
    public final String source;
    public final String translation;
    public final long time;

    public HistoryEntry(long id, Direction direction, String source, String translation, long time) {
        this.id = id;
        this.direction = direction;
        this.source = source;
        this.translation = translation;
        this.time = time;
    }
}
