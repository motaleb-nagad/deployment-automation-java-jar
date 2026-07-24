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

    /** Step 1 — password. On success an OTP is emailed and factor 2 begins. */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req) {
        return auth.login(req);
    }

    /** Step 2 — 6-digit OTP. On success a bearer session token is issued. */
    @PostMapping("/verify")
    public SessionResponse verify(@RequestBody VerifyRequest req) {
        return auth.verify(req);
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
}
