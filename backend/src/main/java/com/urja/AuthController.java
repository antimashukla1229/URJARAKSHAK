package com.urja;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * STEP 7: Login / Signup (DEMO-GRADE)
 * Passwords PBKDF2 se hash hote hain (kabhi plain nahi), users data/users.csv me save hote hain.
 * Sessions memory me hain: server restart par dobara login karna padega.
 * Production ke liye HTTPS, rate-limiting, email verification wagairah chahiye.
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin
public class AuthController {

    record User(String email, String name, String role, String discom, String salt, String hash) {}
    record SignupReq(String email, String password, String name, String role, String discom) {}
    record LoginReq(String email, String password) {}

    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final SecureRandom rnd = new SecureRandom();
    private final Path file;

    public AuthController(@Value("${urja.data-dir:../data}") String dir) throws IOException {
        file = Path.of(dir).resolve("users.csv");
        if (Files.exists(file)) {
            for (String l : Files.readAllLines(file)) {
                String[] c = l.split(",");
                if (c.length == 6) users.put(c[0], new User(c[0], c[1], c[2], c[3], c[4], c[5]));
            }
        }
    }

    @PostMapping("/signup")
    public synchronized ResponseEntity<Map<String, Object>> signup(@RequestBody SignupReq q) throws IOException {
        String email = clean(q.email()).toLowerCase(), name = clean(q.name()), discom = clean(q.discom());
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) return err(400, "Enter a valid email");
        if (q.password() == null || q.password().length() < 6) return err(400, "Password must be at least 6 characters");
        if (name.isEmpty()) return err(400, "Enter your name");
        if (!List.of("officer", "inspector").contains(q.role())) return err(400, "Choose a role");
        if (users.containsKey(email)) return err(409, "This email is already registered");

        byte[] salt = new byte[16];
        rnd.nextBytes(salt);
        String saltHex = HexFormat.of().formatHex(salt);
        User u = new User(email, name, q.role(), discom.isEmpty() ? "Demo DISCOM" : discom, saltHex, hash(q.password(), salt));
        users.put(email, u);
        Files.writeString(file, String.join(",", u.email(), u.name(), u.role(), u.discom(), u.salt(), u.hash()) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return session(u);
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginReq q) {
        User u = users.get(clean(q.email()).toLowerCase());
        if (u == null || q.password() == null
                || !MessageDigest.isEqual(hash(q.password(), HexFormat.of().parseHex(u.salt())).getBytes(), u.hash().getBytes()))
            return err(401, "Wrong email or password");
        return session(u);
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@RequestHeader(value = "Authorization", required = false) String h) {
        User u = userFor(h);
        if (u == null) return err(401, "Not logged in");
        return ResponseEntity.ok(Map.of("email", u.email(), "name", u.name(), "role", u.role(), "discom", u.discom()));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String h) {
        if (h != null && h.startsWith("Bearer ")) sessions.remove(h.substring(7));
        return Map.of("ok", true);
    }

    User userFor(String header) {
        if (header == null || !header.startsWith("Bearer ")) return null;
        String email = sessions.get(header.substring(7));
        return email == null ? null : users.get(email);
    }

    Collection<User> users() {
        return users.values();
    }

    private ResponseEntity<Map<String, Object>> session(User u) {
        byte[] b = new byte[32];
        rnd.nextBytes(b);
        String token = HexFormat.of().formatHex(b);
        sessions.put(token, u.email());
        return ResponseEntity.ok(Map.of("token", token, "name", u.name(), "role", u.role(), "discom", u.discom()));
    }

    private static ResponseEntity<Map<String, Object>> err(int status, String msg) {
        return ResponseEntity.status(status).body(Map.<String, Object>of("message", msg));
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim().replace(",", " ");
    }

    private static String hash(String pw, byte[] salt) {
        try {
            var spec = new PBEKeySpec(pw.toCharArray(), salt, 120_000, 256);
            return HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

/** /api/** par token zaroori (login/signup ko chhodkar) */
@Configuration
class AuthConfig implements WebMvcConfigurer {
    private final AuthController auth;

    AuthConfig(AuthController auth) {
        this.auth = auth;
    }

    @Override
    public void addInterceptors(InterceptorRegistry r) {
        r.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                if ("OPTIONS".equals(req.getMethod())) return true;
                if (auth.userFor(req.getHeader("Authorization")) != null) return true;
                res.setStatus(401);
                return false;
            }
        }).addPathPatterns("/api/**").excludePathPatterns("/api/auth/login", "/api/auth/signup");
    }
}
