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
import java.util.zip.GZIPInputStream;

/**
 * Plain HTTP reads with the headers of an ordinary desktop browser and a
 * cookie jar, so a shop's bot gate sees the same visitor every time. Bodies are
 * capped; a blocked or broken answer is a {@link FetchFailure} with a Dutch
 * explanation for the radar screen.
 */
public final class StockRadarHttpFetcher {

    static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36";
    private static final int MAX_BYTES = 6_000_000;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
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
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("Sec-Fetch-Dest", json ? "empty" : "document")
                .header("Sec-Fetch-Mode", json ? "cors" : "navigate")
                .header("Sec-Fetch-Site", json ? "same-origin" : "none")
                .header("Upgrade-Insecure-Requests", "1")
                .header("sec-ch-ua", "\"Chromium\";v=\"142\", \"Google Chrome\";v=\"142\", \"Not_A Brand\";v=\"99\"")
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua-platform", "\"macOS\"")
                .GET().build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = response.body();
            if (bytes.length > MAX_BYTES) throw new FetchFailure("Antwoord te groot (" + bytes.length / 1024 + " kB)");
            if ("gzip".equalsIgnoreCase(response.headers().firstValue("Content-Encoding").orElse(""))) bytes = gunzip(bytes);
            String body = new String(bytes, StandardCharsets.UTF_8);
            int status = response.statusCode();
            if (status == 403 || status == 429 || status == 503) throw new FetchFailure("Winkel blokkeert de controle (HTTP " + status + ")");
            if (status == 404) throw new FetchFailure("Productpagina niet gevonden (HTTP 404) - artikelnummer nakijken");
            if (status >= 400) throw new FetchFailure("Winkel antwoordde met HTTP " + status);
            return new Page(status, body, null);
        } catch (java.net.http.HttpTimeoutException timeout) {
            throw new FetchFailure("Winkel antwoordt niet op tijd");
        } catch (IOException failure) {
            throw new FetchFailure("Winkel niet bereikbaar");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new FetchFailure("Controle onderbroken");
        }
    }

    private static byte[] gunzip(byte[] bytes) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return in.readNBytes(MAX_BYTES + 1);
        }
    }
}
