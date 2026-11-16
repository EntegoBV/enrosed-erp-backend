package be.enrosed.sourcing.tracking;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The ships the ERP knows: every ship ever linked has one shared feed row ({@link TrackingStore#vesselFeeds()}),
 * and the feed's title is the ship's name. There is no keyless way to turn a ship's name into an IMO number, so a
 * name becomes an IMO only through a ship linked before; an unknown ship's IMO is typed once.
 *
 * <p>A ship is named only when a feed named it: a row whose first fetch failed has no observation, and
 * "IMO 9893890" is the placeholder of a feed without a title, not a name. No table of its own and no outward
 * call; it never throws, so a broken row cannot fail a bill of lading or a tracking pass.</p>
 */
@ApplicationScoped
public class VesselDirectory {
    @Inject TrackingStore store;

    /** The name the feed of this IMO gave; empty while no feed named the ship. */
    public Optional<String> nameOf(String imo) {
        if (imo == null || imo.isBlank()) return Optional.empty();
        return Optional.ofNullable(ships().get(imo.strip()));
    }

    /**
     * The IMO of the one known ship with this name ("CMACGM VASCO DE GAMA" is "CMA CGM VASCO DE GAMA"). Empty when
     * no ship has the name, and also when two do: a guess would link the wrong ship.
     */
    public Optional<String> imoOf(String vesselName) {
        String wanted = plain(vesselName);
        if (wanted.isEmpty()) return Optional.empty();
        Set<String> found = new HashSet<>();
        ships().forEach((imo, name) -> { if (plain(name).equals(wanted)) found.add(imo); });
        return found.size() == 1 ? Optional.of(found.iterator().next()) : Optional.empty();
    }

    /** IMO to name, over the feed rows that name a ship. */
    private Map<String, String> ships() {
        Map<String, String> ships = new HashMap<>();
        try {
            for (TrackingStore.VesselFeed feed : store.vesselFeeds()) {
                String name = feed.observation() == null ? null : feed.observation().vessel();
                if (named(name)) ships.put(feed.trackingNumber().substring(4), name.strip());
            }
        } catch (RuntimeException unreadable) {
            /* Not knowing a ship only means its IMO is asked for; failing here would stop the caller's work. */
        }
        return ships;
    }

    /** A feed gave this as a ship's name: not empty and not the placeholder "IMO 1234567" of a feed without a title. */
    public static boolean named(String vessel) {
        return vessel != null && !vessel.startsWith("IMO ") && !plain(vessel).isEmpty();
    }

    /**
     * Two texts name the same ship, as a bill of lading and a feed write it: equal, or one holds the other
     * ("EVER ALLY" and "M/V EVER ALLY"). Nothing is the same ship as an empty name.
     */
    public static boolean sameName(String one, String other) {
        String first = plain(one), second = plain(other);
        return !first.isEmpty() && !second.isEmpty() && (first.contains(second) || second.contains(first));
    }

    /**
     * The bill of lading names another ship than the linked ship's feed does. False while either is unknown: no
     * bill ship stored, or no feed named the linked ship yet. The arrival of such a ship is not this container's
     * (a mistyped IMO must not write "schip aangekomen"), and the panel says the names differ.
     */
    public static boolean differs(String billVessel, String feedName) {
        return !plain(billVessel).isEmpty() && named(feedName) && !sameName(billVessel, feedName);
    }

    /** How ship names are compared: without accents, upper case, letters and digits only. */
    private static String plain(String name) {
        if (name == null) return "";
        return Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }
}
