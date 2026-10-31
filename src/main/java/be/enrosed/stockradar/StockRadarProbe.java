package be.enrosed.stockradar;

import be.enrosed.stockradar.StockRadar.Method;
import be.enrosed.stockradar.StockRadar.Observation;
import be.enrosed.stockradar.StockRadar.Page;
import be.enrosed.stockradar.StockRadar.Store;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads every shop once and prints the verdicts; no database, no notifications.
 *
 * java -cp 'target/quarkus-app/lib/main/*:target/quarkus-app/lib/boot/*:target/quarkus-app/app/*' be.enrosed.stockradar.StockRadarProbe [store-key ...] [--no-browser]
 */
public final class StockRadarProbe {

    private StockRadarProbe() {}

    public static void main(String[] args) {
        boolean browser = true;
        List<String> keys = new ArrayList<>();
        for (String arg : args) { if ("--no-browser".equals(arg)) browser = false; else keys.add(arg); }
        LocalDate today = LocalDate.now(StockRadarService.BRUSSELS);
        StockRadarHttpFetcher http = new StockRadarHttpFetcher();
        List<Store> rendered = new ArrayList<>();
        for (Store store : StockRadarStores.STORES) {
            if (!keys.isEmpty() && !keys.contains(store.key())) continue;
            if (store.method() != Method.HTTP) { rendered.add(store); continue; }
            try { print(store, store.checker().check(http.fetch(store.url()), today), today); }
            catch (RuntimeException failure) { System.out.println(store.key() + " | FOUT | " + failure.getMessage()); }
        }
        if (!browser || rendered.isEmpty()) return;
        for (Method method : List.of(Method.BROWSER, Method.CHROME)) {
            List<Store> batch = rendered.stream().filter(store -> store.method() == method).toList();
            if (batch.isEmpty()) continue;
            Map<String, Object> results = new StockRadarBrowserFetcher().fetchAll(batch.stream().map(Store::url).toList(), 40, method == Method.CHROME);
            for (Store store : batch) {
                try {
                    Page page = StockRadarBrowserFetcher.page(results.get(store.url()));
                    print(store, store.checker().check(page, today), today);
                } catch (RuntimeException failure) { System.out.println(store.key() + " | FOUT | " + failure.getMessage()); }
            }
        }
    }

    private static void print(Store store, Observation seen, LocalDate today) {
        boolean available = seen.inStock() && (seen.deliveryBy() == null || !seen.deliveryBy().isAfter(today.plusDays(3)));
        System.out.println(store.key() + " | " + (available ? "BESCHIKBAAR" : "niet") + " | inStock=" + seen.inStock() + " | by=" + seen.deliveryBy()
                + " | € " + seen.priceEur() + " | " + seen.statusText() + (seen.foundUrl() == null ? "" : " | " + seen.foundUrl()));
    }
}
