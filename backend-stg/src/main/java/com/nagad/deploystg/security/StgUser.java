package com.nagad.deploystg.security;

/** The authenticated caller, resolved from the main backend's /api/auth/me (delegated auth). */
public record StgUser(String username, boolean r, boolean w, boolean x) {}
