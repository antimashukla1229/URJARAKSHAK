package com.urja;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Missed-alert reminders.
 * CRITICAL transformer jab tak officer acknowledge na kare:
 *   60 s baad web banner, 180 s baad escalation (aur optional WhatsApp, URJA_AUTO_WHATSAPP=true hone par).
 * Acknowledge 30 minute ke liye snooze karta hai; phir bhi critical raha toh dobara yaad dilata hai.
 * Sirf role "officer" (admin) ko reminders milte hain.
 */
@RestController
@RequestMapping("/api/reminders")
@CrossOrigin
public class ReminderController {

    static final long REMIND_AFTER = 60, WA_AFTER = 180, SNOOZE = 30 * 60, WA_GAP = 30 * 60;   // seconds

    record AckReq(String id) {}

    private final GridController grid;
    private final ThermalController thermal;
    private final AuthController auth;
    private final ProfileController profiles;
    private final AlertController alerts;
    private final Map<String, Long> firstSeen = new ConcurrentHashMap<>();
    private final Map<String, Long> acked = new ConcurrentHashMap<>();
    private final Map<String, Long> waSent = new ConcurrentHashMap<>();

    public ReminderController(GridController grid, ThermalController thermal, AuthController auth,
                              ProfileController profiles, AlertController alerts) {
        this.grid = grid;
        this.thermal = thermal;
        this.auth = auth;
        this.profiles = profiles;
        this.alerts = alerts;
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reminder");
            t.setDaemon(true);
            return t;
        }).scheduleWithFixedDelay(this::tick, 30, 20, TimeUnit.SECONDS);
    }

    /** Abhi kaun se transformers CRITICAL hain (dashboard ke 'ACT NOW' jaisa rule) */
    private synchronized Map<String, String> sync() {
        Map<String, Map<String, Object>> th = new HashMap<>();
        for (Map<String, Object> m : thermal.all()) th.put((String) m.get("id"), m);
        Map<String, String> crit = new LinkedHashMap<>();
        for (Map<String, Object> t : grid.transformers()) {
            String id = (String) t.get("id");
            double u = (double) t.get("unexplainedPct");
            int h = (int) th.get(id).get("hoursAbove110C");
            if (u >= 12 || h >= 24) crit.put(id, "unexplained loss " + u + "%, " + h + " h above 110C");
        }
        long now = System.currentTimeMillis();
        firstSeen.keySet().removeIf(k -> !crit.containsKey(k));
        for (String id : crit.keySet()) firstSeen.putIfAbsent(id, now);
        return crit;
    }

    private boolean isAcked(String email, String id, long now) {
        Long a = acked.get(email + "|" + id);
        return a != null && now - a < SNOOZE * 1000;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String h) {
        AuthController.User u = auth.userFor(h);
        if (u == null) return ResponseEntity.status(401).body(Map.of("message", "Not logged in"));
        List<Map<String, Object>> items = new ArrayList<>();
        if ("officer".equals(u.role())) {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, String> e : sync().entrySet()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.getKey());
                m.put("detail", e.getValue());
                m.put("ageSec", (now - firstSeen.get(e.getKey())) / 1000);
                m.put("acked", isAcked(u.email(), e.getKey(), now));
                items.add(m);
            }
        }
        return ResponseEntity.ok(Map.of("remindAfterSec", REMIND_AFTER, "items", items));
    }

    @PostMapping("/ack")
    public ResponseEntity<Map<String, Object>> ack(@RequestHeader(value = "Authorization", required = false) String h,
                                                   @RequestBody AckReq q) {
        AuthController.User u = auth.userFor(h);
        if (u == null) return ResponseEntity.status(401).body(Map.of("message", "Not logged in"));
        long now = System.currentTimeMillis();
        if ("ALL".equals(q.id())) for (String id : sync().keySet()) acked.put(u.email() + "|" + id, now);
        else if (q.id() != null) acked.put(u.email() + "|" + q.id(), now);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** Har 20 s: state update, aur (agar enabled) WhatsApp escalation */
    private void tick() {
        try {
            Map<String, String> crit = sync();
            if (!"true".equalsIgnoreCase(System.getenv("URJA_AUTO_WHATSAPP"))) return;
            long now = System.currentTimeMillis();
            for (Map.Entry<String, String> e : crit.entrySet()) {
                if (now - firstSeen.get(e.getKey()) < WA_AFTER * 1000) continue;
                for (AuthController.User u : auth.users()) {
                    String key = u.email() + "|" + e.getKey(), phone = profiles.phoneOf(u.email());
                    if (!"officer".equals(u.role()) || phone.isEmpty() || isAcked(u.email(), e.getKey(), now)) continue;
                    Long sent = waSent.get(key);
                    if (sent != null && now - sent < WA_GAP * 1000) continue;
                    waSent.put(key, now);   // fail hone par bhi dobara turant nahi bhejenge
                    alerts.sendWhatsApp(phone, "UrjaRakshak reminder: " + e.getKey() + " is still CRITICAL and not acknowledged ("
                            + e.getValue() + "). Please check the dashboard.");
                }
            }
        } catch (Exception ignored) {
        }
    }
}
