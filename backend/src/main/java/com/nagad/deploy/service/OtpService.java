package com.nagad.deploy.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Second auth factor: a 6-digit code emailed to the user. Demo-grade (in memory); in
 * production store the code hashed in Redis with the same TTL. TOTP (RFC 6238) is the
 * more resilient choice since it removes the SMTP dependency from the login path.
 */
@Service
public class OtpService {

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> codes = new ConcurrentHashMap<>();

    @Value("${nagad.otp.ttl-seconds}")
    private long ttlSeconds;

    private record Entry(String code, Instant expiresAt) {}

    public String issue(String username) {
        String code = String.format("%06d", random.nextInt(1_000_000));
        codes.put(username, new Entry(code, Instant.now().plus(Duration.ofSeconds(ttlSeconds))));
        return code;
    }

    public boolean verify(String username, String code) {
        Entry e = codes.get(username);
        if (e == null || Instant.now().isAfter(e.expiresAt())) return false;
        boolean ok = e.code().equals(code == null ? null : code.trim());
        if (ok) codes.remove(username);
        return ok;
    }

    public static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return email;
        return email.charAt(0) + "•••••" + email.substring(at - 1);
    }
}
