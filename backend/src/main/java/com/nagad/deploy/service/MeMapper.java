package com.nagad.deploy.service;

import com.nagad.deploy.domain.AppUser;
import com.nagad.deploy.dto.Dtos.MeResponse;

public final class MeMapper {
    private MeMapper() {}

    public static MeResponse of(AppUser u) {
        return new MeResponse(u.getUsername(), u.getName(), u.getEmail(), u.getRole().wire(),
                u.getScope(), u.isPermR(), u.isPermW(), u.isPermX(), u.perms(),
                u.isMustChangePassword());
    }
}
