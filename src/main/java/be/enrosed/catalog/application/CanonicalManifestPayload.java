package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.CanonicalCatalogManifest;
import be.enrosed.shared.BusinessRuleException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;

import java.security.MessageDigest;
import java.util.HexFormat;

/** Verifies the generator's byte-level payload hash before typed deserialization loses missing/null detail. */
@ApplicationScoped
public class CanonicalManifestPayload {
    private final ObjectMapper json;

    public CanonicalManifestPayload(ObjectMapper json) {
        this.json = json;
    }

    public Parsed parse(JsonNode rawManifest) {
        if (!(rawManifest instanceof ObjectNode source)) {
            throw new BusinessRuleException("Canoniek catalogusmanifest moet een JSON-object zijn");
        }
        try {
            ObjectNode copy = source.deepCopy();
            JsonNode familiesNode = copy.get("families");
            if (!(familiesNode instanceof ArrayNode familyNodes)) {
                throw new BusinessRuleException("Manifest families ontbreekt");
            }
            familyNodes.forEach(familyNode -> familyNode.path("images").forEach(imageNode -> {
                if (imageNode.path("small") instanceof ObjectNode small) small.remove("bytesBase64");
                if (imageNode.path("large") instanceof ObjectNode large) large.remove("bytesBase64");
            }));
            ObjectNode payload = json.createObjectNode();
            payload.set("schemaVersion", copy.get("schemaVersion"));
            payload.set("categories", copy.get("categories"));
            payload.set("families", familyNodes);
            payload.set("validationSummary", copy.get("validationSummary"));
            String computed = sha256(json.writeValueAsBytes(payload));

            CanonicalCatalogManifest manifest = json.treeToValue(source, CanonicalCatalogManifest.class);
            String claimed = manifest.importDescriptor() == null
                    ? null : manifest.importDescriptor().payloadSha256();
            if (!computed.equals(claimed)) {
                throw new BusinessRuleException("importDescriptor.payloadSha256 komt niet overeen "
                        + "met de canonieke inhoud");
            }
            String importKey = manifest.importDescriptor().importKey();
            if (!("enrosed-catalog-" + computed.substring(0, 16)).equals(importKey)) {
                throw new BusinessRuleException("importDescriptor.importKey is niet afgeleid van payloadSha256");
            }
            return new Parsed(manifest, computed);
        } catch (BusinessRuleException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessRuleException("Canoniek catalogusmanifest kon niet worden gelezen");
        }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 ontbreekt", exception); }
    }

    public record Parsed(CanonicalCatalogManifest manifest, String verifiedPayloadSha256) {}
}
