package com.nagad.deploy.security;

import com.nagad.deploy.domain.AppUser;
import com.nagad.deploy.repo.AppUserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/** Resolves the authenticated {@link AppUser} for the current request. */
@Component
public class CurrentUser {

    private final AppUserRepository users;

    public CurrentUser(AppUserRepository users) {
        this.users = users;
    }

    public AppUser require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "not authenticated");
        }
        return users.findById(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "unknown principal"));
    }
}
