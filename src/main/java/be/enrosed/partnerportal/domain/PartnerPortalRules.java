package be.enrosed.partnerportal.domain;

import be.enrosed.shared.BusinessRuleException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Pattern;

/** Fixed rules of the partner portal: defaults, the login name and password rules, the dates. */
public final class PartnerPortalRules {

    /** The owner's defaults (2026-11-10): auction commission 4,5 %, transport 5,5 %. */
    public static final BigDecimal DEFAULT_AUCTION_COMMISSION_PCT = new BigDecimal("4.50");
    public static final BigDecimal DEFAULT_AUCTION_TRANSPORT_PCT = new BigDecimal("5.50");
    /** The highest cost percent staff can set for the auction. */
    public static final BigDecimal MAX_SETTING_PCT = new BigDecimal("50");

    public static final ZoneId ZONE = ZoneId.of("Europe/Brussels");

    public static final String CHANNEL_AUCTION = "AUCTION";
    public static final String CHANNEL_DIRECT = "DIRECT";

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_PARTLY_INVOICED = "PARTLY_INVOICED";
    public static final String STATUS_INVOICED = "INVOICED";
    public static final String STATUS_VOIDED = "VOIDED";

    private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,64}");
    private static final int MIN_PASSWORD = 10;
    private static final int MAX_PASSWORD = 72;
    private static final int MAX_DISPLAY_NAME = 100;

    private PartnerPortalRules() {}

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** "VV-12": how a melding is named everywhere. */
    public static String reportNumber(long reportId) {
        return "VV-" + reportId;
    }

    /** The username as stored: trimmed and lower case; refused unless 3..64 of a-z 0-9 . _ - */
    public static String username(String typed) {
        String canonical = typed == null ? "" : typed.strip().toLowerCase(java.util.Locale.ROOT);
        if (!USERNAME.matcher(canonical).matches()) {
            throw new BusinessRuleException("Een gebruikersnaam heeft 3 tot 64 tekens: kleine letters, cijfers, punt, streepje of liggend streepje");
        }
        return canonical;
    }

    /** The name the portal greets with ("Frans"); required, at most 100 characters. */
    public static String displayName(String typed) {
        String name = typed == null ? "" : typed.strip();
        if (name.isEmpty()) throw new BusinessRuleException("Vul de naam in van wie zich aanmeldt");
        if (name.length() > MAX_DISPLAY_NAME) throw new BusinessRuleException("Een naam heeft hoogstens 100 tekens");
        return name;
    }

    /**
     * A password staff type for a partner: 10..72 characters of printable ASCII only. The browser
     * builds the Basic header with btoa, which cannot carry accents or the euro sign.
     */
    public static void requireValidPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new BusinessRuleException("Een wachtwoord heeft minstens 10 tekens");
        }
        if (password.length() > MAX_PASSWORD) {
            throw new BusinessRuleException("Een wachtwoord heeft hoogstens 72 tekens");
        }
        for (int index = 0; index < password.length(); index++) {
            char character = password.charAt(index);
            if (character < 0x20 || character > 0x7E) {
                throw new BusinessRuleException("Gebruik letters zonder accenten, cijfers en leestekens");
            }
        }
    }

    /** A cost percent staff set: null keeps the default, else 0..50 with at most 4 decimals. */
    public static BigDecimal settingPct(BigDecimal value) {
        if (value == null) return null;
        if (value.signum() < 0 || value.compareTo(MAX_SETTING_PCT) > 0) {
            throw new BusinessRuleException("Een percentage ligt tussen 0 en 50");
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    public static BigDecimal commissionOrDefault(BigDecimal stored) {
        return stored != null ? stored : DEFAULT_AUCTION_COMMISSION_PCT;
    }

    public static BigDecimal transportOrDefault(BigDecimal stored) {
        return stored != null ? stored : DEFAULT_AUCTION_TRANSPORT_PCT;
    }
}
