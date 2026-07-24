package com.nagad.deploy.security;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opaque bearer-token session store. Demo-grade (in memory); in production back this
 * with Redis so tokens survive a restart and scale across nodes.
 */
@Component
public class SessionStore {

    private static final Duration TTL = Duration.ofHours(8);
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public record Session(String username, Instant issuedAt) {}

    public String issue(String username) {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        sessions.put(token, new Session(username, Instant.now()));
        return token;
    }

    public String resolve(String token) {
        Session s = sessions.get(token);
        if (s == null) return null;
        if (Instant.now().isAfter(s.issuedAt().plus(TTL))) {
            sessions.remove(token);
            return null;
        }
        return s.username();
    }

    public void revoke(String token) {
        if (token != null) sessions.remove(token);
    }
}
