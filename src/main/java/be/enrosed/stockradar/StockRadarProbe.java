package be.enrosed.stockradar;

import be.enrosed.stockradar.StockRadar.Observation;
import be.enrosed.stockradar.StockRadar.Watch;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads every page once and prints the verdicts; no database, no notifications.
 *
 * java -cp 'target/quarkus-app/lib/main/*:target/quarkus-app/lib/boot/*:target/quarkus-app/app/*' be.enrosed.stockradar.StockRadarProbe [watch-or-shop-key ...]
 */
public final class StockRadarProbe {

    private StockRadarProbe() {}

    public static void main(String[] args) {
        List<String> keys = List.of(args);
        LocalDate today = LocalDate.now(StockRadarService.BRUSSELS);
        StockRadarHttpFetcher http = new StockRadarHttpFetcher();
        Map<String, Object> pages = new HashMap<>();
        for (Watch watch : StockRadarStores.WATCHES) {
            if (!keys.isEmpty() && !keys.contains(watch.key()) && !keys.contains(watch.shop().key())) continue;
            Object page = pages.computeIfAbsent(watch.url(), url -> {
                try { return http.fetch(url); } catch (RuntimeException failure) { return failure; }
            });
            try {
                if (page instanceof RuntimeException failure) throw failure;
                Observation seen = watch.checker().check((StockRadar.Page) page, today);
                boolean available = seen.inStock() && !seen.dateUnknown() && (seen.deliveryBy() == null || !seen.deliveryBy().isAfter(today.plusDays(3)));
                System.out.println(watch.key() + " | " + (available ? "BESCHIKBAAR" : "niet") + " | inStock=" + seen.inStock() + " | by=" + seen.deliveryBy()
                        + " | € " + seen.priceEur() + " | " + seen.statusText() + (seen.places().isEmpty() ? "" : " | " + seen.places()));
            } catch (RuntimeException failure) {
                System.out.println(watch.key() + " | FOUT | " + failure.getMessage());
            }
        }
    }
}
