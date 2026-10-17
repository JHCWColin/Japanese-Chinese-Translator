package com.jhcwcolin.jctranslator.engine;

/** Very small heuristic language guesser for Chinese vs Japanese input. */
public final class LangDetect {

    public static final int UNKNOWN = 0;
    public static final int CHINESE = 1;
    public static final int JAPANESE = 2;

    /** Characters that exist only in simplified Chinese (not used in Japanese kanji). */
    private static final String SIMPLIFIED_ONLY =
            "这个们说为么时对没还过来会国学门马见长发开关从东车电请问吗谢买卖读写听语认识让该应间现动业产经济样种体里边钱铁银鱼鸟龙飞风云儿爱习书乐医药区办务劳备报场总网线红级组结给统续绝维综编缘纪约练纳纸终细织绍绘继";

    private LangDetect() {
    }

    public static int guess(String text) {
        int kana = 0;
        int han = 0;
        int simplified = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (CjkText.isKana(cp)) {
                kana++;
            } else if (CjkText.isHan(cp)) {
                han++;
                if (cp < 0x10000 && SIMPLIFIED_ONLY.indexOf((char) cp) >= 0) {
                    simplified++;
                }
            }
        }
        if (kana > 0) {
            return JAPANESE;
        }
        if (han > 0 && simplified > 0) {
            return CHINESE;
        }
        return UNKNOWN; // kanji-only text is ambiguous
    }
}
