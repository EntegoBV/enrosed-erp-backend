package be.enrosed.shipping;

import be.enrosed.shipping.application.CarrierRepository;
import be.enrosed.shipping.domain.Carrier;
import be.enrosed.shipping.domain.CarrierLane;
import be.enrosed.shipping.domain.CarrierTier;
import be.enrosed.shipping.domain.CarrierZone;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the ESTA rate sheet (Barendrecht, 18/08/2026) once.
 *
 * The tariff was extracted from the received staffel workbook into
 * {@code esta-rates.json}: per country the zones with their postcode
 * prefixes and the tier ladder with a price per zone. Runs on every start
 * but only writes when no carrier named ESTA exists yet, so later manual
 * edits in the app survive restarts and deploys.
 */
@ApplicationScoped
public class EstaSeed {

    private static final Logger LOG = Logger.getLogger(EstaSeed.class);

    private final CarrierRepository carriers;
    private final ObjectMapper mapper;

    public EstaSeed(CarrierRepository carriers, ObjectMapper mapper) {
        this.carriers = carriers;
        this.mapper = mapper;
    }

    void onStart(@Observes StartupEvent event) {
        try {
            if (carriers.findByName("ESTA").isPresent()) return;
            try (InputStream in = getClass().getResourceAsStream("/esta-rates.json")) {
                if (in == null) {
                    LOG.warn("esta-rates.json ontbreekt; ESTA-staffel niet geladen");
                    return;
                }
                JsonNode doc = mapper.readTree(in);
                Carrier carrier = parse(doc);
                carriers.save(carrier);
                LOG.infof("ESTA-staffel geladen: %d landen", carrier.lanes().size());
            }
        } catch (Exception e) {
            LOG.error("ESTA-staffel laden mislukt", e);
        }
    }

    private static Carrier parse(JsonNode doc) {
        List<CarrierLane> lanes = new ArrayList<>();
        for (JsonNode lane : doc.path("lanes")) {
            List<CarrierZone> zones = new ArrayList<>();
            int zonePosition = 0;
            for (JsonNode zone : lane.path("zones")) {
                zones.add(new CarrierZone(null, zone.path("name").asText(),
                        zone.path("postcodes").asText(), zonePosition++));
            }
            List<CarrierTier> tiers = new ArrayList<>();
            int tierPosition = 0;
            for (JsonNode tier : lane.path("tiers")) {
                List<BigDecimal> prices = new ArrayList<>();
                for (JsonNode price : tier.path("prices")) prices.add(price.decimalValue());
                tiers.add(new CarrierTier(null, decimal(tier, "ep"), decimal(tier, "bp"),
                        decimal(tier, "ldm"), decimal(tier, "kg"), tierPosition++, prices));
            }
            lanes.add(new CarrierLane(null, lane.path("country").asText(),
                    decimal(lane, "surchargePct"), decimal(lane, "surchargeFixedEur"),
                    text(lane, "surchargeNote"), zones, tiers));
        }
        return new Carrier(null, doc.path("carrier").asText(), text(doc, "fullName"), true,
                decimal(doc, "dieselSurchargePct"),
                doc.hasNonNull("validUntil") ? LocalDate.parse(doc.get("validUntil").asText()) : null,
                text(doc, "notes"), lanes);
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).decimalValue() : null;
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
