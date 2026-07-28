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
