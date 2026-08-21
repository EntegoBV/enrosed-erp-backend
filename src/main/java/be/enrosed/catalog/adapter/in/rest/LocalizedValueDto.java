package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.shared.Language;

/** A localized value plus the language that actually supplied it. */
public record LocalizedValueDto(Language language, String value) {}
