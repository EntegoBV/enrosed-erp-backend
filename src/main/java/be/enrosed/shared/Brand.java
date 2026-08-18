package be.enrosed.shared;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

/**
 * The logo, ready to drop into a PDF or e-mail.
 *
 * openhtmltopdf cannot fetch files from the classpath, so the logo is read
 * once and placed in the template as a data URI. Once, not per document:
 * the file does not change between two quotes.
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
