package be.enrosed.sales.application;

import be.enrosed.sales.domain.Country;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CountryDefaultsTest {

    @Test
    void containsEveryCountryOfferedByTheDashboard() {
        Set<String> dashboardCodes = Set.of(
                "BE", "NL", "DE", "FR", "LU", "GB", "IE", "ES", "PT", "IT", "AT", "CH",
                "DK", "SE", "NO", "FI", "PL", "CZ", "SK", "HU", "RO", "BG", "GR", "HR",
                "SI", "EE", "LV", "LT", "CY", "MT", "TR", "UA", "RS", "CN", "HK", "VN",
                "TH", "IN", "ID", "MY", "KR", "JP", "AE", "US", "CA", "EC", "CO", "KE",
                "ET", "MA", "EG", "ZA");
        Set<String> policyCodes = CountryDefaults.all().stream()
                .map(Country::code).collect(Collectors.toSet());

        assertEquals(dashboardCodes, policyCodes);
        assertEquals(dashboardCodes.size(), CountryDefaults.all().size());

        Map<String, Country> byCode = CountryDefaults.all().stream()
                .collect(Collectors.toMap(Country::code, Function.identity()));
        assertEquals(new BigDecimal("600"), byCode.get("BE").minOrderValue());
        assertEquals(new BigDecimal("750"), byCode.get("NL").minOrderValue());
        assertEquals(new BigDecimal("1000"), byCode.get("LU").minOrderValue());
        assertEquals(new BigDecimal("1250"), byCode.get("DE").minOrderValue());
        assertEquals(new BigDecimal("1250"), byCode.get("FR").minOrderValue());
        assertEquals(new BigDecimal("1500"), byCode.get("IT").minOrderValue());
        assertEquals(new BigDecimal("1500"), byCode.get("ES").minOrderValue());
        assertTrue(byCode.values().stream().allMatch(country -> country.handling().signum() == 0));
        assertTrue(Set.of("BE", "NL", "DE", "FR", "LU").stream()
                .allMatch(code -> byCode.get(code).transitDays() == 3));
        assertTrue(byCode.values().stream().allMatch(country ->
                country.transitDays() >= 3 && country.transitDays() <= 7));
        assertEquals(new BigDecimal("24"), byCode.get("EE").vatRatePct());
        assertEquals(new BigDecimal("25.5"), byCode.get("FI").vatRatePct());
        assertEquals(new BigDecimal("21"), byCode.get("RO").vatRatePct());
        assertEquals(new BigDecimal("23"), byCode.get("SK").vatRatePct());
        assertFalse(byCode.get("GB").euMember());
        assertTrue(byCode.get("IE").euMember());
    }

    @Test
    void rolloutRetainsExistingFreightButReplacesAdministration() {
        Country old = new Country("DE", "Duitsland", new BigDecimal("2000"),
                new BigDecimal("123"), new BigDecimal("321"), new BigDecimal("35"),
                new BigDecimal("19"), 2, true);
        Country policy = CountryDefaults.all().stream()
                .filter(country -> country.code().equals("DE")).findFirst().orElseThrow();

        Country merged = CountryDefaults.mergeForRollout(old, policy);

        assertEquals(new BigDecimal("1250"), merged.minOrderValue());
        assertEquals(new BigDecimal("123"), merged.freightPerPallet());
        assertEquals(new BigDecimal("321"), merged.minFreight());
        assertEquals(BigDecimal.ZERO, merged.handling());
        assertEquals(3, merged.transitDays());
    }
}
