package be.enrosed.publicform;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Keyed hashes keep IP addresses, emails and idempotency keys out of persistence. */
@ApplicationScoped
public class PublicFormHasher {
    private final byte[] secret;

    public PublicFormHasher(
            @ConfigProperty(name = "enrosed.public-forms.hmac-secret") String secret) {
        if (secret == null || secret.length() < 24) {
            throw new IllegalStateException("PUBLIC_FORM_HMAC_SECRET must contain at least 24 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String namespace, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(namespace.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return HexFormat.of().formatHex(mac.doFinal(
                    String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }

    public byte[] signature(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }

    public boolean signatureMatches(String value, byte[] supplied) {
        return supplied != null && MessageDigest.isEqual(signature(value), supplied);
    }
}
