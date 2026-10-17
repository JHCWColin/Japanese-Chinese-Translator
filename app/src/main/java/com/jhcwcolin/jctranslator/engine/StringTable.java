package com.jhcwcolin.jctranslator.engine;

import java.util.Arrays;

/**
 * Compact, immutable string -> index table. The strings live in one {@code char[]} and are found
 * through an open-addressing hash of primitive ints, so a 65 000-entry vocabulary costs about
 * 1.4 MB instead of the ~6 MB a {@code HashMap<String, Integer>} would need, and substrings of a
 * larger text can be looked up without allocating.
 *
 * <p>The first occurrence of a duplicated string wins for {@link #find}; {@link #get} still
 * returns every entry by index.
 */
final class StringTable {

    /** Collects strings (given as UTF-8 bytes or Strings) and then builds the table. */
    static final class Builder {
        private char[] chars;
        private int used;
        private int[] offs;
        private int count;

        Builder(int expectedCount, int expectedChars) {
            chars = new char[Math.max(16, expectedChars)];
            offs = new int[Math.max(16, expectedCount) + 1];
        }

        int size() {
            return count;
        }

        private void ensureChars(int extra) {
            if (used + extra > chars.length) {
                chars = Arrays.copyOf(chars, Math.max(chars.length + (chars.length >> 1), used + extra));
            }
        }

        private void finishEntry() {
            if (count + 2 > offs.length) {
                offs = Arrays.copyOf(offs, offs.length + (offs.length >> 1) + 16);
            }
            count++;
            offs[count] = used;
        }

        /** Appends the UTF-8 bytes {@code b[from, to)} as one entry and returns its index. */
        int addUtf8(byte[] b, int from, int to) {
            int i = from;
            while (i < to) {
                int c = b[i++] & 0xff;
                if (c < 0x80) {
                    ensureChars(1);
                    chars[used++] = (char) c;
                } else if (c < 0xe0 && i < to) {
                    ensureChars(1);
                    chars[used++] = (char) (((c & 0x1f) << 6) | (b[i++] & 0x3f));
                } else if (c < 0xf0 && i + 1 < to) {
                    ensureChars(1);
                    chars[used++] = (char) (((c & 0x0f) << 12) | ((b[i] & 0x3f) << 6) | (b[i + 1] & 0x3f));
                    i += 2;
                } else if (i + 2 < to) {
                    int cp = ((c & 0x07) << 18) | ((b[i] & 0x3f) << 12) | ((b[i + 1] & 0x3f) << 6) | (b[i + 2] & 0x3f);
                    i += 3;
                    ensureChars(2);
                    cp -= 0x10000;
                    chars[used++] = (char) (0xd800 + (cp >> 10));
                    chars[used++] = (char) (0xdc00 + (cp & 0x3ff));
                } else {
                    ensureChars(1);
                    chars[used++] = '�';
                    break;
                }
            }
            int index = count;
            finishEntry();
            return index;
        }

        int add(String s) {
            ensureChars(s.length());
            s.getChars(0, s.length(), chars, used);
            used += s.length();
            int index = count;
            finishEntry();
            return index;
        }

        StringTable build() {
            return new StringTable(Arrays.copyOf(chars, used), Arrays.copyOf(offs, count + 1), count);
        }
    }

    private final char[] chars;
    private final int[] offs;
    private final int count;
    private final int[] slots;   // entry index + 1, 0 = empty
    private final int mask;
    private int maxLen;

    private StringTable(char[] chars, int[] offs, int count) {
        this.chars = chars;
        this.offs = offs;
        this.count = count;
        int cap = 16;
        while (cap < count * 2) {
            cap <<= 1;
        }
        slots = new int[cap];
        mask = cap - 1;
        for (int i = 0; i < count; i++) {
            int len = offs[i + 1] - offs[i];
            if (len > maxLen) {
                maxLen = len;
            }
            int h = hash(chars, offs[i], offs[i + 1]) & mask;
            boolean duplicate = false;
            while (slots[h] != 0) {
                int j = slots[h] - 1;
                if (equal(j, chars, offs[i], len)) {
                    duplicate = true;   // keep the first occurrence
                    break;
                }
                h = (h + 1) & mask;
            }
            if (!duplicate) {
                slots[h] = i + 1;
            }
        }
    }

    int size() {
        return count;
    }

    /** Length (in chars) of the longest entry. */
    int maxLength() {
        return maxLen;
    }

    String get(int index) {
        return new String(chars, offs[index], offs[index + 1] - offs[index]);
    }

    int lengthOf(int index) {
        return offs[index + 1] - offs[index];
    }

    static int hash(char[] c, int from, int to) {
        int h = 0;
        for (int i = from; i < to; i++) {
            h = 31 * h + c[i];
        }
        return h ^ (h >>> 15);
    }

    private boolean equal(int index, char[] other, int from, int len) {
        int o = offs[index];
        if (offs[index + 1] - o != len) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (chars[o + i] != other[from + i]) {
                return false;
            }
        }
        return true;
    }

    /** Index of the entry equal to {@code other[from, to)}, or -1. */
    int find(char[] other, int from, int to) {
        int len = to - from;
        if (len <= 0 || len > maxLen) {
            return -1;
        }
        int h = hash(other, from, to) & mask;
        int s;
        while ((s = slots[h]) != 0) {
            if (equal(s - 1, other, from, len)) {
                return s - 1;
            }
            h = (h + 1) & mask;
        }
        return -1;
    }

    /** Index of the entry equal to {@code key}, or -1. */
    int find(String key) {
        int len = key.length();
        if (len == 0 || len > maxLen) {
            return -1;
        }
        char[] tmp = new char[len];
        key.getChars(0, len, tmp, 0);
        return find(tmp, 0, len);
    }
}
