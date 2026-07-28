package com.nagad.deploy.web;

import com.nagad.deploy.dto.Dtos.*;
import com.nagad.deploy.security.CurrentUser;
import com.nagad.deploy.service.AuthService;
import com.nagad.deploy.service.MeMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final CurrentUser current;

    public AuthController(AuthService auth, CurrentUser current) {
        this.auth = auth;
        this.current = current;
    }

    /** Single-factor sign-in: username + password issues a bearer session token. */
    @PostMapping("/login")
    public SessionResponse login(@RequestBody LoginRequest req) {
        return auth.login(req);
    }

    /**
     * Forgot-password: mail a one-time temporary password to the account's address. Public
     * (pre-auth) and existence-agnostic — the response is the same whether or not the account
     * exists, so it can't enumerate users.
     */
    @PostMapping("/forgot-password")
    public ForgotPasswordResponse forgotPassword(@RequestBody ForgotPasswordRequest req) {
        return auth.forgotPassword(req);
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        auth.logout(header != null && header.startsWith("Bearer ") ? header.substring(7) : null);
    }

    @GetMapping("/me")
    public MeResponse me() {
        return MeMapper.of(current.require());
    }

    /**
     * Change one's own password. Required on first sign-in (the account is gated until it
     * succeeds) and available for self-service afterwards. Returns the refreshed profile,
     * with {@code mustChangePassword} cleared once the change goes through.
     */
    @PostMapping("/change-password")
    public MeResponse changePassword(@RequestBody ChangePasswordRequest req) {
        return auth.changePassword(current.require(), req);
    }
}
