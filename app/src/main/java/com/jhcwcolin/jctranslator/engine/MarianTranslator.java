package com.jhcwcolin.jctranslator.engine;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Marian / OPUS-MT translator running on ONNX Runtime. The same code also drives the
 * multilingual M2M100 / NLLB models (encoder-decoder with language tokens): the source text gets
 * the source language token in front and the decoder is forced to start with the target one.
 *
 * <p>Model files (see tools/prepare_models.py): {@code encoder.onnx}, {@code decoder.onnx}
 * (merged decoder with KV cache), {@code source.spm}, {@code target.spm}, {@code vocab.txt},
 * {@code meta.properties}.
 *
 * <p>Translation is split into {@link #encode} and {@link #decode} so that a low-memory mode can
 * run the encoder for every segment, release it, and only then load the decoder.
 */
public final class MarianTranslator implements AutoCloseable {

    /** Thrown when {@link #cancel()} was called while translating. */
    public static final class CancelledException extends RuntimeException {
        public CancelledException() {
            super("cancelled");
        }
    }

    /** Output of the encoder for one text segment. */
    public static final class Encoded {
        final int srcLen;
        float[] hidden; // [srcLen * dModel]

        Encoded(int srcLen, float[] hidden) {
            this.srcLen = srcLen;
            this.hidden = hidden;
        }

        /** Releases the encoder output after the decoder has consumed it. */
        public void release() {
            hidden = null;
        }
    }

    // Long inputs are split before reaching the engine. Capping a single piece bounds the
    // encoder output and decoder cross-attention cache on 32-bit devices.
    private static final int MAX_SOURCE_TOKENS = 256;
    private static final double LENGTH_PENALTY = 1.0;

    private final File dir;
    private final int threads;
    private final SentencePieceModel srcSpm;
    private final SentencePieceModel tgtSpm;
    /** id -> token, as one compact char array (no per-token String objects). */
    private final StringTable vocab;

    private final int unkId;
    private final int eosId;
    private final int padId;
    private final int startId;
    private final int layers;
    private final int heads;
    private final int headDim;
    private final int dModel;
    private final int maxLength;

    private OrtEnvironment env;
    private OrtSession encoderSession;
    private OrtSession decoderSession;

    // decoder I/O layout, resolved once from the session
    private String[] selfInNames;   // [layers*2] past_key_values.i.decoder.key/value
    private String[] crossInNames;  // [layers*2] past_key_values.i.encoder.key/value
    private int[] selfOutIdx;       // output indices of present.i.decoder.*
    private int[] crossOutIdx;      // output indices of present.i.encoder.*
    private boolean hasUseCacheBranch;
    private boolean hasHiddenInput;

    private volatile boolean cancelled;

    // Direct buffers reused between decoding steps / segments (freed with the decoder session).
    private FloatBuffer[] selfPool;
    private FloatBuffer[] crossPool;
    private float[] logitRow;

    /** Language tokens of multilingual models (-1 = not used). */
    private int srcLangId = -1;
    private int tgtLangId = -1;

    public MarianTranslator(File dir, int threads) throws IOException {
        this.dir = dir;
        this.threads = Math.max(1, threads);

        Properties meta = new Properties();
        try (InputStream in = new FileInputStream(new File(dir, "meta.properties"))) {
            meta.load(in);
        }
        eosId = Integer.parseInt(meta.getProperty("eos_token_id"));
        padId = Integer.parseInt(meta.getProperty("pad_token_id"));
        startId = Integer.parseInt(meta.getProperty("decoder_start_token_id"));
        layers = Integer.parseInt(meta.getProperty("decoder_layers"));
        heads = Integer.parseInt(meta.getProperty("decoder_heads"));
        headDim = Integer.parseInt(meta.getProperty("head_dim"));
        maxLength = Integer.parseInt(meta.getProperty("max_length", "512"));
        dModel = heads * headDim;

        vocab = loadVocab(new File(dir, "vocab.txt"), Integer.parseInt(meta.getProperty("vocab_size", "66000")));
        int unk = vocab.find("<unk>");
        unkId = unk >= 0 ? unk : 1;

        srcSpm = SentencePieceModel.load(new File(dir, "source.spm"));
        File tgtFile = new File(dir, "target.spm");
        // Multilingual models ship one SentencePiece model for both sides: do not parse it twice.
        tgtSpm = tgtFile.isFile() ? SentencePieceModel.load(tgtFile) : srcSpm;
    }

    /** Reads vocab.txt (one token per line, line number = id) straight into a {@link StringTable}. */
    private static StringTable loadVocab(File file, int expected) throws IOException {
        long length = file.length();
        if (length <= 0 || length > (64L << 20)) {
            throw new IOException("Bad vocabulary file: " + file);
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
        StringTable.Builder b = new StringTable.Builder(expected + 16, expected * 3);
        int start = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n') {
                int end = i > start && data[i - 1] == '\r' ? i - 1 : i;
                b.addUtf8(data, start, end);
                start = i + 1;
            }
        }
        if (start < data.length) {
            b.addUtf8(data, start, data.length);
        }
        return b.build();
    }

    /**
     * Sets the language tokens (multilingual models only; pass null for plain Marian models).
     * Takes effect for the next {@link #encode} / {@link #decode}.
     */
    public synchronized void setLanguages(String sourceToken, String targetToken) {
        srcLangId = idOf(sourceToken);
        tgtLangId = idOf(targetToken);
    }

    private int idOf(String token) {
        if (token == null) {
            return -1;
        }
        return vocab.find(token);
    }

    public void cancel() {
        cancelled = true;
    }

    public void resetCancel() {
        cancelled = false;
    }

    private void checkCancelled() {
        if (cancelled) {
            throw new CancelledException();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Sessions
    // ---------------------------------------------------------------------------------------

    /**
     * Marker file written while a session is being created. If the process dies natively in the
     * middle of that (a crash cannot be caught in Java), the marker survives and the next attempt
     * uses the most conservative settings. Content: the optimisation level that was attempted.
     */
    private File loadMarker() {
        return new File(dir, ".loading");
    }

    private OrtSession openSession(String file) throws OrtException {
        if (env == null) {
            env = OrtEnvironment.getEnvironment();
        }
        File marker = loadMarker();
        boolean safeMode = marker.exists();
        if (safeMode && readMarker(marker).equals("none")) {
            marker.delete();
            throw new OrtException("上次加载此模型时应用意外退出（内存不足或推理库异常）。请关闭其他应用后重试，"
                    + "或在设置中改用更小的模型（如 OPUS-MT 精简版）。");
        }
        writeMarker(marker, safeMode ? "none" : "basic");

        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        try {
            so.setIntraOpNumThreads(threads);
            so.setInterOpNumThreads(1);
            so.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            so.setOptimizationLevel(safeMode ? OrtSession.SessionOptions.OptLevel.NO_OPT
                    : OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            // ORT 1.22 on 32-bit ARM: the CommonSubexpressionElimination graph pass hashes scalar
            // initialisers with an 8-byte load (ldrd) from an odd address (libc++ short-string
            // storage) -> SIGBUS while the session is created. Measured on an armv7 device.
            so.addConfigEntry("optimization.disable_specified_optimizers", "CommonSubexpressionElimination");
            so.setMemoryPatternOptimization(false);
            so.setCPUArenaAllocator(false);
            so.addConfigEntry("session.intra_op.allow_spinning", "0");
            OrtSession session = env.createSession(new File(dir, file).getAbsolutePath(), so);
            marker.delete();
            return session;
        } finally {
            so.close();
        }
    }

    private static String readMarker(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[16];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeMarker(File f, String content) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException ignored) {
            // without the marker we simply lose the crash fallback
        }
    }

    private void ensureEncoder() throws OrtException {
        if (encoderSession == null) {
            encoderSession = openSession("encoder.onnx");
        }
    }

    private void ensureDecoder() throws OrtException {
        if (decoderSession != null) {
            return;
        }
        decoderSession = openSession("decoder.onnx");

        selfInNames = new String[layers * 2];
        crossInNames = new String[layers * 2];
        selfOutIdx = new int[layers * 2];
        crossOutIdx = new int[layers * 2];
        for (int l = 0; l < layers; l++) {
            selfInNames[l * 2] = "past_key_values." + l + ".decoder.key";
            selfInNames[l * 2 + 1] = "past_key_values." + l + ".decoder.value";
            crossInNames[l * 2] = "past_key_values." + l + ".encoder.key";
            crossInNames[l * 2 + 1] = "past_key_values." + l + ".encoder.value";
        }
        List<String> outs = new ArrayList<>(decoderSession.getOutputNames());
        for (int l = 0; l < layers; l++) {
            selfOutIdx[l * 2] = indexOf(outs, "present." + l + ".decoder.key");
            selfOutIdx[l * 2 + 1] = indexOf(outs, "present." + l + ".decoder.value");
            crossOutIdx[l * 2] = indexOf(outs, "present." + l + ".encoder.key");
            crossOutIdx[l * 2 + 1] = indexOf(outs, "present." + l + ".encoder.value");
        }
        if (!outs.get(0).equals("logits")) {
            throw new OrtException("Unexpected decoder outputs: " + outs);
        }
        hasUseCacheBranch = decoderSession.getInputNames().contains("use_cache_branch");
        hasHiddenInput = decoderSession.getInputNames().contains("encoder_hidden_states");
        if (!hasUseCacheBranch) {
            throw new OrtException("decoder.onnx must be the merged decoder with use_cache_branch");
        }
    }

    private static int indexOf(List<String> list, String name) throws OrtException {
        int i = list.indexOf(name);
        if (i < 0) {
            throw new OrtException("Decoder output missing: " + name);
        }
        return i;
    }

    /** Creates the encoder session now (so callers can show a "loading" state before it happens). */
    public synchronized void loadEncoder() throws OrtException {
        ensureEncoder();
    }

    public synchronized void loadDecoder() throws OrtException {
        ensureDecoder();
    }

    public synchronized boolean isEncoderLoaded() {
        return encoderSession != null;
    }

    public synchronized boolean isDecoderLoaded() {
        return decoderSession != null;
    }

    public synchronized void closeEncoder() {
        if (encoderSession != null) {
            try {
                encoderSession.close();
            } catch (OrtException ignored) {
            }
            encoderSession = null;
        }
    }

    public synchronized void closeDecoder() {
        if (decoderSession != null) {
            try {
                decoderSession.close();
            } catch (OrtException ignored) {
            }
            decoderSession = null;
        }
        selfPool = null;
        crossPool = null;
        logitRow = null;
    }

    @Override
    public synchronized void close() {
        closeEncoder();
        closeDecoder();
    }

    public synchronized boolean isLoaded() {
        return encoderSession != null || decoderSession != null;
    }

    // ---------------------------------------------------------------------------------------
    // Tokenisation
    // ---------------------------------------------------------------------------------------

    private long[] tokenizeSource(String text) {
        List<String> pieces = srcSpm.encodeToPieces(text);
        int n = Math.min(pieces.size(), MAX_SOURCE_TOKENS);
        int lead = srcLangId >= 0 ? 1 : 0;
        long[] ids = new long[lead + n + 1];
        if (lead == 1) {
            ids[0] = srcLangId;
        }
        for (int i = 0; i < n; i++) {
            int id = vocab.find(pieces.get(i));
            ids[lead + i] = id >= 0 ? id : unkId;
        }
        ids[lead + n] = eosId; // Marian expects </s> at the end of the source
        return ids;
    }

    private String detokenize(List<Integer> tokens) {
        List<String> pieces = new ArrayList<>(tokens.size());
        for (int t : tokens) {
            if (t == eosId || t == padId || t == unkId || t == tgtLangId || t < 0 || t >= vocab.size()) {
                continue;
            }
            pieces.add(vocab.get(t));
        }
        return tgtSpm.decodePieces(pieces);
    }

    // ---------------------------------------------------------------------------------------
    // Encoder
    // ---------------------------------------------------------------------------------------

    public synchronized Encoded encode(String text) throws OrtException {
        checkCancelled();
        ensureEncoder();
        long[] ids = tokenizeSource(text);
        int s = ids.length;
        long[] ones = new long[s];
        Arrays.fill(ones, 1L);
        long[] shape = {1, s};

        OnnxTensor idsT = null;
        OnnxTensor maskT = null;
        try {
            idsT = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape);
            maskT = OnnxTensor.createTensor(env, LongBuffer.wrap(ones), shape);
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", idsT);
            inputs.put("attention_mask", maskT);
            try (OrtSession.Result res = encoderSession.run(inputs)) {
                OnnxTensor h = (OnnxTensor) res.get(0);
                FloatBuffer fb = h.getFloatBuffer();
                float[] hidden = new float[s * dModel];
                fb.get(hidden);
                return new Encoded(s, hidden);
            }
        } finally {
            if (idsT != null) {
                idsT.close();
            }
            if (maskT != null) {
                maskT.close();
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Decoder: beam search with KV cache
    // ---------------------------------------------------------------------------------------

    private static final class Hyp {
        final int[] tokens;
        final double score;
        final int parent;

        Hyp(int[] tokens, double score, int parent) {
            this.tokens = tokens;
            this.score = score;
            this.parent = parent;
        }
    }

    private static final class Cand {
        final double score;
        final int beam;
        final int token;

        Cand(double score, int beam, int token) {
            this.score = score;
            this.beam = beam;
            this.token = token;
        }
    }

    private static final class Finished {
        final int[] tokens;
        final double normScore;

        Finished(int[] tokens, double normScore) {
            this.tokens = tokens;
            this.normScore = normScore;
        }
    }

    private static FloatBuffer directFloats(int n) {
        return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    /**
     * Copies batch rows of {@code src} ([bOld, blockSize] floats) into a new buffer of
     * {@code parents.length} rows: row j of the result is row parents[j] of src.
     */
    private static FloatBuffer gather(FloatBuffer[] pool, int slot, FloatBuffer src, int[] parents, int blockSize) {
        int need = parents.length * blockSize;
        FloatBuffer dst = pool[slot];
        if (dst == null || dst.capacity() < need) {
            dst = directFloats(need + (need >> 2) + 64);   // 25 % slack: the cache grows by one position per step
            pool[slot] = dst;
        }
        dst.clear();
        FloatBuffer s = src.duplicate();
        for (int j = 0; j < parents.length; j++) {
            s.limit((parents[j] + 1) * blockSize);
            s.position(parents[j] * blockSize);
            dst.put(s);
            s.limit(s.capacity());
        }
        dst.flip();
        return dst;
    }

    /**
     * Translates one encoded segment.
     *
     * @param numBeams beam width (1 = greedy-like)
     */
    public synchronized String decode(Encoded enc, int numBeams) throws OrtException {
        checkCancelled();
        ensureDecoder();
        if (enc == null || enc.hidden == null || enc.srcLen <= 0) {
            return "";
        }
        final int beams = Math.max(1, numBeams);
        final int s = enc.srcLen;
        final int maxNew = Math.min(Math.min(maxLength, 256), s * 2 + 16);
        final int kvBlock = heads * headDim;

        if (selfPool == null) {
            selfPool = new FloatBuffer[layers * 2];
            crossPool = new FloatBuffer[layers * 2];
        }
        List<OnnxTensor> toClose = new ArrayList<>();
        try {
            // ---- step 0: batch of one, no cache --------------------------------------
            OnnxTensor hidden1 = track(toClose, OnnxTensor.createTensor(env, wrapFloats(enc.hidden), new long[]{1, s, dModel}));
            OnnxTensor mask1 = track(toClose, OnnxTensor.createTensor(env, LongBuffer.wrap(ones(s)), new long[]{1, s}));
            // Multilingual models: the decoder prefix is <decoder_start> + <target language>.
            final boolean forced = tgtLangId >= 0;
            final long[] prefix = forced ? new long[]{startId, tgtLangId} : new long[]{startId};
            OnnxTensor start = track(toClose, OnnxTensor.createTensor(env, LongBuffer.wrap(prefix), new long[]{1, prefix.length}));
            OnnxTensor dummy = track(toClose, OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[kvBlock]), new long[]{1, heads, 1, headDim}));
            OnnxTensor cacheFlag = track(toClose, OnnxTensor.createTensor(env, new boolean[]{false}));

            Map<String, OnnxTensor> in0 = new HashMap<>();
            in0.put("input_ids", start);
            in0.put("encoder_attention_mask", mask1);
            if (hasHiddenInput) {
                in0.put("encoder_hidden_states", hidden1);
            }
            in0.put("use_cache_branch", cacheFlag);
            for (String n : selfInNames) {
                in0.put(n, dummy);
            }
            for (String n : crossInNames) {
                in0.put(n, dummy);
            }

            OrtSession.Result res = decoderSession.run(in0);
            int batch = 1;
            int pastLen = prefix.length - 1; // length of the self-attention cache fed into the *next* run

            List<Hyp> active = new ArrayList<>();
            active.add(new Hyp(forced ? new int[]{tgtLangId} : new int[0], 0.0, 0));
            List<Finished> finished = new ArrayList<>();

            OnnxTensor[] crossT = null;
            int crossBatch = 0;
            boolean done = false;
            FloatBuffer[] selfBuf = new FloatBuffer[layers * 2];
            int[] parents = new int[beams];

            try {
                for (int step = 0; step < maxNew; step++) {
                    checkCancelled();

                    // ---- candidates from the logits of the current batch -------------
                    OnnxTensor logitsT = (OnnxTensor) res.get(0);
                    FloatBuffer logits = logitsT.getFloatBuffer();
                    long[] lshape = logitsT.getInfo().getShape(); // [batch, positions, vocab]
                    int positions = lshape.length >= 3 ? (int) lshape[1] : 1;
                    int logitDim = lshape.length >= 3 ? (int) lshape[2] : logits.remaining() / (batch * positions);
                    int topK = beams * 2;
                    List<Cand> cands = new ArrayList<>(batch * topK);
                    if (logitRow == null || logitRow.length != logitDim) {
                        logitRow = new float[logitDim];
                    }
                    float[] row = logitRow;
                    for (int b = 0; b < batch; b++) {
                        // only the last position predicts the next token
                        logits.position((b * positions + positions - 1) * logitDim);
                        logits.get(row, 0, logitDim);
                        topKLogProbs(row, active.get(b).score, b, topK, cands);
                    }
                    Collections.sort(cands, new Comparator<Cand>() {
                        @Override
                        public int compare(Cand a, Cand c) {
                            return Double.compare(c.score, a.score);
                        }
                    });

                    List<Hyp> next = new ArrayList<>(beams);
                    double bestCandScore = cands.isEmpty() ? Double.NEGATIVE_INFINITY : cands.get(0).score;
                    int limit = Math.min(cands.size(), topK);
                    for (int r = 0; r < limit; r++) {
                        Cand c = cands.get(r);
                        Hyp parent = active.get(c.beam);
                        if (c.token == eosId) {
                            if (r >= beams) {
                                continue;
                            }
                            double norm = c.score / Math.pow(Math.max(1, parent.tokens.length), LENGTH_PENALTY);
                            addFinished(finished, new Finished(parent.tokens, norm), beams);
                        } else {
                            int[] t = Arrays.copyOf(parent.tokens, parent.tokens.length + 1);
                            t[t.length - 1] = c.token;
                            next.add(new Hyp(t, c.score, c.beam));
                        }
                        if (next.size() == beams) {
                            break;
                        }
                    }

                    if (next.isEmpty()) {
                        done = true;
                        break;
                    }
                    if (finished.size() >= beams) {
                        double worst = worstScore(finished);
                        double attainable = bestCandScore / Math.pow(Math.max(1, step), LENGTH_PENALTY);
                        if (worst >= attainable) {
                            done = true;
                            break;
                        }
                    }
                    if (step + 1 >= maxNew) {
                        active = next;
                        break;
                    }

                    // ---- build inputs for the next step ------------------------------
                    int nb = next.size();
                    if (crossT != null && nb != crossBatch) {
                        active = next; // cannot happen with a full beam; stop gracefully
                        break;
                    }
                    parents = new int[nb];
                    long[] last = new long[nb];
                    for (int j = 0; j < nb; j++) {
                        parents[j] = next.get(j).parent;
                        last[j] = next.get(j).tokens[next.get(j).tokens.length - 1];
                    }
                    int newLen = pastLen + 1;
                    int block = heads * newLen * headDim;
                    for (int i = 0; i < layers * 2; i++) {
                        OnnxTensor present = (OnnxTensor) res.get(selfOutIdx[i]);
                        selfBuf[i] = gather(selfPool, i, present.getFloatBuffer(), parents, block);
                    }
                    if (crossT == null) {
                        crossBatch = nb;
                        crossT = new OnnxTensor[layers * 2];
                        int[] zeros = new int[nb];
                        for (int i = 0; i < layers * 2; i++) {
                            OnnxTensor cp = (OnnxTensor) res.get(crossOutIdx[i]);
                            FloatBuffer tiled = gather(crossPool, i, cp.getFloatBuffer(), zeros, heads * s * headDim);
                            crossT[i] = track(toClose, OnnxTensor.createTensor(env, tiled, new long[]{nb, heads, s, headDim}));
                        }
                        // batch-wide constants for the cache branch
                        hidden1 = track(toClose, OnnxTensor.createTensor(env, tile(enc.hidden, nb), new long[]{nb, s, dModel}));
                        mask1 = track(toClose, OnnxTensor.createTensor(env, LongBuffer.wrap(ones(nb * s)), new long[]{nb, s}));
                        cacheFlag = track(toClose, OnnxTensor.createTensor(env, new boolean[]{true}));
                    }
                    res.close();
                    res = null;

                    batch = nb;
                    pastLen = newLen;
                    active = next;

                    OnnxTensor lastT = OnnxTensor.createTensor(env, LongBuffer.wrap(last), new long[]{nb, 1});
                    List<OnnxTensor> stepTensors = new ArrayList<>();
                    stepTensors.add(lastT);
                    try {
                        Map<String, OnnxTensor> in = new HashMap<>();
                        in.put("input_ids", lastT);
                        in.put("encoder_attention_mask", mask1);
                        if (hasHiddenInput) {
                            in.put("encoder_hidden_states", hidden1);
                        }
                        in.put("use_cache_branch", cacheFlag);
                        for (int i = 0; i < layers * 2; i++) {
                            OnnxTensor st = OnnxTensor.createTensor(env, selfBuf[i], new long[]{nb, heads, pastLen, headDim});
                            stepTensors.add(st);
                            in.put(selfInNames[i], st);
                            in.put(crossInNames[i], crossT[i]);
                        }
                        res = decoderSession.run(in);
                    } finally {
                        for (OnnxTensor t : stepTensors) {
                            t.close();
                        }
                    }
                }
            } finally {
                if (res != null) {
                    res.close();
                }
            }

            // ---- finalise: remaining active beams compete with finished ones -----------
            for (Hyp h : active) {
                if (!done && h.tokens.length > 0) {
                    double norm = h.score / Math.pow(h.tokens.length, LENGTH_PENALTY);
                    addFinished(finished, new Finished(h.tokens, norm), beams);
                }
            }
            Finished best = null;
            for (Finished f : finished) {
                if (best == null || f.normScore > best.normScore) {
                    best = f;
                }
            }
            if (best == null) {
                return "";
            }
            List<Integer> out = new ArrayList<>(best.tokens.length);
            for (int t : best.tokens) {
                out.add(t);
            }
            return detokenize(out);
        } finally {
            for (OnnxTensor t : toClose) {
                t.close();
            }
        }
    }

    private static OnnxTensor track(List<OnnxTensor> list, OnnxTensor t) {
        list.add(t);
        return t;
    }

    private static long[] ones(int n) {
        long[] a = new long[n];
        Arrays.fill(a, 1L);
        return a;
    }

    private static FloatBuffer wrapFloats(float[] a) {
        FloatBuffer fb = directFloats(a.length);
        fb.put(a);
        fb.flip();
        return fb;
    }

    private static FloatBuffer tile(float[] a, int times) {
        FloatBuffer fb = directFloats(a.length * times);
        for (int i = 0; i < times; i++) {
            fb.put(a);
        }
        fb.flip();
        return fb;
    }

    private static void addFinished(List<Finished> list, Finished f, int beams) {
        list.add(f);
        if (list.size() > beams) {
            int worstIdx = 0;
            for (int i = 1; i < list.size(); i++) {
                if (list.get(i).normScore < list.get(worstIdx).normScore) {
                    worstIdx = i;
                }
            }
            list.remove(worstIdx);
        }
    }

    private static double worstScore(List<Finished> list) {
        double w = Double.POSITIVE_INFINITY;
        for (Finished f : list) {
            w = Math.min(w, f.normScore);
        }
        return w;
    }

    /** Adds the {@code k} most probable tokens of one beam (log-softmax applied) to {@code out}. */
    private void topKLogProbs(float[] logits, double baseScore, int beam, int k, List<Cand> out) {
        float max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < logits.length; i++) {
            if (i != padId && logits[i] > max) {
                max = logits[i];
            }
        }
        double sum = 0;
        for (int i = 0; i < logits.length; i++) {
            if (i != padId) {
                sum += Math.exp(logits[i] - max);
            }
        }
        double logZ = max + Math.log(sum);

        int[] topIdx = new int[k];
        float[] topVal = new float[k];
        Arrays.fill(topIdx, -1);
        Arrays.fill(topVal, Float.NEGATIVE_INFINITY);
        int filled = 0;
        float minVal = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < logits.length; i++) {
            if (i == padId) {
                continue;
            }
            float v = logits[i];
            if (filled == k && v <= minVal) {
                continue;
            }
            // insertion into the descending top-k arrays
            int pos = Math.min(filled, k - 1);
            if (filled < k) {
                filled++;
            }
            while (pos > 0 && topVal[pos - 1] < v) {
                topVal[pos] = topVal[pos - 1];
                topIdx[pos] = topIdx[pos - 1];
                pos--;
            }
            topVal[pos] = v;
            topIdx[pos] = i;
            if (filled == k) {
                minVal = topVal[k - 1];
            }
        }
        for (int j = 0; j < filled; j++) {
            out.add(new Cand(baseScore + (topVal[j] - logZ), beam, topIdx[j]));
        }
    }
}
