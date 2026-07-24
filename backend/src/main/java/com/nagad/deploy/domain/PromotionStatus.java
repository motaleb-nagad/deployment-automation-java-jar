package com.nagad.deploy.domain;

/** Lifecycle of a jar promotion request. */
public enum PromotionStatus {
    PENDING,
    APPROVED,
    DENIED,
    DEPLOYED;

    public String wire() {
        return name().toLowerCase();
    }
}
