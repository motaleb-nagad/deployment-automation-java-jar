package com.nagad.deploy.service;

import com.nagad.deploy.domain.AppUser;
import com.nagad.deploy.dto.Dtos.*;
import com.nagad.deploy.repo.AppUserRepository;
import com.nagad.deploy.security.SessionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    @Value("${nagad.auth.demo:true}")
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

    public LoginResponse login(LoginRequest req) {
        AppUser u = users.findById(req.username())
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "invalid credentials"));
        if (!passwordOk(u, req.password())) {
            throw new ResponseStatusException(UNAUTHORIZED, "invalid credentials");
        }
        String code = otp.issue(u.getUsername());
        mail.send(u.getEmail(), "Nagad Deploy Console — your sign-in code",
                "Your one-time code is " + code + ". It expires in 5 minutes.");

        byte[] buf = new byte[18];
        random.nextBytes(buf);
        String step1 = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        pending.put(step1, u.getUsername());

        // In demo/simulated-mail mode the code is echoed to the UI so there's nothing to receive.
        String demoCode = mailSimulate ? code : null;
        return new LoginResponse(true, OtpService.maskEmail(u.getEmail()), step1, demoCode);
    }

    public SessionResponse verify(VerifyRequest req) {
        String username = pending.get(req.step1Token());
        if (username == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "login session expired — start again");
        }
        if (!otp.verify(username, req.code())) {
            throw new ResponseStatusException(UNAUTHORIZED, "incorrect or expired code");
        }
        pending.remove(req.step1Token());
        AppUser u = users.findById(username)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "unknown account"));
        String token = sessions.issue(username);
        audit.recordSelf(username, "login", username, "signed in with password + OTP");
        return new SessionResponse(token, MeMapper.of(u));
    }

    public void logout(String bearer) {
        sessions.revoke(bearer);
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
