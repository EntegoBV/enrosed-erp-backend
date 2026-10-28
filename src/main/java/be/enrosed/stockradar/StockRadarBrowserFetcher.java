package be.enrosed.stockradar;

import be.enrosed.stockradar.StockRadar.FetchFailure;
import be.enrosed.stockradar.StockRadar.Page;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Reads pages that refuse plain HTTP (Akamai on mediamarkt.nl, Krëfel, Vanden
 * Borre's client-rendered availability) in a short-lived headless Chromium,
 * the one the TICA export already installs in the image. One browser serves
 * all pages that are due in a tick; images, fonts and media are never loaded.
 */
public final class StockRadarBrowserFetcher {

    private static final Set<String> SKIP = Set.of("image", "media", "font", "websocket", "manifest", "texttrack", "eventsource");

    /**
     * Fetches every url in one browser; a page that fails is a FetchFailure in
     * the map, not an abort of the batch. {@code full} launches the complete
     * Chromium instead of the headless shell (Krëfel answers 500 to the shell).
     */
    public Map<String, Object> fetchAll(List<String> urls, int timeoutSeconds, boolean full) {
        Map<String, Object> results = new LinkedHashMap<>();
        BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions().setHeadless(true)
                .setTimeout(Math.max(10_000, timeoutSeconds * 1000L))
                .setArgs(List.of("--disable-blink-features=AutomationControlled", "--no-sandbox", "--disable-dev-shm-usage"));
        if (full) launch.setChannel("chromium");
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(launch);
             BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                     .setUserAgent(StockRadarHttpFetcher.USER_AGENT)
                     .setLocale("nl-BE").setTimezoneId("Europe/Brussels")
                     .setViewportSize(1366, 900)
                     .setExtraHTTPHeaders(Map.of("Accept-Language", "nl-BE,nl;q=0.9,en;q=0.8")))) {
            context.setDefaultTimeout(Math.max(5_000, timeoutSeconds * 1000L));
            context.setDefaultNavigationTimeout(Math.max(5_000, timeoutSeconds * 1000L));
            context.route("**/*", route -> {
                if (SKIP.contains(route.request().resourceType())) route.abort();
                else route.resume();
            });
            for (String url : urls) results.put(url, read(context, url, timeoutSeconds));
        } catch (RuntimeException failure) {
            FetchFailure reason = new FetchFailure("Browser kon niet starten (" + failure.getClass().getSimpleName() + ")");
            for (String url : urls) results.putIfAbsent(url, reason);
        }
        return results;
    }

    private static Object read(BrowserContext context, String url, int timeoutSeconds) {
        try (var page = context.newPage()) {
            // Krëfel wants a session before it serves a product page: land on the home page first.
            if (url.contains("krefel.be")) {
                try { page.navigate("https://www.krefel.be/nl", new com.microsoft.playwright.Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)); }
                catch (RuntimeException ignored) { /* the product page decides */ }
            }
            var response = page.navigate(url, new com.microsoft.playwright.Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            try { page.waitForLoadState(LoadState.NETWORKIDLE, new com.microsoft.playwright.Page.WaitForLoadStateOptions().setTimeout(Math.min(12_000, timeoutSeconds * 1000L))); }
            catch (RuntimeException slow) { /* a chatty page never goes idle; what is rendered by now is enough */ }
            page.waitForTimeout(1200);
            int status = response == null ? 200 : response.status();
            String html = page.content();
            String text = page.innerText("body");
            if (status == 403 || status == 429 || status == 503) throw new FetchFailure("Winkel blokkeert ook de browsercontrole (HTTP " + status + ")");
            if (status == 404) throw new FetchFailure("Productpagina niet gevonden (HTTP 404) - artikelnummer nakijken");
            if (status >= 400) throw new FetchFailure("Winkel antwoordde met HTTP " + status);
            return new Page(status, html, text);
        } catch (FetchFailure failure) {
            return failure;
        } catch (RuntimeException failure) {
            return new FetchFailure("Browsercontrole mislukt (" + failure.getClass().getSimpleName() + ")");
        }
    }

    /** For the probe and the service: unwrap one result. */
    public static Page page(Object result) {
        if (result instanceof Page page) return page;
        if (result instanceof FetchFailure failure) throw failure;
        throw new FetchFailure("Geen resultaat");
    }

    static <T> Function<Object, T> unwrap(Class<T> type) { return type::cast; }
}
