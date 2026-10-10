package com.urja;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * STEP 4: Thermal Digital Twin (simplified IEEE C57.91 style)
 * Load -> top-oil temperature -> hot-spot temperature -> ageing speed (FAA)
 * Do baar simulate karte hain:
 *   TRUE   = transformer ke input meter ka load (asli load)
 *   BILLED = sirf consumer meters ka load (jo utility ko dikhta hai)
 * Dono ka fark = "invisible ageing" jo chori ki wajah se hota hai.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin
public class ThermalController {

    static final double TO_RISE = 55;     // rated top-oil rise (C)
    static final double HS_RISE = 25;     // rated hot-spot gradient (C)
    static final double R = 4.5;          // load loss / no-load loss
    static final double N = 0.8, M = 0.8; // oil / winding exponents
    static final double TAU = 3;          // oil time constant (hours)
    static final double NORMAL_LIFE_YEARS = 20.5;   // 180,000 hours at 110C hot-spot

    private final DataStore d;

    public ThermalController(DataStore d) {
        this.d = d;
    }

    private double[] hotSpot(int t, boolean billed) {
        double cap = d.cap.get(t);
        double[] hs = new double[d.hours];
        double oil = d.ambient[0] + 25;
        for (int h = 0; h < d.hours; h++) {
            double kw = billed ? d.metered[t][h] + d.techLoss(t, h) : d.input[t][h];
            double K = kw / 0.95 / cap;
            double target = d.ambient[h] + TO_RISE * Math.pow((K * K * R + 1) / (R + 1), N);
            oil += (target - oil) / TAU;
            hs[h] = oil + HS_RISE * Math.pow(K, 2 * M);
        }
        return hs;
    }

    /** Ageing acceleration factor: 1.0 = normal speed, 5.0 = 5 guna tez budhapa */
    private static double faa(double hs) {
        return Math.exp(15000.0 / 383 - 15000.0 / (hs + 273));
    }

    Map<String, Object> summary(int t, boolean daily) {
        double[] a = hotSpot(t, false), b = hotSpot(t, true);
        int days = d.hours / 24, w = d.hours - 240;
        double ageA = 0, ageB = 0, recA = 0, recB = 0, peakA = 0, peakB = 0;
        int hot = 0;
        double[] dpA = new double[days], dpB = new double[days];
        for (int h = 0; h < days * 24; h++) {
            double fa = faa(a[h]), fb = faa(b[h]);
            ageA += fa;
            ageB += fb;
            if (h >= w) { recA += fa; recB += fb; }
            peakA = Math.max(peakA, a[h]);
            peakB = Math.max(peakB, b[h]);
            if (a[h] > 110) hot++;
            dpA[h / 24] = Math.max(dpA[h / 24], a[h]);
            dpB[h / 24] = Math.max(dpB[h / 24], b[h]);
        }
        double rateA = recA / 240, rateB = recB / 240;     // zindagi ke din jo har din kharch ho rahe hain

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.trIds.get(t));
        m.put("peakHotSpotC", r(peakA));
        m.put("peakHotSpotBilledViewC", r(peakB));
        m.put("hoursAbove110C", hot);
        m.put("lifeDaysUsed30d", r(ageA / 24));
        m.put("lifeDaysUsed30dBilledView", r(ageB / 24));
        m.put("invisibleAgeingDays", r((ageA - ageB) / 24));
        m.put("ageingSpeedNow", r(rateA));                 // 1.0 = normal
        m.put("ageingMultiple", r(ageB > 1 ? ageA / ageB : 1.0));   // asli ageing / billed-view ageing
        m.put("health", (hot >= 72 || peakA > 140) ? "CRITICAL" : hot >= 24 ? "STRESSED" : hot > 0 ? "WATCH" : "HEALTHY");
        if (daily) {
            for (int i = 0; i < days; i++) { dpA[i] = r(dpA[i]); dpB[i] = r(dpB[i]); }
            m.put("dailyPeakHotSpotC", dpA);
            m.put("dailyPeakHotSpotBilledViewC", dpB);
        }
        return m;
    }

    @GetMapping("/thermal")
    public List<Map<String, Object>> all() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int t = 0; t < d.trIds.size(); t++) out.add(summary(t, false));
        return out;
    }

    @GetMapping("/transformers/{id}/thermal")
    public Map<String, Object> one(@PathVariable String id) {
        return summary(d.trIds.indexOf(id), true);
    }

    private static double r(double x) {
        return Math.round(x * 10) / 10.0;
    }
}
