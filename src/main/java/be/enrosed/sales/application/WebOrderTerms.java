package be.enrosed.sales.application;

import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.shared.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Price, quantity and freight of a document as one fingerprint: what a
 * website order is compared by when it is invoiced, sent and approved.
 *
 * Goods lines, freight, the extra lines and the VAT rate fix the total, so
 * the total and the VAT amount stay out. That makes a slotfactuur compare
 * equal to the order: its advance deductions are extra lines with a negative
 * total, and adding the deducted amount back cancels exactly those. Delivery
 * weeks, notes, payment terms and internal notes never enter.
 */
public final class WebOrderTerms {
    private static final int MAX_DIFFERENCES = 8;
    private static final Locale DUTCH = Locale.forLanguageTag("nl-BE");

    private WebOrderTerms() {}

    public static String of(PricedOrder priced) {
        return of(priced, BigDecimal.ZERO);
    }

    /** @param deductedAdvancesExcl what a slotfactuur deducts for advance invoices, excl. VAT; zero elsewhere */
    public static String of(PricedOrder priced, BigDecimal deductedAdvancesExcl) {
        StringBuilder canonical = new StringBuilder();
        priced.lines().stream()
                .sorted(Comparator.comparing(PricedOrder.Line::productId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .forEach(line -> canonical.append(line.productId()).append(':').append(line.quantity()).append(':')
                        .append(plain(line.unitPrice())).append(':').append(plain(line.discountPct())).append(':')
                        .append(plain(line.net())).append(';'));
        canonical.append('|').append(plain(priced.totals().shippingTotal()))
                .append('|').append(plain(otherExtras(priced, deductedAdvancesExcl)))
                .append('|').append(plain(priced.totals().vatRatePct()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** What differs between the order as placed and the document now, in Dutch for staff; at most eight lines. */
    public static List<String> differences(WebOrderSnapshot snapshot, PricedOrder priced) {
        return differences(snapshot, priced, BigDecimal.ZERO);
    }

    static List<String> differences(WebOrderSnapshot snapshot, PricedOrder priced, BigDecimal deductedAdvancesExcl) {
        if (snapshot == null) return List.of();
        List<String> found = new ArrayList<>();
        Map<Long, PricedOrder.Line> now = new LinkedHashMap<>();
        for (PricedOrder.Line line : priced.lines()) now.putIfAbsent(line.productId(), line);
        for (WebOrderSnapshot.Line ordered : snapshot.lines()) {
            PricedOrder.Line current = now.remove(ordered.productId());
            if (current == null) {
                found.add("Product verwijderd: " + ordered.sku());
                continue;
            }
            if (ordered.quantity() != current.quantity())
                found.add("Aantal " + ordered.sku() + ": besteld " + ordered.quantity() + ", nu " + current.quantity());
            if (differs(ordered.unitPrice(), current.unitPrice()))
                found.add("Prijs " + ordered.sku() + ": besteld " + euro(ordered.unitPrice()) + ", nu " + euro(current.unitPrice()));
            if (differs(ordered.discountPct(), current.discountPct()))
                found.add("Korting " + ordered.sku() + ": besteld " + percent(ordered.discountPct())
                        + ", nu " + percent(current.discountPct()));
        }
        for (PricedOrder.Line added : now.values()) found.add("Product toegevoegd: " + added.sku());

        WebOrderSnapshot.Totals ordered = snapshot.totals();
        BigDecimal shipping = priced.totals().shippingTotal();
        if (ordered == null || ordered.shipping() == null) {
            if (ordered == null || !"PICKUP".equals(ordered.shippingStatus()) || Money.nz(shipping).signum() != 0)
                found.add("Vracht: besteld nog te bevestigen, nu " + euro(shipping));
        } else if (differs(ordered.shipping(), shipping)) {
            found.add("Vracht: besteld " + euro(ordered.shipping()) + ", nu " + euro(shipping));
        }
        BigDecimal extras = otherExtras(priced, deductedAdvancesExcl);
        if (Money.money(extras).signum() != 0) found.add("Extra regels: niet besteld, nu " + euro(extras));
        if (ordered != null && differs(ordered.vatRatePct(), priced.totals().vatRatePct()))
            found.add("Btw-tarief: besteld " + percent(ordered.vatRatePct()) + ", nu " + percent(priced.totals().vatRatePct()));

        /* The total closes the list and is never the line that falls off. */
        BigDecimal total = Money.nz(priced.totals().total()).add(Money.nz(deductedAdvancesExcl));
        String totals = ordered == null || ordered.totalExclVat() == null || !differs(ordered.totalExclVat(), total) ? null
                : "Totaal excl. btw: besteld " + euro(ordered.totalExclVat()) + ", nu " + euro(total);
        int room = totals == null ? MAX_DIFFERENCES : MAX_DIFFERENCES - 1;
        List<String> result = new ArrayList<>(found.subList(0, Math.min(room, found.size())));
        if (totals != null) result.add(totals);
        return List.copyOf(result);
    }

    /** An amount as staff read it: € 1.234,56. */
    static String euro(BigDecimal amount) {
        return "€ " + String.format(DUTCH, "%,.2f", Money.money(amount));
    }

    private static String percent(BigDecimal value) {
        String number = Money.money(value).stripTrailingZeros().toPlainString().replace('.', ',');
        return number + " %";
    }

    /** The free lines plus what a slotfactuur deducted: zero for a document that only holds the order. */
    private static BigDecimal otherExtras(PricedOrder priced, BigDecimal deductedAdvancesExcl) {
        BigDecimal extras = priced.extraLines().stream().map(line -> Money.nz(line.total()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return extras.add(Money.nz(deductedAdvancesExcl));
    }

    private static boolean differs(BigDecimal ordered, BigDecimal current) {
        return Money.money(ordered).compareTo(Money.money(current)) != 0;
    }

    private static String plain(BigDecimal value) {
        return Money.nz(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
