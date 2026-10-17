package com.jhcwcolin.jctranslator.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits input text into pieces the model can translate reliably: Marian models are trained on
 * sentences, so long paragraphs are cut at sentence ends and, if still too long, at clauses.
 */
public final class TextSegmenter {

    private TextSegmenter() {
    }

    /**
     * @return one list per input line (blank lines yield an empty list), each holding the
     * segments of that line in order.
     */
    public static List<List<String>> split(String text, int maxChars) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        List<List<String>> result = new ArrayList<>(lines.length);
        for (String line : lines) {
            result.add(splitLine(line.trim(), maxChars));
        }
        return result;
    }

    public static int countSegments(List<List<String>> lines) {
        int n = 0;
        for (List<String> l : lines) {
            n += l.size();
        }
        return n;
    }

    private static List<String> splitLine(String line, int maxChars) {
        List<String> out = new ArrayList<>();
        if (line.isEmpty()) {
            return out;
        }
        List<String> sentences = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int n = line.length();
        for (int i = 0; i < n; i++) {
            cur.append(line.charAt(i));
            if (isTerminator(line, i)) {
                while (i + 1 < n && (isTerminatorChar(line.charAt(i + 1)) || isCloser(line.charAt(i + 1)))) {
                    cur.append(line.charAt(++i));
                }
                sentences.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            sentences.add(cur.toString());
        }
        for (String s : sentences) {
            String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.length() <= maxChars) {
                out.add(t);
            } else {
                splitLong(t, maxChars, out);
            }
        }
        return out;
    }

    private static boolean isTerminatorChar(char c) {
        switch (c) {
            case '。':
            case '！':
            case '？':
            case '!':
            case '?':
            case '…':
            case '‥':
            case '；':
            case ';':
                return true;
            default:
                return false;
        }
    }

    private static boolean isTerminator(String s, int i) {
        char c = s.charAt(i);
        if (isTerminatorChar(c)) {
            return true;
        }
        if (c == '.' || c == '．') {
            if (i > 0 && Character.isDigit(s.charAt(i - 1))) {
                return false; // decimal number / numbering
            }
            if (i + 1 >= s.length()) {
                return true;
            }
            char next = s.charAt(i + 1);
            return Character.isWhitespace(next) || CjkText.isCjk(next);
        }
        return false;
    }

    private static boolean isCloser(char c) {
        switch (c) {
            case '」':
            case '』':
            case '）':
            case ')':
            case '"':
            case '”':
            case '’':
            case '】':
            case '》':
            case '〉':
                return true;
            default:
                return false;
        }
    }

    private static boolean isClauseBreak(char c) {
        return c == '，' || c == '、' || c == ',' || c == '：' || c == ':' || c == '　';
    }

    private static void splitLong(String s, int maxChars, List<String> out) {
        StringBuilder cur = new StringBuilder();
        int start = 0;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            if (isClauseBreak(s.charAt(i)) || i == n - 1) {
                String clause = s.substring(start, i + 1);
                start = i + 1;
                if (clause.length() > maxChars) {
                    flush(cur, out);
                    hardSplit(clause, maxChars, out);
                    continue;
                }
                if (cur.length() + clause.length() > maxChars) {
                    flush(cur, out);
                }
                cur.append(clause);
            }
        }
        flush(cur, out);
    }

    private static void flush(StringBuilder cur, List<String> out) {
        String t = cur.toString().trim();
        if (!t.isEmpty()) {
            out.add(t);
        }
        cur.setLength(0);
    }

    private static void hardSplit(String s, int maxChars, List<String> out) {
        int i = 0;
        while (i < s.length()) {
            int end = Math.min(s.length(), i + maxChars);
            if (end < s.length() && Character.isHighSurrogate(s.charAt(end - 1))) {
                end--;
            }
            String t = s.substring(i, end).trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
            i = end;
        }
    }
}
