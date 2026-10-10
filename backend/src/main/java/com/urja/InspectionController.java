package com.urja;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * STEP 5: Inspection priority = expected recovery / inspection cost
 * Assumptions (UI me bhi dikhayenge): tariff Rs 7/kWh, consumer visit Rs 500,
 * area audit Rs 3000 (50% hidden energy recover hone ka maan ke), 10-din ka data x3 = 1 mahina.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin
public class InspectionController {

    static final double TARIFF = 7, VISIT = 500, AUDIT = 3000, MONTH = 3.0;

    private final DataStore d;
    private final DetectorController det;

    public InspectionController(DataStore d, DetectorController det) {
        this.d = d;
        this.det = det;
    }

    @GetMapping("/inspections")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<Integer, Double> explained = new HashMap<>();

        for (DetectorController.Flag f : det.flags()) {
            explained.merge(f.tr(), f.stolenKwh(), Double::sum);
            double rec = f.prob() * f.stolenKwh() * MONTH * TARIFF;
            out.add(row("CONSUMER", f.id(), d.trIds.get(f.tr()), rec, VISIT,
                    "Meter reads " + Math.round(f.rel() * 100) + "% of its neighbours' trend (theft probability "
                            + Math.round(f.prob() * 100) + "%)"));
        }

        int s = d.hours - 240;
        for (int t = 0; t < d.trIds.size(); t++) {
            double un = 0, night = 0, day = 0;
            int nn = 0, nd = 0;
            for (int h = s; h < d.hours; h++) {
                double u = d.input[t][h] - d.metered[t][h] - d.techLoss(t, h);
                un += u;
                int hod = h % 24;
                if (hod <= 5 || hod >= 22) { night += u; nn++; }
                else if (hod >= 10 && hod <= 16) { day += u; nd++; }
            }
            double hidden = un - explained.getOrDefault(t, 0.0);
            if (hidden > 150) {
                boolean nightHeavy = night / nn > 1.5 * Math.max(day / nd, 0.1);
                String why = Math.round(hidden) + " kWh in 10 days not explained by any meter, "
                        + (nightHeavy ? "night-heavy pattern (possible night tapping)"
                                      : "spread across the day (possible illegal hooking)");
                out.add(row("AREA AUDIT", d.trIds.get(t) + " feeder area", d.trIds.get(t),
                        hidden * MONTH * TARIFF * 0.5, AUDIT, why));
            }
        }

        out.sort((a, b) -> Double.compare((double) b.get("priority"), (double) a.get("priority")));
        List<Map<String, Object>> top = new ArrayList<>(out.subList(0, Math.min(15, out.size())));
        for (int i = 0; i < top.size(); i++) top.get(i).put("rank", i + 1);
        return top;
    }

    private Map<String, Object> row(String type, String target, String tr, double rec, double cost, String why) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("target", target);
        m.put("transformer", tr);
        m.put("estMonthlyRecoveryInr", Math.round(rec));
        m.put("costInr", Math.round(cost));
        m.put("priority", Math.round(rec / cost * 10) / 10.0);
        m.put("reason", why);
        return m;
    }
}
