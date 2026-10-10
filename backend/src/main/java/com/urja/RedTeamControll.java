package com.urja;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * STEP 6: Red-team simulator
 * Ek saaf transformer ke kuch consumers par chori "inject" karta hai (data me asli badlav),
 * phir detector + thermal engine dobara chalata hai aur before/after dikhata hai.
 */
@RestController
@RequestMapping("/api/redteam")
@CrossOrigin
public class RedTeamControll {

    static final List<String> TYPES = List.of("meter_bypass", "meter_tamper", "night_tap", "hooking");
    static final double[] SHAPE = new double[24];

    static {   // simulator.py wala daily pattern (evening peak), mean = 1
        double sum = 0;
        for (int h = 0; h < 24; h++) {
            SHAPE[h] = 0.45 + 0.35 * Math.exp(-Math.pow(h - 8, 2) / 8) + 0.95 * Math.exp(-Math.pow(h - 20, 2) / 10);
            sum += SHAPE[h];
        }
        for (int h = 0; h < 24; h++) SHAPE[h] /= sum / 24;
    }

    private final DataStore d;
    private final DetectorController det;
    private final ThermalController thermal;
    private final Set<String> injected = new LinkedHashSet<>();
    private final Random rnd = new Random(7);

    public RedTeamControll(DataStore d, DetectorController det, ThermalController thermal) {
        this.d = d;
        this.det = det;
        this.thermal = thermal;
    }

    record Req(String transformer, String type, int count, int days) {}

    @PostMapping("/inject")
    public synchronized Map<String, Object> inject(@RequestBody Req q) {
        int t = d.trIds.indexOf(q.transformer());
        if (t < 0 || !TYPES.contains(q.type()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown transformer or theft type");
        int count = Math.max(1, Math.min(q.count(), 10));
        int days = Math.max(1, Math.min(q.days(), 10));
        int start = d.hours - days * 24;

        Map<String, Object> before = snap(t);

        // sirf aise consumers chuno jo abhi saaf hain
        Set<String> flagged = new HashSet<>();
        for (DetectorController.Flag f : det.flags()) flagged.add(f.id());
        List<Integer> cand = new ArrayList<>();
        for (int i = 0; i < d.cons.length; i++) {
            String id = d.consIds.get(i);
            if (d.consTr[i] == t && !flagged.contains(id) && !injected.contains(id)) cand.add(i);
        }
        Collections.shuffle(cand, rnd);
        List<String> ids = new ArrayList<>();
        for (int k = 0; k < Math.min(count, cand.size()); k++) {
            int j = cand.get(k);
            apply(t, j, q.type(), start);
            ids.add(d.consIds.get(j));
            injected.add(d.consIds.get(j));
        }

        Map<String, Object> after = snap(t);

        Map<String, DetectorController.Flag> fl = new HashMap<>();
        for (DetectorController.Flag f : det.flags()) fl.put(f.id(), f);
        List<Map<String, Object>> detection = new ArrayList<>();
        int caught = 0;
        for (String id : ids) {
            DetectorController.Flag f = fl.get(id);
            if (f != null) caught++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("consumerId", id);
            m.put("flaggedByMeterAnalysis", f != null);
            m.put("theftProbability", f == null ? 0 : Math.round(f.prob() * 100));
            detection.add(m);
        }

        double dHidden = (double) after.get("hiddenKwh") - (double) before.get("hiddenKwh");
        String verdict;
        if (caught > 0)
            verdict = "Meter analysis flagged " + caught + " of " + ids.size() + " injected meters.";
        else if (dHidden >= 150)
            verdict = "Every meter looked normal, but the transformer energy balance exposed +" + Math.round(dHidden)
                    + " kWh of hidden load, so an area audit is raised.";
        else
            verdict = "Not caught yet: the theft is too small or too recent. Try more days or more consumers.";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transformer", q.transformer());
        out.put("type", q.type());
        out.put("verdict", verdict);
        out.put("before", before);
        out.put("after", after);
        out.put("detection", detection);
        return out;
    }

    @PostMapping("/reset")
    public synchronized Map<String, Object> reset() {
        d.reset();
        injected.clear();
        return Map.of("ok", true);
    }

    /** Ek consumer par chori lagao: meter ka reading badlo aur transformer ka asli load badhao */
    private void apply(int t, int j, String type, int start) {
        double cap = d.cap.get(t);
        for (int h = start; h < d.hours; h++) {
            double dem = d.cons[j][h], extra = 0, newMet = dem;
            int hod = h % 24;
            switch (type) {
                case "meter_bypass" -> { extra = 0.3 * dem; newMet = 0.35 * dem; }
                case "meter_tamper" -> { extra = 0.2 * dem; newMet = 0.6 * dem; }
                case "night_tap" -> { if (hod <= 5 || hod >= 22) extra = 2.0; }
                default -> extra = 1.5 * SHAPE[hod];     // hooking
            }
            double K = d.input[t][h] / 0.95 / cap;
            d.input[t][h] += extra * (1 + 0.032 * K);    // extra load + uske copper loss ka chhota hissa
            d.metered[t][h] += newMet - dem;
            d.cons[j][h] = newMet;
        }
    }

    private Map<String, Object> snap(int t) {
        int s = d.hours - 240;
        double in = 0, met = 0, tech = 0;
        for (int h = s; h < d.hours; h++) {
            in += d.input[t][h];
            met += d.metered[t][h];
            tech += d.techLoss(t, h);
        }
        double un = in - met - tech, explained = 0;
        for (DetectorController.Flag f : det.flags()) if (f.tr() == t) explained += f.stolenKwh();
        Map<String, Object> th = thermal.summary(t, false);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unexplainedPct", r(un / in * 100));
        m.put("hiddenKwh", r(un - explained));
        for (String k : List.of("peakHotSpotC", "peakHotSpotBilledViewC", "hoursAbove110C", "ageingMultiple", "health"))
            m.put(k, th.get(k));
        return m;
    }

    private static double r(double x) {
        return Math.round(x * 10) / 10.0;
    }
}
