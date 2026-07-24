package com.nagad.deploy.security;

import com.nagad.deploy.domain.AppUser;
import com.nagad.deploy.repo.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Reads {@code Authorization: Bearer <token>}, resolves the session, and populates RBAC authorities. */
public class TokenAuthFilter extends OncePerRequestFilter {

    private final SessionStore sessions;
    private final AppUserRepository users;

    public TokenAuthFilter(SessionStore sessions, AppUserRepository users) {
        this.sessions = sessions;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String token = null;
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7);
        }
        if (token != null) {
            String username = sessions.resolve(token);
            if (username != null) {
                users.findById(username).ifPresent(u -> {
                    var auth = new UsernamePasswordAuthenticationToken(u.getUsername(), null, authorities(u));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
            }
        }
        chain.doFilter(req, res);
    }

    private static List<SimpleGrantedAuthority> authorities(AppUser u) {
        List<SimpleGrantedAuthority> list = new ArrayList<>();
        list.add(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()));
        if (u.isPermR()) list.add(new SimpleGrantedAuthority("PERM_R"));
        if (u.isPermW()) list.add(new SimpleGrantedAuthority("PERM_W"));
        if (u.isPermX()) list.add(new SimpleGrantedAuthority("PERM_X"));
        return list;
    }
}
