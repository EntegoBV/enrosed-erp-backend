package be.enrosed.catalog.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.List;

/** Closed contract of homepage sections understood by both dashboard and website. */
public enum HomepageSectionKey {
    HERO("hero", true),
    RANGE("range", true),
    ORDER("order", true),
    COUNTER("counter", true),
    FLOWERBOX("flowerbox", true),
    SOAP("soap", false),
    OCCASION("occasion", false),
    RETAIL("retail", true),
    FAQ("faq", true),
    CATALOG("catalog", false),
    QUOTE("quote", true);

    private static final List<HomepageSectionKey> DEFAULT_ORDER = List.of(values());
    private final String key;
    private final boolean enabledByDefault;

    HomepageSectionKey(String key, boolean enabledByDefault) {
        this.key = key;
        this.enabledByDefault = enabledByDefault;
    }

    @JsonValue
    public String key() {
        return key;
    }

    @JsonCreator
    public static HomepageSectionKey fromKey(String key) {
        return Arrays.stream(values())
                .filter(value -> value.key.equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Onbekende homepage-sectie '" + key + "'; toegestaan: "
                                + String.join(", ", DEFAULT_ORDER.stream()
                                        .map(HomepageSectionKey::key).toList())));
    }

    public static List<HomepageSectionKey> defaultOrder() {
        return DEFAULT_ORDER;
    }

    public boolean enabledByDefault() {
        return enabledByDefault;
    }
}
