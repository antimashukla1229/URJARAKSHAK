package com.urja;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WhatsApp alert via Twilio (sandbox ya paid number).
 * Keys sirf environment variables me: TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, (optional) TWILIO_WHATSAPP_FROM.
 * Message sirf logged-in user ke apne saved mobile number par jata hai.
 */
@RestController
@RequestMapping("/api/alerts")
@CrossOrigin
public class AlertController {

    record Req(String message) {}
    record Result(int status, boolean ok, String message) {}

    private final AuthController auth;
    private final ProfileController profiles;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, Long> last = new ConcurrentHashMap<>();

    public AlertController(AuthController auth, ProfileController profiles) {
        this.auth = auth;
        this.profiles = profiles;
    }

    @PostMapping("/whatsapp")
    public ResponseEntity<Map<String, Object>> send(@RequestHeader(value = "Authorization", required = false) String h,
                                                    @RequestBody Req q) {
        AuthController.User u = auth.userFor(h);
        if (u == null) return out(new Result(401, false, "Not logged in"));
        String phone = profiles.phoneOf(u.email());
        if (phone.isEmpty()) return out(new Result(400, false, "Save your mobile number on the Profile page first"));
        String msg = q.message() == null ? "" : q.message().trim();
        if (msg.isEmpty()) return out(new Result(400, false, "Empty message"));

        long now = System.currentTimeMillis();
        Long prev = last.put(u.email(), now);
        if (prev != null && now - prev < 5000) return out(new Result(429, false, "Please wait a few seconds between messages"));
        return out(sendWhatsApp(phone, msg));
    }

    /** Reminder system bhi isi ko use karta hai */
    Result sendWhatsApp(String phone, String msg) {
        String sid = System.getenv("TWILIO_ACCOUNT_SID"), tok = System.getenv("TWILIO_AUTH_TOKEN");
        if (sid == null || sid.isBlank() || tok == null || tok.isBlank())
            return new Result(503, false, "WhatsApp sending is not configured on the server");
        if (msg.length() > 600) msg = msg.substring(0, 600);
        String from = System.getenv("TWILIO_WHATSAPP_FROM");
        if (from == null || from.isBlank()) from = "whatsapp:+14155238886";
        try {
            String form = "From=" + enc(from) + "&To=" + enc("whatsapp:+91" + phone) + "&Body=" + enc(msg);
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.twilio.com/2010-04-01/Accounts/" + sid + "/Messages.json"))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((sid + ":" + tok).getBytes(StandardCharsets.UTF_8)))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 201) return new Result(200, true, "Sent to WhatsApp number ending " + phone.substring(6));
            String body = res.body();
            return new Result(502, false, "WhatsApp provider error " + res.statusCode() + ": " + (body.length() > 250 ? body.substring(0, 250) : body));
        } catch (Exception e) {
            return new Result(500, false, "Could not reach the WhatsApp service");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static ResponseEntity<Map<String, Object>> out(Result r) {
        return ResponseEntity.status(r.status()).body(Map.<String, Object>of("ok", r.ok(), "message", r.message()));
    }
}
