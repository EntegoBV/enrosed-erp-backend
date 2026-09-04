package be.enrosed.sourcing.adapter.out.market;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The weekly NCFI reprint on Hellenic Shipping News, read through the
 * official "Weekly Index Data" PDF the reprint attaches.
 *
 * The exchange's own site is a splash page and the Baltic Exchange copy sits
 * behind a bot challenge; the reprint is the one free, dated publication of
 * the Ningbo Shipping Exchange table, and since August 2026 its article text
 * no longer carries any number at all. The PDF does: every route with the
 * previous and the current week side by side, both weeks dated. One download
 * therefore serves the Europe route and the composite alike.
 */
final class NcfiReprint {

    private static final String ARTICLE =
            "https://www.hellenicshippingnews.com/ningbo-containerized-freight-index-report-%d-%s-%d/";
    private static final String USER_AGENT = "Mozilla/5.0 (Enrosed ERP dashboard)";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH);
    private static final Pattern DATA_PDF = Pattern.compile(
            "href=\"\\s*(https?://[^\"\\s]*NCFI-Weekly-Index-Data[^\"\\s]*\\.pdf)\\s*\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile("\\((\\d{4}-\\d{2}-\\d{2})\\)");
    private static final String NUMBER = "([\\d,]+(?:\\.\\d+)?)";
    private static final Pattern COMPOSITE = Pattern.compile(
            "Composite\\s+Index\\s+" + NUMBER + "\\s+" + NUMBER, Pattern.CASE_INSENSITIVE);
    private static final Pattern EUROPE = Pattern.compile(
            "(?<![\\w.])Europe\\s+" + NUMBER + "\\s+" + NUMBER, Pattern.CASE_INSENSITIVE);
    /** A reprint PDF is a few dozen kilobytes; anything bigger is not the table. */
    private static final int MAX_PDF_BYTES = 4 * 1024 * 1024;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private NcfiReprint() {}

    /** One week of the exchange table: both dated columns for the rows we chart. */
    record WeeklyTable(
            LocalDate previousOn,
            LocalDate currentOn,
            BigDecimal compositePrevious,
            BigDecimal compositeCurrent,
            BigDecimal europePrevious,
            BigDecimal europeCurrent) {

        /** The value published for one of the two dates, or null for any other day. */
        BigDecimal compositeOn(LocalDate day) {
            if (day.equals(currentOn)) return compositeCurrent;
            return day.equals(previousOn) ? compositePrevious : null;
        }
    }

    /** The provider answered with a bot challenge instead of the publication. */
    static final class ProviderAccessException extends IllegalStateException {
        ProviderAccessException(String message) {
            super(message);
        }
    }

    static String articleUrl(LocalDate friday) {
        return String.format(Locale.ROOT, ARTICLE, friday.getDayOfMonth(),
                friday.format(MONTH).toLowerCase(Locale.ENGLISH), friday.getYear());
    }

    /** The reprint article for one Friday; empty when that week has none. */
    static Optional<String> fetchArticle(LocalDate friday) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(articleUrl(friday)))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", USER_AGENT)
                .GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return Optional.empty();
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        if (isProviderChallenge(response.body())) {
            throw new ProviderAccessException(
                    "Provider challenge received; configure the authorized NCFI feed, "
                    + "credentials or IP allowlist");
        }
        return Optional.of(response.body());
    }

    /** The whole table of one Friday's reprint; empty when the week has no reprint or no data PDF. */
    static Optional<WeeklyTable> fetchWeek(LocalDate friday) throws Exception {
        Optional<String> article = fetchArticle(friday);
        if (article.isEmpty()) return Optional.empty();
        String link = dataPdfLink(article.get());
        if (link == null) return Optional.empty();
        return Optional.ofNullable(parseTable(extractText(fetchPdf(link))));
    }

    static byte[] fetchPdf(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT)
                .GET().build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " for the index PDF");
        }
        if (response.body().length > MAX_PDF_BYTES) {
            throw new IllegalStateException("Index PDF unexpectedly large");
        }
        return response.body();
    }

    /** The "Weekly Index Data" attachment, never the commentary PDF next to it. */
    static String dataPdfLink(String html) {
        if (html == null) return null;
        Matcher matcher = DATA_PDF.matcher(html);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    static boolean isProviderChallenge(String html) {
        if (html == null || html.isBlank()) return false;
        String normalized = html.toLowerCase(Locale.ROOT);
        return normalized.contains("<title>challenge validation</title>")
                || normalized.contains("akamai bot manager");
    }

    /** Row-ordered text, so every route keeps its two numbers beside its name. */
    static String extractText(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    /** Null when the text carries no dated table; route values are null when their row is absent. */
    static WeeklyTable parseTable(String text) {
        if (text == null) return null;
        String flat = text.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        Matcher dates = DATE.matcher(flat);
        if (!dates.find()) return null;
        LocalDate first = LocalDate.parse(dates.group(1));
        if (!dates.find()) return null;
        LocalDate second = LocalDate.parse(dates.group(1));
        LocalDate previous = first.isBefore(second) ? first : second;
        LocalDate current = first.isBefore(second) ? second : first;
        boolean swapped = !first.isBefore(second);

        BigDecimal[] composite = row(COMPOSITE, flat, swapped);
        BigDecimal[] europe = row(EUROPE, flat, swapped);
        if (composite == null && europe == null) return null;
        return new WeeklyTable(previous, current,
                composite == null ? null : composite[0], composite == null ? null : composite[1],
                europe == null ? null : europe[0], europe == null ? null : europe[1]);
    }

    private static BigDecimal[] row(Pattern pattern, String flat, boolean swapped) {
        Matcher matcher = pattern.matcher(flat);
        if (!matcher.find()) return null;
        BigDecimal left = decimal(matcher.group(1));
        BigDecimal right = decimal(matcher.group(2));
        return swapped ? new BigDecimal[] {right, left} : new BigDecimal[] {left, right};
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value.replace(",", ""));
    }
}
