package com.jhcwcolin.jctranslator.engine;

/** Helpers for Chinese / Japanese text: script tests and cosmetic clean-up of model output. */
public final class CjkText {

    private CjkText() {
    }

    public static boolean isKana(int c) {
        return (c >= 0x3040 && c <= 0x30ff) || (c >= 0x31f0 && c <= 0x31ff) || (c >= 0xff66 && c <= 0xff9f);
    }

    public static boolean isHan(int c) {
        return (c >= 0x4e00 && c <= 0x9fff) || (c >= 0x3400 && c <= 0x4dbf) || (c >= 0xf900 && c <= 0xfaff)
                || (c >= 0x20000 && c <= 0x2ebef);
    }

    /** Han, kana, CJK punctuation and full-width forms. */
    public static boolean isCjk(int c) {
        return isHan(c) || isKana(c) || (c >= 0x3000 && c <= 0x303f) || (c >= 0xff00 && c <= 0xffef)
                || c == 0x30fc;
    }

    /**
     * Cleans up decoded model output: removes spaces next to CJK characters and converts the
     * ASCII punctuation the model emits (it was trained on NFKC-normalised text) back to the
     * punctuation normally used next to CJK text.
     */
    public static String tidy(String s, boolean japaneseTarget) {
        int n = s.length();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            char prev = prevNonSpace(sb);
            char next = nextNonSpace(s, i + 1);
            if (c == ' ') {
                if (sb.length() == 0 || next == 0) {
                    continue;
                }
                if (isCjk(prev) || isCjk(next)) {
                    continue; // no spaces around CJK characters
                }
                sb.append(c);
                continue;
            }
            if (isCjk(prev) || isCjk(next)) {
                switch (c) {
                    case ',':
                        c = japaneseTarget ? '、' : '，';
                        break;
                    case '?':
                        c = '？';
                        break;
                    case '!':
                        c = '！';
                        break;
                    case ';':
                        c = '；';
                        break;
                    case ':':
                        c = '：';
                        break;
                    case '(':
                        c = '（';
                        break;
                    case ')':
                        c = '）';
                        break;
                    case '.':
                        if (isCjk(prev) && (next == 0 || isCjk(next))) {
                            c = '。';
                        }
                        break;
                    default:
                        break;
                }
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    private static char nextNonSpace(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (s.charAt(i) != ' ') {
                return s.charAt(i);
            }
        }
        return 0;
    }

    private static char prevNonSpace(StringBuilder sb) {
        for (int i = sb.length() - 1; i >= 0; i--) {
            if (sb.charAt(i) != ' ') {
                return sb.charAt(i);
            }
        }
        return 0;
    }

    /** Joins translated segments of one line. */
    public static String join(Iterable<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                char a = sb.charAt(sb.length() - 1);
                char b = p.charAt(0);
                if (Character.isLetterOrDigit(a) && Character.isLetterOrDigit(b) && !isCjk(a) && !isCjk(b)) {
                    sb.append(' ');
                }
            }
            sb.append(p);
        }
        return sb.toString();
    }
}
