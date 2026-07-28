package com.nagad.deploy.service;

import com.nagad.deploy.domain.AppUser;
import com.nagad.deploy.dto.Dtos.*;
import com.nagad.deploy.repo.AppUserRepository;
import com.nagad.deploy.security.SessionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * Two-step sign-in: password (factor 1) then a 6-digit emailed OTP (factor 2).
 *
 * <p>Between the steps a short-lived {@code step1Token} holds the pending username so the
 * OTP call cannot be pointed at a different account.
 */
@Service
public class AuthService {

    private final AppUserRepository users;
    private final OtpService otp;
    private final MailService mail;
    private final SessionStore sessions;
    private final AuditService audit;
    private final PasswordEncoder encoder;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, String> pending = new ConcurrentHashMap<>(); // step1Token -> username

    @Value("${nagad.auth.demo:false}")
    private boolean demo;
    @Value("${nagad.mail.simulate}")
    private boolean mailSimulate;

    public AuthService(AppUserRepository users, OtpService otp, MailService mail,
                       SessionStore sessions, AuditService audit, PasswordEncoder encoder) {
        this.users = users;
        this.otp = otp;
        this.mail = mail;
        this.sessions = sessions;
        this.audit = audit;
        this.encoder = encoder;
    }

    /** Single-factor sign-in: username + password issues a session directly (OTP retired). */
    public SessionResponse login(LoginRequest req) {
        AppUser u = users.findById(req.username())
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "invalid credentials"));
        if (!passwordOk(u, req.password())) {
            throw new ResponseStatusException(UNAUTHORIZED, "invalid credentials");
        }
        String token = sessions.issue(u.getUsername());
        audit.recordSelf(u.getUsername(), "login", u.getUsername(), "signed in with password");
        return new SessionResponse(token, MeMapper.of(u));
    }

    public void logout(String bearer) {
        sessions.revoke(bearer);
    }

    /**
     * Change one's own password. Used to satisfy the forced first-login change, and available
     * for self-service afterwards. The new password is verified against the current one, hashed
     * with bcrypt, and stored — so nobody but the account holder ever knows it. Clearing the
     * {@code must_change_password} flag is what lifts the first-login gate.
     */
    @Transactional
    public MeResponse changePassword(AppUser user, ChangePasswordRequest req) {
        String current = req.currentPassword();
        String next = req.newPassword() == null ? "" : req.newPassword();
        if (current == null || !encoder.matches(current, user.getPasswordHash())) {
            throw new ResponseStatusException(UNAUTHORIZED, "current password is incorrect");
        }
        if (next.length() < 8) {
            throw new ResponseStatusException(BAD_REQUEST, "new password must be at least 8 characters");
        }
        if (encoder.matches(next, user.getPasswordHash())) {
            throw new ResponseStatusException(BAD_REQUEST, "new password must differ from the current password");
        }
        user.changePassword(encoder.encode(next));
        users.save(user);
        audit.recordSelf(user.getUsername(), "password-change", user.getUsername(), "changed own password");
        return MeMapper.of(user);
    }

    private boolean passwordOk(AppUser u, String password) {
        if (password == null) return false;
        if (demo) {
            // Prototype demo rule: any password of 4+ characters is accepted.
            return password.trim().length() >= 4;
        }
        return encoder.matches(password, u.getPasswordHash());
    }
}
