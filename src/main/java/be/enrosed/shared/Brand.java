package be.enrosed.shared;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

/**
 * Het logo, klaar om in een PDF of e-mail te zetten.
 *
 * openhtmltopdf kan geen bestanden van het klassenpad halen, dus wordt het
 * logo een keer ingelezen en als data-URI in het sjabloon gezet. Een keer,
 * niet per document: het bestand verandert niet tussen twee offertes.
 */
@ApplicationScoped
public class Brand {

    private static final Logger LOG = Logger.getLogger(Brand.class);
    private static final String RESOURCE = "/seed-images/logo.png";

    private final String logoDataUri;

    public Brand() {
        this.logoDataUri = load();
    }

    public String logoDataUri() {
        return logoDataUri;
    }


    private String load() {
        try (InputStream in = Brand.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOG.warnf("Geen logo gevonden op %s; documenten krijgen alleen tekst", RESOURCE);
                return null;
            }
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
        } catch (IOException e) {
            LOG.warn("Logo kon niet gelezen worden", e);
            return null;
        }
    }
}
