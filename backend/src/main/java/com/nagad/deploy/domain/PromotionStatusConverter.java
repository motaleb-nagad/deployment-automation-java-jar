package com.nagad.deploy.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Persists {@link PromotionStatus} as its lowercase wire form. */
@Converter(autoApply = true)
public class PromotionStatusConverter implements AttributeConverter<PromotionStatus, String> {
    @Override
    public String convertToDatabaseColumn(PromotionStatus s) {
        return s == null ? null : s.wire();
    }

    @Override
    public PromotionStatus convertToEntityAttribute(String s) {
        return s == null ? null : PromotionStatus.valueOf(s.toUpperCase());
    }
}
