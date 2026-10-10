package com.urja;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * User profile (name, mobile, age, national ID).
 * National ID (Aadhaar format): sirf format + Verhoeff checksum check hota hai (verification NAHI).
 * Poora number store nahi hota, sirf aakhri 4 digits.
 */
@RestController
@RequestMapping("/api/profile")
@CrossOrigin
public class ProfileController {

    record P(String fullName, String phone, int age, String idLast4) {}
    record Req(String fullName, String phone, Integer age, String nationalId) {}

    private final AuthController auth;
    private final Map<String, P> profiles = new ConcurrentHashMap<>();
    private final Path file;

    public ProfileController(AuthController auth, @Value("${urja.data-dir:../data}") String dir) throws IOException {
        this.auth = auth;
        this.file = Path.of(dir).resolve("profiles.csv");
        if (Files.exists(file)) {
            for (String l : Files.readAllLines(file)) {
                String[] c = l.split(",", -1);
                if (c.length == 5) profiles.put(c[0], new P(c[1], c[2], Integer.parseInt(c[3]), c[4]));
            }
        }
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(@RequestHeader(value = "Authorization", required = false) String h) {
        AuthController.User u = auth.userFor(h);
        if (u == null) return err(401, "Not logged in");
        P p = profiles.get(u.email());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", u.email());
        m.put("role", u.role());
        m.put("discom", u.discom());
        m.put("fullName", p == null ? u.name() : p.fullName());
        m.put("phone", p == null ? "" : p.phone());
        m.put("age", p == null ? "" : p.age());
        m.put("idMasked", p == null || p.idLast4().isEmpty() ? "" : "XXXX XXXX " + p.idLast4());
        m.put("complete", p != null && !p.phone().isEmpty() && p.age() > 0 && !p.idLast4().isEmpty());
        return ResponseEntity.ok(m);
    }

    @PutMapping
    public synchronized ResponseEntity<Map<String, Object>> save(@RequestHeader(value = "Authorization", required = false) String h,
                                                                  @RequestBody Req q) throws IOException {
        AuthController.User u = auth.userFor(h);
        if (u == null) return err(401, "Not logged in");
        String name = q.fullName() == null ? "" : q.fullName().trim();
        String phone = q.phone() == null ? "" : q.phone().replaceAll("[\\s-]", "").replaceFirst("^\\+?91", "");
        String nid = q.nationalId() == null ? "" : q.nationalId().replaceAll("\\s", "");
        P old = profiles.get(u.email());

        if (!name.matches("^[\\p{L} .'-]{2,60}$")) return err(400, "Enter your full name (letters only)");
        if (!phone.matches("^[6-9]\\d{9}$")) return err(400, "Enter a valid 10-digit Indian mobile number");
        if (q.age() == null || q.age() < 18 || q.age() > 70) return err(400, "Age must be between 18 and 70");
        String last4;
        if (nid.isEmpty() && old != null && !old.idLast4().isEmpty()) last4 = old.idLast4();
        else if (nid.matches("^[2-9]\\d{11}$") && verhoeff(nid)) last4 = nid.substring(8);
        else return err(400, "Enter a valid 12-digit Aadhaar number (format and checksum are checked)");

        profiles.put(u.email(), new P(name.replace(",", " "), phone, q.age(), last4));
        StringBuilder sb = new StringBuilder();
        profiles.forEach((e, p) -> sb.append(String.join(",", e, p.fullName(), p.phone(), String.valueOf(p.age()), p.idLast4())).append("\n"));
        Files.writeString(file, sb.toString());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    String phoneOf(String email) {
        P p = profiles.get(email);
        return p == null ? "" : p.phone();
    }

    private static final int[][] D = {{0,1,2,3,4,5,6,7,8,9},{1,2,3,4,0,6,7,8,9,5},{2,3,4,0,1,7,8,9,5,6},{3,4,0,1,2,8,9,5,6,7},{4,0,1,2,3,9,5,6,7,8},
            {5,9,8,7,6,0,4,3,2,1},{6,5,9,8,7,1,0,4,3,2},{7,6,5,9,8,2,1,0,4,3},{8,7,6,5,9,3,2,1,0,4},{9,8,7,6,5,4,3,2,1,0}};
    private static final int[][] PM = {{0,1,2,3,4,5,6,7,8,9},{1,5,7,6,2,8,3,0,9,4},{5,8,0,3,7,9,6,1,4,2},{8,9,1,6,0,4,3,5,2,7},
            {9,4,5,3,1,2,6,8,7,0},{4,2,8,6,5,7,3,9,0,1},{2,7,9,3,8,0,6,4,1,5},{7,0,4,6,9,1,3,2,5,8}};

    static boolean verhoeff(String n) {
        int c = 0;
        for (int i = 0; i < n.length(); i++) c = D[c][PM[i % 8][n.charAt(n.length() - 1 - i) - '0']];
        return c == 0;
    }

    private static ResponseEntity<Map<String, Object>> err(int s, String m) {
        return ResponseEntity.status(s).body(Map.<String, Object>of("message", m));
    }
}
