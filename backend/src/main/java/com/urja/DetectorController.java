package com.urja;

import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * STEP 3: Consumer-level detector
 * Idea: chori shuru hone se pehle ke 5 din = baseline.
 * Last 10 din ka reading apne baseline se compare karo, aur apne PADOSIYON (same transformer)
 * ke change se normalise karo (mausam/season ka asar cancel ho jata hai).
 * Jo meter padosiyon se bahut zyada gira = shak.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin
public class DetectorController {

    private static final int W = 240;     // recent window (hours)
    private static final int B = 120;     // baseline window (hours)
    private final DataStore d;

    public DetectorController(DataStore d) {
        this.d = d;
    }

    record Flag(String id, int tr, double rel, double prob, double stolenKwh) {}

    List<Flag> flags() {
        int n = d.cons.length, s = d.hours - W;
        double[] base = new double[n], rec = new double[n], ratio = new double[n];
        List<List<Double>> byTr = new ArrayList<>();
        for (int t = 0; t < d.trIds.size(); t++) byTr.add(new ArrayList<>());

        for (int i = 0; i < n; i++) {
            base[i] = mean(d.cons[i], 0, B);
            rec[i] = mean(d.cons[i], s, d.hours);
            ratio[i] = rec[i] / base[i];
            byTr.get(d.consTr[i]).add(ratio[i]);
        }
        double[] med = new double[byTr.size()];
        for (int t = 0; t < med.length; t++) {
            List<Double> l = byTr.get(t);
            Collections.sort(l);
            med[t] = l.get(l.size() / 2);
        }

        List<Flag> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int t = d.consTr[i];
            double rel = ratio[i] / med[t];                 // 1.0 = padosiyon jaisa, 0.5 = aadha ho gaya
            if (rel < 0.82) {
                double prob = Math.min(1, (0.92 - rel) / 0.35);
                double stolen = (base[i] * med[t] - rec[i]) * W;   // kam se kam itni bijli meter se bahar gayi
                out.add(new Flag(d.consIds.get(i), t, rel, prob, stolen));
            }
        }
        return out;
    }

    /** Ek transformer ki poori detail: shak wale consumers + meter-invisible hidden load */
    @GetMapping("/transformers/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        int t = d.trIds.indexOf(id), s = d.hours - W;
        double un = 0;
        double[] prof = new double[24];
        for (int h = s; h < d.hours; h++) {
            double u = d.input[t][h] - d.metered[t][h] - d.techLoss(t, h);
            un += u;
            prof[h % 24] += u / (W / 24.0);                 // avg hidden kW us ghante par
        }
        List<Map<String, Object>> sus = new ArrayList<>();
        double explained = 0;
        for (Flag f : flags()) {
            if (f.tr() != t) continue;
            explained += f.stolenKwh();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("consumerId", f.id());
            m.put("theftProbability", r(f.prob() * 100));
            m.put("readingVsNeighboursPct", r(f.rel() * 100));
            m.put("estUnmeteredKwh", r(f.stolenKwh()));
            sus.add(m);
        }
        sus.sort((a, b) -> Double.compare((double) b.get("theftProbability"), (double) a.get("theftProbability")));

        for (int i = 0; i < 24; i++) prof[i] = r(prof[i]);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("unexplainedKwh", r(un));
        out.put("explainedByFlaggedMetersKwh", r(explained));
        out.put("meterInvisibleHiddenKwh", r(un - explained));     // night tap / hooking jaisi chori
        out.put("hiddenLoadByHourKw", prof);
        out.put("suspectConsumers", sus);
        return out;
    }

    /** SIRF HUMARE CHECK KE LIYE: detector ne ground truth se kitna match kiya */
    @GetMapping("/scorecard")
    public Map<String, Object> scorecard() throws Exception {
        List<String> lines = Files.readAllLines(Path.of(d.dataDir()).resolve("ground_truth.csv"));
        Map<String, String> truth = new HashMap<>();
        for (String l : lines.subList(1, lines.size())) {
            String[] c = l.split(",");
            truth.put(c[0], c[2]);
        }
        Set<String> flagged = new HashSet<>();
        for (Flag f : flags()) flagged.add(f.id());

        Map<String, int[]> byType = new TreeMap<>();
        for (Map.Entry<String, String> e : truth.entrySet()) {
            int[] a = byType.computeIfAbsent(e.getValue(), k -> new int[2]);
            a[0]++;
            if (flagged.contains(e.getKey())) a[1]++;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        byType.forEach((k, v) -> rows.add(new LinkedHashMap<>(Map.of("theftType", k, "total", v[0], "caughtByMeterAnalysis", v[1]))));

        int falseAlarms = 0;
        for (String f : flagged) if (!truth.containsKey(f)) falseAlarms++;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byType", rows);
        out.put("totalFlagged", flagged.size());
        out.put("falseAlarms", falseAlarms);
        return out;
    }

    private static double mean(double[] a, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) s += a[i];
        return s / (to - from);
    }

    private static double r(double x) {
        return Math.round(x * 10) / 10.0;
    }
}

