package be.enrosed.stockradar;

import be.enrosed.stockradar.StockRadar.FetchFailure;
import be.enrosed.stockradar.StockRadar.Page;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPInputStream;

/**
 * Plain HTTP reads with a desktop browser's user agent (as the radar has done
 * since October 2026; with an honest client name Coolblue answers 403, Emre's
 * call) and only the ordinary Accept headers: no client hints or Sec-Fetch
 * headers that would claim a browser context, nothing that works around a
 * shop's checks. One client and one cookie jar for the whole radar, so a shop
 * sees the same visitor every time; bodies are capped, nothing grows with time. No browser, no child process: the answer has
 * 25 seconds to start and 20 more to arrive, then the stream is closed. A
 * blocked or broken answer is a {@link FetchFailure} with a Dutch explanation
 * for the radar screen.
 */
public final class StockRadarHttpFetcher {

    static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36";
    private static final int MAX_BYTES = 6_000_000;
    private static final ScheduledExecutorService CUTTER = cutter();

    /** One daemon thread that closes streams that keep trickling; a cancelled cut leaves its queue at once. */
    private static ScheduledExecutorService cutter() {
        java.util.concurrent.ScheduledThreadPoolExecutor executor = new java.util.concurrent.ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "stock-radar-http-cutter");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .cookieHandler(cookies)
            .build();
    public Page fetch(String url) {
        boolean json = url.contains("pickup-message") || url.contains("delivery-message");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", USER_AGENT)
                .header("Accept", json ? "application/json,text/plain;q=0.9,*/*;q=0.8"
                        : "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", url.contains(".nl/") ? "nl-NL,nl;q=0.9,en;q=0.8" : "nl-BE,nl;q=0.9,fr-BE;q=0.7,en;q=0.6")
                .header("Accept-Encoding", "gzip")
                .GET().build();
        CompletableFuture<HttpResponse<InputStream>> pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        try {
            HttpResponse<InputStream> response = pending.get(30, TimeUnit.SECONDS);
            InputStream raw = response.body();
            ScheduledFuture<?> cut = CUTTER.schedule(() -> { try { raw.close(); } catch (IOException ignored) { /* already closed */ } }, 20, TimeUnit.SECONDS);
            byte[] bytes;
            try (InputStream stream = raw) {
                bytes = stream.readNBytes(MAX_BYTES + 1);
            } catch (IOException slow) {
                throw new FetchFailure("Winkel antwoordt niet op tijd");
            } finally {
                cut.cancel(false);
            }
            if (bytes.length > MAX_BYTES) throw new FetchFailure("Antwoord te groot (meer dan " + MAX_BYTES / 1_000_000 + " MB)");
            if ("gzip".equalsIgnoreCase(response.headers().firstValue("Content-Encoding").orElse(""))) bytes = gunzip(bytes);
            int status = response.statusCode();
            String body = new String(bytes, charsetOf(response.headers().firstValue("Content-Type").orElse("")));
            if (status == 403 || status == 429 || status == 503 || status == 541) throw new FetchFailure("Winkel weigert de controle even (HTTP " + status + ")", true);
            if (StockRadar.robotCheck(body)) throw new FetchFailure("Winkel vraagt even een robotcontrole (HTTP " + status + ")", true);
            if (status == 404) throw new FetchFailure("Productpagina niet gevonden (HTTP 404) - artikelnummer nakijken");
            if (status >= 400) throw new FetchFailure("Winkel antwoordde met HTTP " + status);
            return new Page(status, body);
        } catch (TimeoutException slow) {
            pending.cancel(true);
            throw new FetchFailure("Winkel antwoordt niet op tijd");
        } catch (ExecutionException failed) {
            throw new FetchFailure(failed.getCause() instanceof java.net.http.HttpTimeoutException ? "Winkel antwoordt niet op tijd" : "Winkel niet bereikbaar");
        } catch (IOException failure) {
            throw new FetchFailure("Antwoord niet leesbaar");
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new FetchFailure("Controle onderbroken");
        }
    }

    /** The page's own charset (Vanden Borre sends ISO-8859-1), UTF-8 when it names none or an unknown one. */
    static java.nio.charset.Charset charsetOf(String contentType) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)charset=\"?([A-Za-z0-9._-]+)").matcher(contentType);
        if (!matcher.find()) return StandardCharsets.UTF_8;
        try { return java.nio.charset.Charset.forName(matcher.group(1)); }
        catch (RuntimeException unknown) { return StandardCharsets.UTF_8; }
    }

    private static byte[] gunzip(byte[] bytes) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            byte[] out = in.readNBytes(MAX_BYTES + 1);
            if (out.length > MAX_BYTES) throw new FetchFailure("Antwoord te groot (meer dan " + MAX_BYTES / 1_000_000 + " MB)");
            return out;
        }
    }
}
