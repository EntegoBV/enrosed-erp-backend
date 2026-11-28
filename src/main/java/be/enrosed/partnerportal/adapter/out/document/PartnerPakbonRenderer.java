package be.enrosed.partnerportal.adapter.out.document;

import be.enrosed.catalog.adapter.out.document.CatalogPdfFonts;
import be.enrosed.catalog.adapter.out.document.PdfImageEncoder;
import be.enrosed.catalog.application.SalesPhotoService;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.partnerportal.application.PartnerContainerViews;
import be.enrosed.shared.Brand;
import be.enrosed.shared.company.CompanyProfileService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.awt.Color;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The partner's pakbon of one container (GET /api/partner-portal/containers/{id}/pakbon.pdf): the packing list of a
 * purchase order to tick off when it is unloaded, A4 portrait in the family of the purchase order PDF (white cards,
 * Jost for figures and names, Cormorant for the titles).
 *
 * <p>Price-free by construction: it reads only the partner's own container view ({@link PartnerContainerViews}, which
 * carries no price, cost, amount, supplier, note or alias), the product's sales photo (the one the portal shows) and
 * Enrosed's letterhead. Per product: name, colour and size, code, the product's own size and packaging, the omdoos,
 * and the figures (count in sales units with the pieces, cartons, m³, ± pallets, weight).</p>
 */
@ApplicationScoped
public class PartnerPakbonRenderer {

    private static final Logger LOG = Logger.getLogger(PartnerPakbonRenderer.class);
    private static final Locale NL = Locale.forLanguageTag("nl-BE");
    private static final String[] WEEKDAYS = {"ma", "di", "wo", "do", "vr", "za", "zo"};
    private static final String[] MONTHS = {"jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec"};
    /** The photo's print canvas: 15 mm at about 400 dpi, white around a photo of another shape. */
    private static final int PHOTO_PX = 240;

    /** The PDF and its file name ("pakbon-PO-2026-011.pdf"). */
    public record Document(byte[] content, String filename) {}

    /** One fact of the masthead: a small label over a value, an optional line below it. */
    public record Fact(String label, String value, String sub, boolean muted) {}

    /** One figure of the totals box. */
    public record Total(String label, String value) {}

    /**
     * One product line. title is the variant where every product of its group shares one name (the group's head
     * says it once), else the product's name; detail is the variant beside such a name.
     */
    public record Line(int position, String title, String detail, String photo, List<String> facts, String carton, String quantity, String unit, String pieces, String ordered, String cartons,
                       String cbm, String pallets, String weight) {}

    /** Category · family, its lines in the catalogue's order. */
    public record Group(String label, int lines, List<Line> rows) {}

    /** Everything the template prints, as text. */
    public record View(String number, String stateWord, List<Fact> facts, List<Total> totals, List<Group> groups,
                       boolean empty, String made, boolean estimate) {}

    private final Template template;
    private final Brand brand;
    private final CompanyProfileService company;
    private final CatalogPdfFonts fonts;
    private final ProductRepository products;
    private final SalesPhotoService photos;
    private final PdfImageEncoder images;

    public PartnerPakbonRenderer(@Location("partner-pakbon.html") Template template, Brand brand,
                                 CompanyProfileService company, CatalogPdfFonts fonts, ProductRepository products,
                                 SalesPhotoService photos, PdfImageEncoder images) {
        this.template = template;
        this.brand = brand;
        this.company = company;
        this.fonts = fonts;
        this.products = products;
        this.photos = photos;
        this.images = images;
    }

    /** The pakbon of the one container in this page ({@code PartnerContainerReader.one}). */
    public Document render(PartnerContainerViews.ContainerPage page) {
        PartnerContainerViews.Container container = page.containers().getFirst();
        View view = view(page, container);
        String html = template.data("pakbon", view)
                .data("logo", brand.logoDataUri())
                .data("company", company.get())
                .render();
        String number = container.name() == null ? "container-" + container.id() : container.name();
        return new Document(fonts.render(html), "pakbon-" + number.replaceAll("[^A-Za-z0-9._-]+", "-") + ".pdf");
    }

    /* ------------------------------------------------------------------ the view */

    View view(PartnerContainerViews.ContainerPage page, PartnerContainerViews.Container container) {
        LocalDate today = page.serverDate();
        boolean received = "ONTVANGEN".equals(container.status());
        Map<Long, PartnerContainerViews.ProductSpec> specs = new HashMap<>();
        for (PartnerContainerViews.ProductSpec spec : page.products()) specs.put(spec.tile().productId(), spec);

        List<Fact> facts = new ArrayList<>();
        facts.add(new Fact("Container", orDash(container.containerType()),
                container.containerLabel() != null && !container.containerLabel().equals(container.containerType())
                        ? container.containerLabel() : null, container.containerType() == null));
        facts.add(new Fact("Besteld", container.orderDate() == null ? "—" : day(container.orderDate()), null,
                container.orderDate() == null));
        facts.add(new Fact("Vertrokken", container.shippedOn() == null ? "nog niet" : day(container.shippedOn()),
                container.departurePort(), container.shippedOn() == null));
        boolean estimate = false;
        if (received) {
            facts.add(new Fact("Aankomst haven", container.expectedArrival() == null ? "—" : day(container.expectedArrival()),
                    container.destinationPort(), true));
            facts.add(new Fact("Aangekomen", container.receivedOn() == null ? "ja" : day(container.receivedOn()),
                    container.locationName(), false));
        } else {
            if (container.portArrivedOn() != null) {
                facts.add(new Fact("Aankomst haven", day(container.portArrivedOn()),
                        join(container.destinationPort(), "schip is binnen"), false));
            } else if (container.expectedArrival() != null) {
                String hour = hour(container.expectedArrivalTime());
                estimate = hour == null;
                facts.add(new Fact("Aankomst haven", (hour == null ? "± " : "") + day(container.expectedArrival()),
                        join(container.destinationPort(), hour), false));
            } else {
                facts.add(new Fact("Aankomst haven", "nog niet bekend", container.destinationPort(), true));
            }
            /* Where it is unloaded, only where the view may name it (his own container or Enrosed's warehouse). */
            String place = blank(container.locationName());
            String label = place == null ? "Magazijn" : "In " + place;
            if (container.warehouseArrivalOn() != null) {
                facts.add(new Fact(label, day(container.warehouseArrivalOn()), hour(container.warehouseArrivalTime()), false));
            } else {
                facts.add(new Fact(label, place == null ? "—" : "nog niet gepland", null, true));
            }
        }

        PartnerContainerViews.ContainerTotals sum = container.totals();
        List<Total> totals = new ArrayList<>();
        totals.add(new Total("Producten", count(sum.products())));
        totals.add(new Total("Stuks", count(sum.pieces())));
        totals.add(new Total("Dozen", count(sum.cartons())));
        totals.add(new Total("Volume", (sum.cbmUnknown() ? "± " : "") + cbm(sum.cbm()) + " m³"));
        totals.add(new Total("Pallets", sum.pallets() == null ? "—" : "± " + count(sum.pallets())));
        totals.add(new Total("Gewicht", sum.weightKg() == null ? "—"
                : (sum.weightUnknown() ? "± " : "") + kg(sum.weightKg())));

        Map<String, List<PartnerContainerViews.ContainerLine>> grouped = new LinkedHashMap<>();
        Map<String, String> categories = new HashMap<>();
        Map<String, String> families = new HashMap<>();
        Map<Long, String> photoCache = new HashMap<>();
        List<PartnerContainerViews.ContainerLine> lines = new ArrayList<>(container.lines());
        Map<Long, Integer> order = new HashMap<>();
        for (int i = 0; i < page.products().size(); i++) order.put(page.products().get(i).tile().productId(), i);
        lines.sort((a, b) -> {
            int byOrder = Integer.compare(order.getOrDefault(a.productId(), Integer.MAX_VALUE),
                    order.getOrDefault(b.productId(), Integer.MAX_VALUE));
            return byOrder != 0 ? byOrder : Long.compare(a.productId(), b.productId());
        });
        for (PartnerContainerViews.ContainerLine line : lines) {
            PartnerContainerViews.ProductSpec spec = specs.get(line.productId());
            String key = (spec == null || spec.tile().categoryId() == null ? "x" : spec.tile().categoryId()) + "|"
                    + (spec == null || spec.tile().familyId() == null ? "p" + line.productId() : spec.tile().familyId());
            categories.putIfAbsent(key, spec == null ? null : blank(spec.tile().categoryName()));
            families.putIfAbsent(key, spec == null || spec.tile().familyId() == null ? null : blank(spec.tile().familyName()));
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(line);
        }
        List<Group> groups = new ArrayList<>();
        int position = 0;
        for (Map.Entry<String, List<PartnerContainerViews.ContainerLine>> entry : grouped.entrySet()) {
            List<PartnerContainerViews.ContainerLine> members = entry.getValue();
            /* The products of a family under one name (often not the family's own): the head carries that name once
               and each line reads as its colour and size. A product without a family is a group of its own. */
            PartnerContainerViews.ProductSpec first = specs.get(members.getFirst().productId());
            boolean shared = first != null && members.stream().allMatch(member -> {
                PartnerContainerViews.ProductSpec spec = specs.get(member.productId());
                return spec != null && blank(spec.tile().variant()) != null && same(spec.tile().name(), first.tile().name());
            });
            String head = shared ? blank(first.tile().name()) : families.get(entry.getKey());
            String label = join(" · ", categories.get(entry.getKey()), head);
            List<Line> rows = new ArrayList<>();
            for (PartnerContainerViews.ContainerLine member : members) {
                rows.add(line(++position, member, specs.get(member.productId()), shared, received, photoCache));
            }
            groups.add(new Group(label == null ? "Andere producten" : label, rows.size(), List.copyOf(rows)));
        }

        String number = container.name() == null ? "Container " + container.id() : container.name();
        return new View(number, stateWord(container.state()), List.copyOf(facts), List.copyOf(totals),
                List.copyOf(groups), groups.isEmpty(), day(today), estimate);
    }

    private Line line(int position, PartnerContainerViews.ContainerLine line, PartnerContainerViews.ProductSpec spec,
                      boolean shared, boolean received, Map<Long, String> photoCache) {
        String name = spec == null ? "Product #" + line.productId() : orDash(blank(spec.tile().name()));
        String variant = spec == null ? null : blank(spec.tile().variant());
        String title = shared && variant != null ? variant : name;
        String detail = shared && variant != null ? null : variant;
        /* Code, size and packaging, each a whole that moves to the next line as one. */
        List<String> facts = new ArrayList<>();
        if (spec != null) {
            if (blank(spec.tile().sku()) != null) facts.add(blank(spec.tile().sku()));
            if (size(spec.productCm()) != null) facts.add("Maat " + size(spec.productCm()));
            if (packaging(spec) != null) facts.add(packaging(spec));
        }
        String carton = spec == null ? null : carton(spec);

        String one = spec == null ? "stuk" : orElse(blank(spec.tile().unitOne()), "stuk");
        String other = spec == null ? "stuks" : orElse(blank(spec.tile().unitOther()), "stuks");
        String unit = line.quantity() == 1 ? one : other;
        String pieces = null;
        if (spec != null && line.pieces() != line.quantity()) {
            String pieceOne = orElse(blank(spec.tile().pieceOne()), "stuk");
            String pieceOther = orElse(blank(spec.tile().pieceOther()), "stuks");
            pieces = "= " + count(line.pieces()) + " " + (line.pieces() == 1 ? pieceOne : pieceOther);
        }
        String ordered = received && line.orderedQuantity() != null
                ? "besteld " + count(line.orderedQuantity()) : null;
        return new Line(position, title, detail, photo(line.productId(), spec, photoCache), List.copyOf(facts), carton,
                count(line.quantity()), unit, pieces, ordered, count(line.cartons()), cbm(line.cbm()),
                line.pallets() == null ? "—" : "± " + count(line.pallets()),
                line.weightKg() == null ? "—" : kg(line.weightKg()));
    }

    /** The sales photo the portal shows, small, on a white square; null without one or when it cannot be read. */
    private String photo(long productId, PartnerContainerViews.ProductSpec spec, Map<Long, String> cache) {
        if (spec == null || !spec.tile().hasPhoto()) return null;
        String encoded = cache.computeIfAbsent(productId, id -> {
            try {
                Optional<SalesPhotoService.Choice> choice = QuarkusTransaction.joiningExisting()
                        .call(() -> products.findById(id).flatMap(SalesPhotoService::choose));
                if (choice.isEmpty()) return "";
                SalesPhotoService.Image image = photos.render(choice.get().photo(), "small");
                try (InputStream data = image.data()) {
                    String value = images.encodeContained(data.readAllBytes(), PHOTO_PX, PHOTO_PX, Color.WHITE);
                    return value == null ? "" : value;
                }
            } catch (Exception exception) {
                LOG.warnf("Productfoto %d kon niet op de pakbon: %s", id, exception.getMessage());
                return "";
            }
        });
        return encoded.isEmpty() ? null : encoded;
    }

    /* ------------------------------------------------------------------ words */

    static String stateWord(String state) {
        if (state == null) return "";
        return switch (state) {
            case "BESTELD" -> "Besteld";
            case "ONDERWEG" -> "Onderweg";
            case "BINNENKORT" -> "Binnenkort";
            case "AANGEKOMEN" -> "Aangekomen";
            default -> state;
        };
    }

    /** "vr 23 okt 2026". */
    static String day(LocalDate date) {
        return WEEKDAYS[date.getDayOfWeek().getValue() - 1] + " " + date.getDayOfMonth() + " "
                + MONTHS[date.getMonthValue() - 1] + " " + date.getYear();
    }

    /** "08:30" from "08:30" or "08:30:00"; null without one. */
    static String hour(String value) {
        return value != null && value.matches("^\\d{2}:\\d{2}.*") ? value.substring(0, 5) : null;
    }

    static String count(long value) {
        DecimalFormat format = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(NL));
        return format.format(value);
    }

    /** "1,08" under 10 m³, "61,3" above. */
    static String cbm(BigDecimal value) {
        BigDecimal n = value == null ? BigDecimal.ZERO : value;
        DecimalFormat format = new DecimalFormat(n.compareTo(BigDecimal.TEN) >= 0 ? "#,##0.#" : "#,##0.##",
                DecimalFormatSymbols.getInstance(NL));
        /* Half up, as the portal's Intl.NumberFormat: 0,125 m³ reads 0,13 on both. */
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(n);
    }

    /** "996 kg", "12,5 kg". */
    static String kg(BigDecimal value) {
        BigDecimal n = value.compareTo(BigDecimal.valueOf(100)) >= 0 ? value.setScale(0, RoundingMode.HALF_UP) : value;
        DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(NL));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(n) + " kg";
    }

    /** "17 × 17 × 28 cm"; null without all three. */
    static String size(PartnerContainerViews.Size size) {
        if (size == null || size.b() == null || size.d() == null || size.h() == null) return null;
        DecimalFormat format = new DecimalFormat("#,##0.#", DecimalFormatSymbols.getInstance(NL));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(size.b()) + " × " + format.format(size.d()) + " × " + format.format(size.h()) + " cm";
    }

    /** "Geschenkverpakking 18,5 × 18,5 × 31,5 cm", "Display"; null without packaging. */
    static String packaging(PartnerContainerViews.ProductSpec spec) {
        String word = spec.packagingKind() == null ? null : switch (spec.packagingKind()) {
            case "GIFT_BOX" -> "Geschenkverpakking";
            case "DISPLAY" -> "Display";
            default -> null;
        };
        if (word == null) return null;
        String size = size(spec.packagingCm());
        return size == null ? word : word + " " + size;
    }

    /** "Omdoos 90 × 60 × 73 cm · 40 per doos · 12 kg" (Aantal names the noun); null when nothing is known. */
    static String carton(PartnerContainerViews.ProductSpec spec) {
        Integer n = spec.unitsPerCarton();
        String content = n == null || n <= 0 ? null : count(n) + " per doos";
        String text = join(" · ", size(spec.cartonCm()), content,
                spec.cartonWeightKg() == null ? null : kg(spec.cartonWeightKg()));
        return text == null ? null : "Omdoos " + text;
    }

    /** The same words, whatever their case, spaces, dashes or × against x. */
    static boolean same(String a, String b) {
        return normal(a).equals(normal(b));
    }

    private static String normal(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('×', 'x').replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static String join(String a, String b) {
        return join(" · ", a, b);
    }

    private static String join(String separator, String... parts) {
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            if (!text.isEmpty()) text.append(separator);
            text.append(part.strip());
        }
        return text.isEmpty() ? null : text.toString();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String orElse(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
