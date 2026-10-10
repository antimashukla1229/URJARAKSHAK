package com.urja;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * STEP 2: Loss Attribution
 * Transformer me jo bijli gayi (input)
 *   - consumers ke meters ka total (metered)
 *   - technical loss (copper + iron, physics se)
 *   = UNEXPLAINED loss  -> yahi chori ka shak hai
 */
@RestController
@RequestMapping("/api")
@CrossOrigin
public class GridController {

    private final DataStore d;

    public GridController(DataStore d) {
        this.d = d;
    }

    @GetMapping("/transformers")
    public List<Map<String, Object>> transformers() {
        List<Map<String, Object>> out = new ArrayList<>();
        int days = d.hours / 24;
        int windowStart = Math.max(0, d.hours - 240);   // last 10 din ka hisaab

        for (int t = 0; t < d.trIds.size(); t++) {
            double cap = d.cap.get(t);
            double in = 0, met = 0, tech = 0, peak = 0;
            double[] dayUnexp = new double[days];
            double[] dayIn = new double[days];

            for (int h = 0; h < days * 24; h++) {
                double K = d.input[t][h] / 0.95 / cap;                 // load factor
                double loss = 0.015 * cap * K * K + 0.004 * cap;       // technical loss (kW)
                double unexp = d.input[t][h] - d.metered[t][h] - loss;
                dayUnexp[h / 24] += unexp;
                dayIn[h / 24] += d.input[t][h];
                peak = Math.max(peak, K);
                if (h >= windowStart) {
                    in += d.input[t][h];
                    met += d.metered[t][h];
                    tech += loss;
                }
            }

            double unexplained = in - met - tech;
            double pct = unexplained / in * 100;

            double[] dailyPct = new double[days];
            for (int i = 0; i < days; i++) dailyPct[i] = r(dayUnexp[i] / dayIn[i] * 100);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.trIds.get(t));
            row.put("capacityKva", (int) cap);
            row.put("lat", d.pos.get(t)[0]);
            row.put("lon", d.pos.get(t)[1]);
            row.put("peakLoadPct", r(peak * 100));
            row.put("inputKwh", r(in));
            row.put("meteredKwh", r(met));
            row.put("technicalLossKwh", r(tech));
            row.put("unexplainedKwh", r(unexplained));
            row.put("unexplainedPct", r(pct));
            row.put("status", pct > 10 ? "SUSPECT" : pct > 5 ? "WATCH" : "OK");   // thresholds baad me tune karenge
            row.put("dailyUnexplainedPct", dailyPct);
            out.add(row);
        }
        return out;
    }

    private static double r(double x) {
        return Math.round(x * 10) / 10.0;
    }
}
