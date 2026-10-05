package be.enrosed.catalog.application;

import be.enrosed.catalog.application.ProductPhotoExport.FileEntry;
import be.enrosed.catalog.application.ProductPhotoExport.Plan;
import be.enrosed.catalog.application.ProductPhotoExport.ProductEntry;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Packaging;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
import be.enrosed.shared.PhotoExportText;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The plain-text read-me (LEESMIJ.txt / README.txt) that travels with the
 * photos: what the export contains and, per product, the facts a buyer or a
 * webshop needs next to the pictures.
 *
 * Only what {@link ProductEntry} carries can be printed, and that record has
 * no prices, costs, supplier or stock data. Every fixed sentence comes from
 * i18n/photo-export-text.csv; units (cm, kg, px) are the same everywhere.
 */
public final class PhotoExportReadme {

    /** Windows Notepad and macOS TextEdit both read UTF-8 with a BOM and CRLF without guessing. */
    private static final String NEWLINE = "\r\n";
    private static final String BOM = "﻿";
    private static final String SEPARATOR = " — ";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private PhotoExportReadme() {}

    /** LEESMIJ.txt for Dutch, README.txt for every other language. */
    public static String fileName(Language language) {
        return language == Language.NL ? "LEESMIJ.txt" : "README.txt";
    }

    public static byte[] bytes(Plan plan, ZonedDateTime exportedAt) {
        return render(plan, exportedAt).getBytes(StandardCharsets.UTF_8);
    }

    public static String render(Plan plan, ZonedDateTime exportedAt) {
        Language language = plan.request().language();
        Map<String, String> text = PhotoExportText.of(language);
        List<String> lines = new ArrayList<>();

        String title = text.get("title");
        lines.add(title);
        lines.add("=".repeat(title.codePointCount(0, title.length())));
        lines.add("");
        lines.add(text.get("exportedAt") + ": " + DocumentText.date(exportedAt.toLocalDate(), language)
                + " " + exportedAt.format(TIME));
        lines.add(text.get("selection") + ": " + text.get("scope." + plan.request().scope().name())
                + " · " + text.get("photos." + plan.request().photos().name()));
        lines.add(text.get("productCount") + ": " + integer(plan.productCount(), language));
        lines.add(text.get("photoCount") + ": " + integer(plan.photoCount(), language));
        lines.add("");

        heading(lines, text.get("aboutHeading"));
        lines.add("- " + text.get("about.folders"));
        lines.add("- " + text.get("about.lead").replace("{lead}", "01" + PhotoExportNames.LEAD_SUFFIX));
        lines.add("- " + text.get("about.quality"));
        lines.add("- " + text.get("about.shared").replace("{role}", text.get("role.series")));
        lines.add("- " + text.get("about.noFolder"));
        lines.add("- " + text.get("about.noPrices"));
        lines.add("");

        heading(lines, text.get("productsHeading"));
        int number = 0;
        for (ProductEntry product : plan.products()) {
            number++;
            product(lines, product, number, language, text);
        }

        return BOM + String.join(NEWLINE, lines) + NEWLINE;
    }

    private static void product(List<String> lines, ProductEntry product, int number,
                                Language language, Map<String, String> text) {
        lines.add("[" + number + "] " + join(SEPARATOR, product.sku(), product.name()));
        field(lines, text.get("field.folder"), product.folder());
        field(lines, text.get("field.sku"), product.sku());
        field(lines, text.get("field.name"), product.name());
        field(lines, text.get("field.colour"), product.colour());
        field(lines, text.get("field.size"), product.size());
        field(lines, text.get("field.series"), product.series());
        field(lines, text.get("field.category"), product.category());
        field(lines, text.get("field.dimensions"), dimensions(product.dimensions(), language));
        field(lines, text.get("field.weight"),
                product.dimensions() == null ? null : weight(product.dimensions().weightKg(), language));
        field(lines, text.get("field.packaging"), packaging(product.packaging(), language, text));
        Packaging packaging = product.packaging();
        field(lines, text.get("field.packagingEan"), packaging == null ? null : packaging.barcode());
        field(lines, text.get("field.cartonContents"), cartonContents(product, language, text));
        Carton carton = product.carton();
        field(lines, text.get("field.cartonDimensions"),
                carton == null ? null : dimensions(carton.dimensions(), language));
        field(lines, text.get("field.cartonWeight"),
                carton == null ? null : weight(carton.weightKg(), language));
        field(lines, text.get("field.ean"), product.pieceEan());
        field(lines, text.get("field.itf"), product.outerItf());
        if (product.files().isEmpty()) {
            lines.add(text.get("field.files") + ": " + text.get("noPhotos"));
        } else {
            lines.add(text.get("field.files") + ":");
            for (FileEntry file : product.files()) lines.add("  " + file(file, text));
        }
        lines.add("");
    }

    private static String file(FileEntry file, Map<String, String> text) {
        List<String> roles = new ArrayList<>();
        if (file.lead()) roles.add(text.get("role.lead"));
        if (file.website()) roles.add(text.get("role.website"));
        roles.add(text.get(switch (file.source()) {
            case PRODUCT -> "role.product";
            case VARIANT -> "role.variant";
            case SERIES -> "role.series";
        }));
        StringBuilder line = new StringBuilder(file.name())
                .append(SEPARATOR).append(String.join(" · ", roles));
        if (positive(file.widthPx()) && positive(file.heightPx())) {
            /* Pixel sizes are technical: 4000 × 3000 px everywhere, without thousands separators. */
            line.append(SEPARATOR).append(file.widthPx())
                    .append(" × ").append(file.heightPx()).append(" px");
        }
        if (file.originalFilename() != null && !file.originalFilename().isBlank()) {
            line.append(SEPARATOR).append(text.get("file.original")).append(": ")
                    .append(file.originalFilename().strip());
        }
        return line.toString();
    }

    private static String packaging(Packaging packaging, Language language, Map<String, String> text) {
        if (packaging == null || !packaging.isPresent()) return null;
        String kind;
        if (packaging.kind() == PackagingKind.DISPLAY) {
            Integer pieces = packaging.piecesPerUnit();
            kind = pieces != null && pieces > 1
                    ? text.get("packaging.displayOf").replace("{count}", integer(pieces, language))
                    : text.get("packaging.display");
        } else {
            kind = text.get("packaging.giftBox");
        }
        String size = dimensions(packaging.dimensions(), language);
        return size == null ? kind : kind + SEPARATOR + size;
    }

    private static String cartonContents(ProductEntry product, Language language, Map<String, String> text) {
        Carton carton = product.carton();
        if (carton == null || carton.piecesPerCarton() <= 0) return null;
        int count = carton.piecesPerCarton();
        /* One piece and no carton size is the untouched default, not a real carton. */
        if (count == 1 && (carton.dimensions() == null || carton.dimensions().isBlank())) return null;
        Packaging packaging = product.packaging();
        if (packaging != null && packaging.soldAsDisplay()) {
            return text.get(count == 1 ? "carton.oneDisplay" : "carton.displays")
                    .replace("{count}", integer(count, language))
                    .replace("{pieces}", integer((long) count * packaging.unitPieces(), language));
        }
        return count == 1 ? text.get("carton.onePiece")
                : text.get("carton.pieces").replace("{count}", integer(count, language));
    }

    /** "15 × 30 × 12 cm" with the language's decimal separator; null when nothing is known. */
    static String dimensions(Dimensions dimensions, Language language) {
        if (dimensions == null || dimensions.isBlank()) return null;
        return measure(dimensions.lengthCm(), language) + " × " + measure(dimensions.widthCm(), language)
                + " × " + measure(dimensions.heightCm(), language) + " cm";
    }

    private static String weight(BigDecimal kilograms, Language language) {
        if (kilograms == null || kilograms.signum() <= 0) return null;
        return decimal(kilograms, 3, language) + " kg";
    }

    private static String measure(BigDecimal value, Language language) {
        return value == null || value.signum() <= 0 ? "—" : decimal(value, 2, language);
    }

    private static String decimal(BigDecimal value, int maximumFractionDigits, Language language) {
        NumberFormat format = NumberFormat.getNumberInstance(language.locale());
        format.setGroupingUsed(false);
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(maximumFractionDigits);
        return format.format(value);
    }

    private static String integer(long value, Language language) {
        return NumberFormat.getIntegerInstance(language.locale()).format(value);
    }

    private static void heading(List<String> lines, String heading) {
        lines.add(heading);
        lines.add("-".repeat(heading.codePointCount(0, heading.length())));
        lines.add("");
    }

    private static void field(List<String> lines, String label, String value) {
        if (value == null || value.isBlank()) return;
        lines.add(label + ": " + value.strip());
    }

    private static String join(String separator, String... parts) {
        List<String> present = new ArrayList<>();
        for (String part : parts) if (part != null && !part.isBlank()) present.add(part.strip());
        return String.join(separator, present);
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }
}
