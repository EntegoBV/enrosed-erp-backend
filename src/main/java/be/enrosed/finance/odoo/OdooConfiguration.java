package be.enrosed.finance.odoo;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable fields are read by collaborators, so inject the instance rather than a normal-scope client proxy. */
@Singleton
public class OdooConfiguration {
    final boolean enabled;
    final String baseUrl, database, apiKey;
    final Long companyId;
    final Set<Long> journalIds;

    @Inject
    public OdooConfiguration(
            @ConfigProperty(name="enrosed.odoo.enabled", defaultValue="false") boolean enabled,
            @ConfigProperty(name="enrosed.odoo.base-url") Optional<String> baseUrl,
            @ConfigProperty(name="enrosed.odoo.database") Optional<String> database,
            @ConfigProperty(name="enrosed.odoo.api-key") Optional<String> apiKey,
            @ConfigProperty(name="enrosed.odoo.company-id") Optional<Long> companyId,
            @ConfigProperty(name="enrosed.odoo.journal-ids") Optional<String> journalIds) {
        this.enabled=enabled;
        this.baseUrl=baseUrl.orElse("").strip().replaceAll("/+$", "");
        this.database=database.orElse("").strip(); this.apiKey=apiKey.orElse("").strip();
        this.companyId=companyId.orElse(null);
        Set<Long> ids=new LinkedHashSet<>();
        // Invalid optional settings should produce a safe status, not prevent application startup.
        try { for (String id:journalIds.orElse("").split(",")) if (!id.isBlank()) ids.add(Long.parseLong(id.strip())); }
        catch (NumberFormatException invalid) { ids.add(-1L); }
        this.journalIds=Set.copyOf(ids);
    }
    List<String> missing() {
        List<String> names=new ArrayList<>();
        if(baseUrl.isEmpty())names.add("ENROSED_ODOO_BASE_URL");
        if(database.isEmpty())names.add("ENROSED_ODOO_DATABASE");
        if(apiKey.isEmpty())names.add("ENROSED_ODOO_API_KEY");
        if(companyId==null)names.add("ENROSED_ODOO_COMPANY_ID");
        return List.copyOf(names);
    }
    String problem() {
        if(!enabled)return "DISABLED";
        if(!missing().isEmpty())return "NOT_CONFIGURED";
        try { validate(); return null; } catch(OdooFailure invalid) { return invalid.code; }
    }
    void requireReady() { String problem=problem(); if(problem!=null)throw new OdooFailure(problem); }
    void validate() {
        try {
            URI uri=URI.create(baseUrl);
            if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null
                    ||uri.getQuery()!=null||uri.getFragment()!=null||!uri.getPath().isEmpty()
                    ||(uri.getPort()!=-1&&uri.getPort()!=443)||companyId==null||companyId<=0
                    ||journalIds.stream().anyMatch(id->id<=0)||database.length()>128
                    ||database.chars().anyMatch(Character::isISOControl)||apiKey.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException();
        } catch(Exception invalid) { throw new OdooFailure("INVALID_CONFIGURATION"); }
    }
    String sourceNamespace() {
        requireReady();
        try {
            String identity=URI.create(baseUrl).getHost().toLowerCase(Locale.ROOT)+"|"+database+"|"+companyId;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception invalid) { throw new OdooFailure("INVALID_CONFIGURATION"); }
    }
}
