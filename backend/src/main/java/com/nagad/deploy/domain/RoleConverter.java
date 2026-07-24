package com.nagad.deploy.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Persists {@link Role} as its lowercase wire form (superadmin / operator / viewer). */
@Converter(autoApply = true)
public class RoleConverter implements AttributeConverter<Role, String> {
    @Override
    public String convertToDatabaseColumn(Role role) {
        return role == null ? null : role.wire();
    }

    @Override
    public Role convertToEntityAttribute(String s) {
        return s == null ? null : Role.of(s);
    }
}
