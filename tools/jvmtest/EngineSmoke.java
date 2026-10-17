import com.jhcwcolin.jctranslator.engine.CjkText;
import com.jhcwcolin.jctranslator.engine.MarianTranslator;
import com.jhcwcolin.jctranslator.engine.SentencePieceModel;
import com.jhcwcolin.jctranslator.engine.TextSegmenter;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Desktop-JVM smoke test of the pure-Java engine (no Android needed).
 *
 * <pre>
 *   java -cp out:onnxruntime.jar EngineSmoke &lt;modelsDir&gt; &lt;spReference.tsv&gt; [beams]
 * </pre>
 */
public class EngineSmoke {

    static final String[][] SAMPLES = {
            {"ja-zh", "今日はとても良い天気ですね。散歩に行きませんか？"},
            {"ja-zh", "この電車は東京駅に行きますか？"},
            {"ja-zh", "昨日、友達と一緒に新しいレストランへ行きました。料理はとても美味しかったですが、値段が少し高かったです。\n\n来週もまた行きたいと思います。"},
            {"zh-ja", "今天天气很好，我们一起去公园散步吧。"},
            {"zh-ja", "请问去最近的地铁站怎么走？我想买一张到新宿的票。"},
            {"zh-ja", "这家餐厅的菜非常好吃，但是价格有点贵。下周我还想再去一次。"},
    };

    public static void main(String[] args) throws Exception {
        File models = new File(args[0]);
        int failures = checkTokenizer(models, new File(args[1]));
        int beams = args.length > 2 ? Integer.parseInt(args[2]) : 4;
        translate(models, beams);
        System.out.println(failures == 0 ? "TOKENIZER: ALL MATCH" : ("TOKENIZER: " + failures + " MISMATCHES"));
        System.exit(failures == 0 ? 0 : 1);
    }

    static int checkTokenizer(File models, File tsv) throws Exception {
        Map<String, SentencePieceModel> cache = new HashMap<>();
        int ok = 0;
        int bad = 0;
        for (String line : Files.readAllLines(tsv.toPath(), StandardCharsets.UTF_8)) {
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\t", -1);
            String key = f[0];
            String text = f[1].replace("\\n", "\n").replace("\\t", "\t");
            String expected = f[2];
            SentencePieceModel m = cache.get(key);
            if (m == null) {
                String[] p = key.split("/");
                try (FileInputStream in = new FileInputStream(new File(new File(models, p[0]), p[1] + ".spm"))) {
                    m = SentencePieceModel.load(in);
                }
                cache.put(key, m);
            }
            String got = String.join(" ", m.encodeToPieces(text));
            if (got.equals(expected)) {
                ok++;
            } else {
                bad++;
                System.out.println("MISMATCH " + key + " | " + text);
                System.out.println("   expected: " + expected);
                System.out.println("   got     : " + got);
            }
        }
        System.out.println("tokenizer cases ok=" + ok + " bad=" + bad);
        return bad;
    }

    static void translate(File models, int beams) throws Exception {
        Map<String, MarianTranslator> translators = new HashMap<>();
        for (String[] s : SAMPLES) {
            String dir = s[0];
            MarianTranslator t = translators.get(dir);
            if (t == null) {
                long t0 = System.nanoTime();
                t = new MarianTranslator(new File(models, dir), 4);
                translators.put(dir, t);
                System.out.printf("[%s] tokenizer/vocab load %.2fs%n", dir, (System.nanoTime() - t0) / 1e9);
            }
            long t0 = System.nanoTime();
            List<List<String>> lines = TextSegmenter.split(s[1], 100);
            List<List<MarianTranslator.Encoded>> encoded = new ArrayList<>();
            for (List<String> line : lines) {
                List<MarianTranslator.Encoded> e = new ArrayList<>();
                for (String seg : line) {
                    e.add(t.encode(seg));
                }
                encoded.add(e);
            }
            StringBuilder out = new StringBuilder();
            boolean ja = dir.equals("zh-ja");
            for (int i = 0; i < lines.size(); i++) {
                List<String> parts = new ArrayList<>();
                for (MarianTranslator.Encoded e : encoded.get(i)) {
                    parts.add(CjkText.tidy(t.decode(e, beams), ja));
                }
                if (i > 0) {
                    out.append('\n');
                }
                out.append(CjkText.join(parts));
            }
            System.out.printf("[%s beams=%d %.2fs] %s%n    => %s%n", dir, beams, (System.nanoTime() - t0) / 1e9,
                    s[1].replace("\n", "\\n"), out.toString().replace("\n", "\\n"));
        }
        for (MarianTranslator t : translators.values()) {
            t.close();
        }
    }
}
