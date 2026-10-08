package be.enrosed.inventory.application;

import be.enrosed.inventory.domain.PayeeLabels;
import be.enrosed.sourcing.domain.LotCost;
import be.enrosed.sourcing.domain.PurchaseReconciliation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What stands between a concept and "Definitief maken", and what the user
 * and the accountant should look at: the blockers and the warnings of one
 * compute. Every notice names the step of the screen in which it is solved.
 * They are stored on the closing, so a final closing keeps the ones it was
 * frozen with.
 */
public final class ClosingNotices {

    public static final String BLOCKER = "BLOCKER";
    public static final String WARNING = "WARNING";

    public static final String SEGMENT_COUNT = "tellen";
    public static final String SEGMENT_DATE = "datum";
    public static final String SEGMENT_VALUE = "waarde";
    public static final String SEGMENT_SEPARATE = "apart";
    public static final String SEGMENT_FINALIZE = "afsluiten";

    private static final List<String> SEGMENTS = List.of(SEGMENT_COUNT, SEGMENT_DATE, SEGMENT_VALUE, SEGMENT_SEPARATE,
            SEGMENT_FINALIZE);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One blocker or warning; the ids say which row of the screen it belongs to. */
    public record Notice(String code, String severity, String segment, String message, Long productId,
                         Long purchaseOrderId, Long locationId, Long salesOrderId, Long movementId, String payee,
                         BigDecimal amountEur) {

        public boolean blocker() {
            return BLOCKER.equals(severity);
        }

        Notice product(Long id) {
            return new Notice(code, severity, segment, message, id, purchaseOrderId, locationId, salesOrderId, movementId, payee, amountEur);
        }

        Notice container(Long id) {
            return new Notice(code, severity, segment, message, productId, id, locationId, salesOrderId, movementId, payee, amountEur);
        }

        Notice location(Long id) {
            return new Notice(code, severity, segment, message, productId, purchaseOrderId, id, salesOrderId, movementId, payee, amountEur);
        }

        Notice invoice(Long id) {
            return new Notice(code, severity, segment, message, productId, purchaseOrderId, locationId, id, movementId, payee, amountEur);
        }

        Notice stream(String payeeName, BigDecimal amount) {
            return new Notice(code, severity, segment, message, productId, purchaseOrderId, locationId, salesOrderId, movementId,
                    payeeName, amount);
        }
    }

    private final List<Notice> notices = new ArrayList<>();

    /* ------------------------------------------------------------- collecting */

    public Notice block(String code, String segment, String message) {
        return add(new Notice(code, BLOCKER, segment, message, null, null, null, null, null, null, null));
    }

    public Notice warn(String code, String segment, String message) {
        return add(new Notice(code, WARNING, segment, message, null, null, null, null, null, null, null));
    }

    /** Adds a notice built with the id helpers of {@link Notice}; returns it for chaining. */
    public Notice add(Notice notice) {
        notices.add(notice);
        return notice;
    }

    private void replaceLast(Notice notice) {
        notices.set(notices.size() - 1, notice);
    }

    /** Blockers first, then by step, each group in the order it was raised. */
    public List<Notice> list() {
        List<Notice> sorted = new ArrayList<>(notices);
        sorted.sort(Comparator.comparing((Notice notice) -> !notice.blocker())
                .thenComparing(notice -> SEGMENTS.indexOf(notice.segment())));
        return sorted;
    }

    /* -------------------------------------------------------------- containers */

    /**
     * Everything one valued container can raise (roles EIGEN, PARTNER and ONDERWEG, never VORIG).
     *
     * @param supplierBilledDecided whether the user stated which pieces the supplier charged
     * @param normalisedPaid        per payee what was paid at the container rates: the measure of a lower settlement
     */
    public void container(long purchaseOrderId, String name, LotCost.Container cost, boolean supplierBilledDecided,
                          Map<String, BigDecimal> normalisedPaid) {
        if (cost.paymentWithoutEuro()) {
            replaceLast(block("BETALING_ZONDER_EURO", SEGMENT_VALUE,
                    "Container " + name + ": een betaling mist haar eurowaarde.").container(purchaseOrderId));
        }
        for (LotCost.StreamCost stream : cost.streams()) {
            String label = PayeeLabels.of(stream.payee().name());
            if (stream.accrualStale()) {
                replaceLast(block("GESCHAT_BEDRAG_VEROUDERD", SEGMENT_VALUE, "Container " + name + ", " + label
                        + ": het open bedrag is gewijzigd sinds het werd bevestigd. Bevestig het opnieuw.")
                        .container(purchaseOrderId).stream(stream.payee().name(), null));
            }
            if (stream.overpaidEur() != null && stream.overpaidEur().signum() > 0) {
                replaceLast(warn("MEER_BETAALD", SEGMENT_VALUE, "Container " + name + ", " + label + ": € "
                        + euro(stream.overpaidEur()) + " meer betaald dan de Afspraak; volledig opgenomen.")
                        .container(purchaseOrderId).stream(stream.payee().name(), money(stream.overpaidEur())));
            }
            if (PurchaseReconciliation.Status.SETTLED_LOWER.name().equals(stream.status()) && !stream.accrualApplied()) {
                BigDecimal paid = normalisedPaid.getOrDefault(stream.payee().name(), stream.paidEur());
                BigDecimal less = money(stream.plannedEur().subtract(paid).max(BigDecimal.ZERO));
                replaceLast(warn("LAGER_AFGEREKEND", SEGMENT_VALUE, "Container " + name + ", " + label + ": € " + euro(less)
                        + " minder betaald dan de Afspraak en vereffend; het lagere bedrag is als aanschafwaarde opgenomen.")
                        .container(purchaseOrderId).stream(stream.payee().name(), less));
            }
        }
        if (cost.supplierSettledLower() && cost.hasShortage() && !supplierBilledDecided) {
            replaceLast(block("LEVERANCIER_LAGER_AFGEREKEND", SEGMENT_VALUE, "Container " + name
                    + ": de leverancier is lager afgerekend en er zijn stuks te weinig geleverd."
                    + " Geef aan welke stuks de leverancier aanrekende.").container(purchaseOrderId));
        }
        boolean aboveLoss = false;
        for (LotCost.CreditUse credit : cost.credits()) {
            if (LotCost.CreditUse.NO_KIND.equals(credit.requiredBy())) {
                replaceLast(block("TEGOED_ZONDER_SOORT", SEGMENT_VALUE, "Container " + name + ": tegoed leverancier van € "
                        + euro(credit.countedEur()) + " zonder soort. Geef aan of het de aanschafwaarde verlaagt of buiten"
                        + " de voorraadwaarde blijft.").container(purchaseOrderId).stream(null, money(credit.countedEur())));
            } else if (LotCost.CreditUse.SETTLED_LOWER.equals(credit.requiredBy())) {
                replaceLast(block("TEGOED_EN_LAGER_AFGEREKEND", SEGMENT_VALUE, "Container " + name
                        + ": de leverancier is lager afgerekend en er staat een prijstegoed van € " + euro(credit.countedEur())
                        + ". Geef aan of dat tegoed al van de betaling is afgetrokken.")
                        .container(purchaseOrderId).stream(null, money(credit.countedEur())));
            } else if (LotCost.CreditUse.ABOVE_LOSS.equals(credit.requiredBy())) {
                aboveLoss = true;
            }
        }
        if (aboveLoss) {
            replaceLast(block("TEGOED_MEER_DAN_VERLIES", SEGMENT_VALUE, "Container " + name + ": tegoed voor tekort of schade € "
                    + euro(cost.defaultLossCreditEur()) + ", terwijl de ontbrekende en beschadigde stuks samen € "
                    + euro(cost.missingAndDamagedCostEur()) + " kostten. Geef per tegoed aan wat het is.")
                    .container(purchaseOrderId).stream(null, money(cost.defaultLossCreditEur())));
        } else if (cost.lossCreditEur() != null && cost.lossCreditEur().signum() > 0) {
            replaceLast(warn("TEGOED_BUITEN_WAARDE", SEGMENT_VALUE, "Container " + name
                    + ": tegoed leverancier voor tekort of schade € " + euro(cost.lossCreditEur())
                    + " staat buiten de voorraadwaarde. Is het een korting op stuks die er liggen, geef dat dan aan bij het tegoed.")
                    .container(purchaseOrderId).stream(null, money(cost.lossCreditEur())));
        }
        for (LotCost.Lot lot : cost.lots()) {
            if (lot.status() == LotCost.LotStatus.GEEN_PRIJS) {
                replaceLast(block("GEEN_PRIJS", SEGMENT_VALUE, "Partij zonder inkoopprijs: container " + name + ", "
                        + lot.productName() + ". Vul de inkoopprijs aan op de container.")
                        .container(purchaseOrderId).product(lot.productId()));
            } else if (lot.status() == LotCost.LotStatus.MEER_ONTVANGEN) {
                replaceLast(warn("MEER_ONTVANGEN", SEGMENT_VALUE, "Container " + name + ", " + lot.productName()
                        + ": meer ontvangen dan besteld. Rekent de leverancier de extra stuks nog aan, pas dan de Afspraak aan.")
                        .container(purchaseOrderId).product(lot.productId()));
            }
        }
    }

    /** A blocker or warning about one container, with its id. */
    public void forContainer(boolean blocker, String code, String segment, long purchaseOrderId, String message) {
        replaceLast((blocker ? block(code, segment, message) : warn(code, segment, message)).container(purchaseOrderId));
    }

    public void forProduct(boolean blocker, String code, String segment, long productId, Long locationId, String message) {
        replaceLast((blocker ? block(code, segment, message) : warn(code, segment, message)).product(productId).location(locationId));
    }

    public void forLocation(String code, long locationId, String message) {
        replaceLast(block(code, SEGMENT_COUNT, message).location(locationId));
    }

    public void forInvoice(String code, long salesOrderId, String message) {
        replaceLast(block(code, SEGMENT_SEPARATE, message).invoice(salesOrderId));
    }

    /** A warning that names an amount without a container or stream. */
    public void amount(String code, String segment, BigDecimal amount, String message) {
        replaceLast(warn(code, segment, message).stream(null, money(amount)));
    }

    /* ------------------------------------------------------------------ texts */

    /** An amount as the Belgian screens and files print it: 1.234,56. */
    public static String euro(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        symbols.setMinusSign('-');
        DecimalFormat format = new DecimalFormat("#,##0.00", symbols);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(amount == null ? BigDecimal.ZERO : amount);
    }

    public static String day(LocalDate date) {
        return date == null ? "" : DAY.format(date);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    /* ------------------------------------------------------------------- json */

    /** The notices as the closing row stores them: one object each, keys in a fixed order, absent ids left out. */
    public static String toJson(List<Notice> notices) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Notice notice : notices) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", notice.code());
            row.put("severity", notice.severity());
            row.put("segment", notice.segment());
            row.put("message", notice.message());
            if (notice.productId() != null) row.put("productId", notice.productId());
            if (notice.purchaseOrderId() != null) row.put("purchaseOrderId", notice.purchaseOrderId());
            if (notice.locationId() != null) row.put("locationId", notice.locationId());
            if (notice.salesOrderId() != null) row.put("salesOrderId", notice.salesOrderId());
            if (notice.movementId() != null) row.put("movementId", notice.movementId());
            if (notice.payee() != null) row.put("payee", notice.payee());
            if (notice.amountEur() != null) row.put("amountEur", notice.amountEur().toPlainString());
            rows.add(row);
        }
        try {
            return JSON.writeValueAsString(rows);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static List<Notice> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> rows = JSON.readValue(json, new TypeReference<>() {});
            List<Notice> notices = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                notices.add(new Notice(text(row.get("code")), text(row.get("severity")), text(row.get("segment")),
                        text(row.get("message")), number(row.get("productId")), number(row.get("purchaseOrderId")),
                        number(row.get("locationId")), number(row.get("salesOrderId")), number(row.get("movementId")),
                        text(row.get("payee")), row.get("amountEur") == null ? null : new BigDecimal(row.get("amountEur").toString())));
            }
            return notices;
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Long number(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
