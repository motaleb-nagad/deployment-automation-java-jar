package com.nagad.deploystg.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/** Resolves the authenticated {@link StgUser} (populated by the delegated auth filter). */
@Component
public class CurrentUser {

    public StgUser require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof StgUser u)) {
            throw new ResponseStatusException(UNAUTHORIZED, "not authenticated");
        }
        return u;
    }
}
