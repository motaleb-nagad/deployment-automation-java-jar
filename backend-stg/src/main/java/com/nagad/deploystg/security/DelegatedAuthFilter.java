package com.nagad.deploystg.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nagad.deploystg.dto.Dtos.MeResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Delegated authentication. The staging service holds no session store of its own; it validates
 * the bearer token by calling the main console backend's {@code GET /api/auth/me} (forwarding the
 * {@code Authorization} header) and reads the caller's r/w/x permissions from the response. A
 * short per-token cache avoids a round-trip on every request. This keeps a single login for both
 * services without sharing session state.
 */
@Component
public class DelegatedAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DelegatedAuthFilter.class);
    private static final long CACHE_TTL_MS = 10_000;

    private final String meUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(MeResponse me, long expiresAt) {}

    public DelegatedAuthFilter(@Value("${nagad.main-backend.base-url}") String baseUrl) {
        this.meUrl = baseUrl.replaceAll("/+$", "") + "/api/auth/me";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            MeResponse me = resolve(token);
            // A user still owing a first-login password change is not yet authorised anywhere.
            if (me != null && me.username() != null && !me.mustChangePassword()) {
                var auth = new UsernamePasswordAuthenticationToken(
                        new StgUser(me.username(), me.r(), me.w(), me.x()), null, authorities(me));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(req, res);
    }

    private MeResponse resolve(String token) {
        Cached c = cache.get(token);
        long now = System.currentTimeMillis();
        if (c != null && c.expiresAt() > now) return c.me();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(meUrl))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + token)
                    .GET().build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                MeResponse me = json.readValue(resp.body(), MeResponse.class);
                cache.put(token, new Cached(me, now + CACHE_TTL_MS));
                return me;
            }
            cache.remove(token);
            return null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("delegated auth to {} failed: {}", meUrl, e.toString());
            return null;
        }
    }

    private static List<SimpleGrantedAuthority> authorities(MeResponse me) {
        List<SimpleGrantedAuthority> list = new ArrayList<>();
        if (me.role() != null) list.add(new SimpleGrantedAuthority("ROLE_" + me.role().toUpperCase()));
        if (me.r()) list.add(new SimpleGrantedAuthority("PERM_R"));
        if (me.w()) list.add(new SimpleGrantedAuthority("PERM_W"));
        if (me.x()) list.add(new SimpleGrantedAuthority("PERM_X"));
        return list;
    }
}
