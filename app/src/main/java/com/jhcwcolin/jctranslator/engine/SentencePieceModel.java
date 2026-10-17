package com.jhcwcolin.jctranslator.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal pure-Java SentencePiece (unigram) implementation.
 *
 * <p>It reads a standard {@code .spm} model file and reproduces the behaviour of the C++ library
 * for the parts the Marian/OPUS-MT models need: the precompiled normalisation map (nmt_nfkc),
 * whitespace handling and BPE / unigram segmentation.
 */
public final class SentencePieceModel {

    private static final String SPACE_SYMBOL = "▁";
    private static final byte[] SPACE_SYMBOL_UTF8 = SPACE_SYMBOL.getBytes(StandardCharsets.UTF_8);
    private static final float UNK_PENALTY = 10.0f;

    // SentencePiece.Type values
    private static final int TYPE_NORMAL = 1;
    private static final int TYPE_UNKNOWN = 2;
    private static final int TYPE_CONTROL = 3;
    private static final int TYPE_USER_DEFINED = 4;
    private static final int TYPE_UNUSED = 5;

    // Pieces and their scores in primitive arrays (about 1.4 MB for a 65k-piece model).
    private final StringTable.Builder pieceBuilder = new StringTable.Builder(70000, 200000);
    private float[] scoreBuf = new float[70000];
    private StringTable pieces;
    private float[] scores;
    private int maxPieceChars = 1;
    private float minScore = Float.MAX_VALUE;

    /** true = BPE (merge ranks), false = unigram (Viterbi). */
    private boolean bpe = false;

    private boolean addDummyPrefix = true;
    private boolean removeExtraWhitespaces = true;
    private boolean escapeWhitespaces = true;

    /** Double-array trie units of the normalisation map (may be null = identity). */
    private int[] trie;
    /** NUL separated replacement strings of the normalisation map. */
    private byte[] normalizedBlob;

    private SentencePieceModel() {
    }

    // ---------------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------------

    /** Loads a model file; the file is read into a single exactly-sized array. */
    public static SentencePieceModel load(File file) throws IOException {
        long length = file.length();
        if (length <= 0 || length > (64L << 20)) {
            throw new IOException("Bad SentencePiece model size: " + file);
        }
        byte[] data = new byte[(int) length];
        try (FileInputStream in = new FileInputStream(file)) {
            int off = 0;
            while (off < data.length) {
                int r = in.read(data, off, data.length - off);
                if (r < 0) {
                    throw new IOException("Unexpected end of " + file);
                }
                off += r;
            }
        }
        return parse(data);
    }

    public static SentencePieceModel load(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 21);
        byte[] buf = new byte[1 << 16];
        int r;
        while ((r = in.read(buf)) > 0) {
            bos.write(buf, 0, r);
        }
        return parse(bos.toByteArray());
    }

    private static SentencePieceModel parse(byte[] data) throws IOException {
        SentencePieceModel m = new SentencePieceModel();
        Reader top = new Reader(data, 0, data.length);
        while (top.hasMore()) {
            int tag = (int) top.varint();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (wire == 2) {
                int len = (int) top.varint();
                int start = top.pos;
                if (field == 1) {
                    m.parsePiece(new Reader(data, start, start + len));
                } else if (field == 2) {
                    m.parseTrainerSpec(new Reader(data, start, start + len));
                } else if (field == 3) {
                    m.parseNormalizerSpec(new Reader(data, start, start + len));
                }
                top.pos = start + len;
            } else {
                top.skip(wire);
            }
        }
        if (m.pieceBuilder.size() == 0) {
            throw new IOException("SentencePiece model has no pieces");
        }
        m.pieces = m.pieceBuilder.build();
        m.scores = java.util.Arrays.copyOf(m.scoreBuf, m.pieces.size());
        m.scoreBuf = null;
        m.maxPieceChars = Math.max(1, m.pieces.maxLength());
        return m;
    }

    private void parsePiece(Reader r) {
        int pieceFrom = -1;
        int pieceTo = -1;
        float score = 0f;
        int type = TYPE_NORMAL;
        while (r.hasMore()) {
            int tag = (int) r.varint();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 2) {
                int len = (int) r.varint();
                pieceFrom = r.pos;
                pieceTo = r.pos + len;
                r.pos += len;
            } else if (field == 2 && wire == 5) {
                int bits = (r.b[r.pos] & 0xff) | ((r.b[r.pos + 1] & 0xff) << 8)
                        | ((r.b[r.pos + 2] & 0xff) << 16) | ((r.b[r.pos + 3] & 0xff) << 24);
                score = Float.intBitsToFloat(bits);
                r.pos += 4;
            } else if (field == 3 && wire == 0) {
                type = (int) r.varint();
            } else {
                r.skip(wire);
            }
        }
        if (pieceFrom < 0 || pieceTo <= pieceFrom) {
            return;
        }
        if (type == TYPE_UNKNOWN || type == TYPE_CONTROL || type == TYPE_UNUSED) {
            return; // never produced by segmentation
        }
        int index = pieceBuilder.addUtf8(r.b, pieceFrom, pieceTo);
        if (index >= scoreBuf.length) {
            scoreBuf = java.util.Arrays.copyOf(scoreBuf, scoreBuf.length + (scoreBuf.length >> 1) + 16);
        }
        scoreBuf[index] = score;
        if (type == TYPE_NORMAL && score < minScore) {
            minScore = score;
        }
    }

    private void parseTrainerSpec(Reader r) throws IOException {
        while (r.hasMore()) {
            int tag = (int) r.varint();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 3 && wire == 0) { // model_type
                long type = r.varint();
                if (type != 1 && type != 2) {
                    throw new IOException("Only unigram and BPE SentencePiece models are supported (model_type=" + type + ")");
                }
                bpe = type == 2;
            } else {
                r.skip(wire);
            }
        }
    }

    private void parseNormalizerSpec(Reader r) {
        while (r.hasMore()) {
            int tag = (int) r.varint();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 2 && wire == 2) {
                int len = (int) r.varint();
                decodeCharsMap(r.b, r.pos, len);
                r.pos += len;
            } else if (field == 3 && wire == 0) {
                addDummyPrefix = r.varint() != 0;
            } else if (field == 4 && wire == 0) {
                removeExtraWhitespaces = r.varint() != 0;
            } else if (field == 5 && wire == 0) {
                escapeWhitespaces = r.varint() != 0;
            } else {
                r.skip(wire);
            }
        }
    }

    private void decodeCharsMap(byte[] b, int off, int len) {
        if (len < 4) {
            return;
        }
        int trieSize = u32(b, off);
        if (trieSize < 4 || 4 + trieSize > len) {
            return;
        }
        int units = trieSize / 4;
        trie = new int[units];
        for (int i = 0; i < units; i++) {
            trie[i] = u32(b, off + 4 + i * 4);
        }
        int blobStart = off + 4 + trieSize;
        normalizedBlob = new byte[off + len - blobStart];
        System.arraycopy(b, blobStart, normalizedBlob, 0, normalizedBlob.length);
    }

    private static int u32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }

    private static final class Reader {
        final byte[] b;
        int pos;
        final int end;

        Reader(byte[] b, int pos, int end) {
            this.b = b;
            this.pos = pos;
            this.end = end;
        }

        boolean hasMore() {
            return pos < end;
        }

        long varint() {
            long result = 0;
            int shift = 0;
            while (pos < end) {
                int x = b[pos++] & 0xff;
                result |= (long) (x & 0x7f) << shift;
                if ((x & 0x80) == 0) {
                    return result;
                }
                shift += 7;
            }
            return result;
        }

        void skip(int wire) {
            switch (wire) {
                case 0:
                    varint();
                    break;
                case 1:
                    pos += 8;
                    break;
                case 2:
                    int len = (int) varint();
                    pos += len;
                    break;
                case 5:
                    pos += 4;
                    break;
                default:
                    pos = end; // unknown wire type: abandon this message
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Normalisation (port of sentencepiece::normalizer::Normalizer)
    // ---------------------------------------------------------------------------------------

    private static final class Match {
        byte[] arr;
        int off;
        int len;
        int consumed;
    }

    /** Longest-prefix lookup in the precompiled charsmap (darts-clone double array). */
    private void normalizePrefix(byte[] in, int pos, Match m) {
        int longestLen = 0;
        int longestVal = 0;
        if (trie != null) {
            int nodePos = 0;
            long unit = trie[0] & 0xffffffffL;
            nodePos ^= (int) offset(unit);
            for (int i = pos; i < in.length; i++) {
                int c = in[i] & 0xff;
                nodePos ^= c;
                if (nodePos < 0 || nodePos >= trie.length) {
                    break;
                }
                unit = trie[nodePos] & 0xffffffffL;
                if ((unit & (0x80000000L | 0xffL)) != c) {
                    break;
                }
                nodePos ^= (int) offset(unit);
                if ((unit & 0x100L) != 0) { // has_leaf
                    if (nodePos >= 0 && nodePos < trie.length) {
                        int length = i - pos + 1;
                        if (length > longestLen) {
                            longestLen = length;
                            longestVal = trie[nodePos] & 0x7fffffff;
                        }
                    }
                }
            }
        }
        if (longestLen == 0) {
            int lead = in[pos] & 0xff;
            int len = lead < 0x80 ? 1 : lead < 0xe0 ? 2 : lead < 0xf0 ? 3 : 4;
            len = Math.min(len, in.length - pos);
            m.arr = in;
            m.off = pos;
            m.len = len;
            m.consumed = len;
        } else {
            int end = longestVal;
            while (end < normalizedBlob.length && normalizedBlob[end] != 0) {
                end++;
            }
            m.arr = normalizedBlob;
            m.off = longestVal;
            m.len = end - longestVal;
            m.consumed = longestLen;
        }
    }

    private static long offset(long unit) {
        return (unit >>> 10) << ((unit & 0x200L) >>> 6);
    }

    private void addWhitespace(ByteArrayOutputStream out) {
        if (escapeWhitespaces) {
            out.write(SPACE_SYMBOL_UTF8, 0, SPACE_SYMBOL_UTF8.length);
        } else {
            out.write(' ');
        }
    }

    String normalize(String text) {
        byte[] in = text.getBytes(StandardCharsets.UTF_8);
        int n = in.length;
        int pos = 0;
        Match m = new Match();

        if (removeExtraWhitespaces) {
            while (pos < n) {
                normalizePrefix(in, pos, m);
                if (!(m.len == 1 && m.arr[m.off] == ' ')) {
                    break;
                }
                pos += m.consumed;
            }
        }
        if (pos >= n) {
            return "";
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(n * 3);
        if (addDummyPrefix) {
            addWhitespace(out);
        }
        boolean isPrevSpace = removeExtraWhitespaces;
        while (pos < n) {
            normalizePrefix(in, pos, m);
            byte[] arr = m.arr;
            int off = m.off;
            int len = m.len;
            while (isPrevSpace && len > 0 && arr[off] == ' ') {
                off++;
                len--;
            }
            if (len > 0) {
                for (int i = 0; i < len; i++) {
                    byte c = arr[off + i];
                    if (escapeWhitespaces && c == ' ') {
                        addWhitespace(out);
                    } else {
                        out.write(c);
                    }
                }
                isPrevSpace = arr[off + len - 1] == ' ';
            }
            pos += m.consumed;
            if (!removeExtraWhitespaces) {
                isPrevSpace = false;
            }
        }

        byte[] res = out.toByteArray();
        int end = res.length;
        if (removeExtraWhitespaces) {
            if (escapeWhitespaces) {
                while (end >= SPACE_SYMBOL_UTF8.length && endsWithSpaceSymbol(res, end)) {
                    end -= SPACE_SYMBOL_UTF8.length;
                }
            } else {
                while (end > 0 && res[end - 1] == ' ') {
                    end--;
                }
            }
        }
        return new String(res, 0, end, StandardCharsets.UTF_8);
    }

    private static boolean endsWithSpaceSymbol(byte[] b, int end) {
        int k = SPACE_SYMBOL_UTF8.length;
        for (int i = 0; i < k; i++) {
            if (b[end - k + i] != SPACE_SYMBOL_UTF8[i]) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // Encoding / decoding
    // ---------------------------------------------------------------------------------------

    /** Segments {@code text} into SentencePiece pieces (the same strings as {@code sp.encode(text, out_type=str)}). */
    public List<String> encodeToPieces(String text) {
        String s = normalize(text);
        if (s.isEmpty()) {
            return new ArrayList<>();
        }
        return bpe ? encodeBpe(s) : encodeUnigram(s);
    }

    // ---- BPE (port of sentencepiece::bpe::Model::Encode) ----

    private static final class SymbolPair {
        final int left;
        final int right;
        final float score;
        final int size;

        SymbolPair(int left, int right, float score, int size) {
            this.left = left;
            this.right = right;
            this.score = score;
            this.size = size;
        }
    }

    private void maybeAddPair(java.util.PriorityQueue<SymbolPair> agenda, String[] piece, int left, int right, char[] scratch) {
        if (left < 0 || right < 0) {
            return;
        }
        int ll = piece[left].length();
        int rl = piece[right].length();
        if (ll + rl > maxPieceChars) {
            return;
        }
        piece[left].getChars(0, ll, scratch, 0);
        piece[right].getChars(0, rl, scratch, ll);
        int idx = pieces.find(scratch, 0, ll + rl);
        if (idx < 0) {
            return;
        }
        agenda.add(new SymbolPair(left, right, scores[idx], ll + rl));
    }

    private List<String> encodeBpe(String s) {
        int n = s.codePointCount(0, s.length());
        String[] piece = new String[n];
        int[] prev = new int[n];
        int[] next = new int[n];
        int off = 0;
        for (int i = 0; i < n; i++) {
            int cp = s.codePointAt(off);
            int len = Character.charCount(cp);
            piece[i] = s.substring(off, off + len);
            off += len;
            prev[i] = i - 1;
            next[i] = i + 1 < n ? i + 1 : -1;
        }
        java.util.PriorityQueue<SymbolPair> agenda = new java.util.PriorityQueue<>(64, new java.util.Comparator<SymbolPair>() {
            @Override
            public int compare(SymbolPair a, SymbolPair b) {
                if (a.score != b.score) {
                    return Float.compare(b.score, a.score); // higher score first
                }
                return Integer.compare(a.left, b.left);      // then leftmost
            }
        });
        char[] scratch = new char[2 * maxPieceChars + 2];
        for (int i = 1; i < n; i++) {
            maybeAddPair(agenda, piece, i - 1, i, scratch);
        }
        while (!agenda.isEmpty()) {
            SymbolPair top = agenda.poll();
            String l = piece[top.left];
            String r = piece[top.right];
            if (l.isEmpty() || r.isEmpty() || l.length() + r.length() != top.size) {
                continue;
            }
            piece[top.left] = l + r;
            next[top.left] = next[top.right];
            if (next[top.right] >= 0) {
                prev[next[top.right]] = top.left;
            }
            piece[top.right] = "";
            maybeAddPair(agenda, piece, prev[top.left], top.left, scratch);
            maybeAddPair(agenda, piece, top.left, next[top.left], scratch);
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i != -1; i = next[i]) {
            result.add(piece[i]);
        }
        return result;
    }

    // ---- unigram (Viterbi) ----

    private List<String> encodeUnigram(String s) {
        List<String> result = new ArrayList<>();
        int n = s.length();
        char[] sc = s.toCharArray();
        float unkScore = minScore - UNK_PENALTY;
        float[] best = new float[n + 1];
        int[] startsAt = new int[n + 1];
        java.util.Arrays.fill(startsAt, -1);

        int i = 0;
        while (i < n) {
            int cpLen = Character.charCount(s.codePointAt(i));
            float base = best[i];
            boolean hasSingle = false;
            int end = i;
            while (end < n) {
                end += Character.charCount(s.codePointAt(end));
                if (end - i > maxPieceChars) {
                    break;
                }
                int idx = pieces.find(sc, i, end);
                if (idx >= 0) {
                    float cand = base + scores[idx];
                    if (startsAt[end] == -1 || cand > best[end]) {
                        best[end] = cand;
                        startsAt[end] = i;
                    }
                    if (end - i == cpLen) {
                        hasSingle = true;
                    }
                }
            }
            if (!hasSingle) {
                int e = i + cpLen;
                float cand = base + unkScore;
                if (startsAt[e] == -1 || cand > best[e]) {
                    best[e] = cand;
                    startsAt[e] = i;
                }
            }
            i += cpLen;
        }

        int e = n;
        while (e > 0) {
            int st = startsAt[e];
            if (st < 0) {
                break; // cannot happen: every boundary is reachable
            }
            result.add(s.substring(st, e));
            e = st;
        }
        java.util.Collections.reverse(result);
        return result;
    }

    /** Joins pieces back to text (equivalent of {@code sp.decode_pieces}). */
    public String decodePieces(List<String> pieces) {
        StringBuilder sb = new StringBuilder();
        for (String p : pieces) {
            sb.append(p);
        }
        String s = sb.toString().replace(SPACE_SYMBOL, " ");
        if (addDummyPrefix && s.startsWith(" ")) {
            s = s.substring(1);
        }
        return s;
    }
}
