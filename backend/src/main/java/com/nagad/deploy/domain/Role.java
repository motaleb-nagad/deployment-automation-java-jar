package com.nagad.deploy.domain;

/** RBAC role. Only {@code SUPERADMIN} may approve or deny promotions and manage users. */
public enum Role {
    SUPERADMIN,
    OPERATOR,
    VIEWER;

    public static Role of(String s) {
        return switch (s.toLowerCase()) {
            case "superadmin" -> SUPERADMIN;
            case "operator" -> OPERATOR;
            default -> VIEWER;
        };
    }

    public String wire() {
        return name().toLowerCase();
    }
}
