package com.urja;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * STEP 8: Search
 * /api/search?q=C00  -> consumers aur transformers dhoondho
 * /api/consumers/{id} -> ek consumer ka meter reading vs padosiyon ke trend ka expected reading
 */
@RestController
@RequestMapping("/api")
@CrossOrigin
public class SearchController {

    private static final int W = 240, B = 120;
    private final DataStore d;
    private final DetectorController det;

    public SearchController(DataStore d, DetectorController det) {
        this.d = d;
        this.det = det;
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "false") boolean flagged) {
        String s = q.trim().toLowerCase();
        Map<String, DetectorController.Flag> fl = new HashMap<>();
        for (DetectorController.Flag f : det.flags()) fl.put(f.id(), f);

        List<Map<String, Object>> trs = new ArrayList<>(), cons = new ArrayList<>();
        if (!flagged) {
            for (int t = 0; t < d.trIds.size(); t++) {
                String id = d.trIds.get(t);
                if (!s.isEmpty() && id.toLowerCase().contains(s)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", id);
                    m.put("capacityKva", d.cap.get(t));
                    trs.add(m);
                }
            }
        }
        if (flagged || !s.isEmpty()) {
            for (int i = 0; i < d.consIds.size() && cons.size() < 25; i++) {
                String id = d.consIds.get(i);
                DetectorController.Flag f = fl.get(id);
                if (flagged && f == null) continue;
                if (!s.isEmpty() && !id.toLowerCase().contains(s)) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", id);
                m.put("transformer", d.trIds.get(d.consTr[i]));
                m.put("flagged", f != null);
                m.put("theftProbability", f == null ? 0 : Math.round(f.prob() * 100));
                cons.add(m);
            }
            cons.sort((a, b) -> Long.compare((long) b.get("theftProbability"), (long) a.get("theftProbability")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transformers", trs);
        out.put("consumers", cons);
        return out;
    }

    @GetMapping("/consumers/{id}")
    public ResponseEntity<Map<String, Object>> consumer(@PathVariable String id) {
        int i = d.consIds.indexOf(id);
        if (i < 0) return ResponseEntity.notFound().build();
        int t = d.consTr[i], days = d.hours / 24, s = d.hours - W;

        List<Integer> peers = new ArrayList<>();
        for (int c = 0; c < d.cons.length; c++) if (d.consTr[c] == t) peers.add(c);
        double[] ratios = new double[peers.size()];
        double[][] factor = new double[days][peers.size()];
        double baseI = 0;
        for (int k = 0; k < peers.size(); k++) {
            int c = peers.get(k);
            double b = mean(d.cons[c], 0, B);
            if (c == i) baseI = b;
            ratios[k] = mean(d.cons[c], s, d.hours) / b;
            for (int dd = 0; dd < days; dd++) factor[dd][k] = mean(d.cons[c], dd * 24, dd * 24 + 24) / b;
        }
        double med = median(ratios), rec = mean(d.cons[i], s, d.hours), rel = rec / baseI / med;
        double[] actual = new double[days], expected = new double[days];
        for (int dd = 0; dd < days; dd++) {
            actual[dd] = r2(mean(d.cons[i], dd * 24, dd * 24 + 24));
            expected[dd] = r2(baseI * median(factor[dd]));
        }
        boolean sus = rel < 0.82;
        double prob = sus ? Math.min(1, (0.92 - rel) / 0.35) : 0;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("consumerId", id);
        m.put("transformer", d.trIds.get(t));
        m.put("baselineKw", r2(baseI));
        m.put("recentKw", r2(rec));
        m.put("vsNeighboursPct", Math.round(rel * 1000) / 10.0);
        m.put("theftProbability", Math.round(prob * 1000) / 10.0);
        m.put("estUnmeteredKwh", sus ? Math.round((baseI * med - rec) * W) : 0);
        m.put("status", sus ? "SUSPECT" : "NORMAL");
        m.put("explanation", sus
                ? "Since the baseline period this meter reads " + Math.round(rel * 100) + "% of what its neighbours' trend predicts. Low reading alone is not proof; field inspection should confirm."
                : "Reading follows its neighbours' trend. Note: theft that bypasses the meter (night tapping, hooking) cannot be seen here; check this transformer's hidden load on the Health page.");
        m.put("dailyActualKw", actual);
        m.put("dailyExpectedKw", expected);
        return ResponseEntity.ok(m);
    }

    private static double mean(double[] a, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) s += a[i];
        return s / (to - from);
    }

    private static double median(double[] a) {
        double[] c = a.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }

    private static double r2(double x) {
        return Math.round(x * 100) / 100.0;
    }
}
