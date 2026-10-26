package be.enrosed.finance.invoices;

import be.enrosed.finance.odoo.OdooDocumentService;
import be.enrosed.finance.odoo.OdooFailure;
import be.enrosed.finance.odoo.OdooInvoiceSearch;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The finder's one door to Odoo: the live search when Odoo is configured, the demo source when it is not and the demo
 * property is on, else "not connected" (empty pages). Read-only: nothing here writes to Odoo or to the database.
 */
@ApplicationScoped
public class OdooInvoices {
    static final Duration CACHE_TTL=Duration.ofSeconds(30);
    static final int CACHE_SIZE=64;

    public record Availability(String state /* READY | DEMO | NOT_CONNECTED */, String code /* null | DISABLED | NOT_CONFIGURED | INVALID_CONFIGURATION */,
                               String namespace /* READY: real namespace; DEMO: "demo"; NOT_CONNECTED: null */) {}

    private record CacheKey(String namespace, OdooInvoiceSearch.Query query) {}
    private record Cached(OdooInvoiceSearch.Page page, Instant storedAt) {}

    private final OdooInvoiceSearch search;
    private final OdooDocumentService documents;
    private final OdooInvoiceDemo demo;
    private final boolean demoFlag;
    private final Clock clock;
    /** Insertion order = age; guarded by itself. */
    private final LinkedHashMap<CacheKey,Cached> cache=new LinkedHashMap<>();

    @Inject OdooInvoices(OdooInvoiceSearch search, OdooDocumentService documents, OdooInvoiceDemo demo,
                         @ConfigProperty(name = "enrosed.invoice-finder.odoo-demo", defaultValue = "false") boolean demoFlag) {
        this(search,documents,demo,demoFlag,Clock.systemUTC());
    }

    /** Tests: same, with the clock the cache reads. */
    OdooInvoices(OdooInvoiceSearch search, OdooDocumentService documents, OdooInvoiceDemo demo, boolean demoFlag, Clock clock) {
        this.search=search;this.documents=documents;this.demo=demo;this.demoFlag=demoFlag;this.clock=clock;
    }

    /** A configured Odoo always wins; the demo only answers where no Odoo is configured. Never throws. */
    public Availability availability() {
        String problem=search.problem();
        if(problem==null) {
            try { return new Availability("READY",null,search.namespace()); }
            catch(OdooFailure failure) { return new Availability("NOT_CONNECTED",failure.code,null); }
        }
        if(demoFlag)return new Availability("DEMO",null,OdooInvoiceDemo.NAMESPACE);
        return new Availability("NOT_CONNECTED",problem,null);
    }

    /**
     * READY: live search (may throw OdooFailure). DEMO: in-memory demo with the same semantics. NOT_CONNECTED: empty page.
     * READY answers are cached 30 s per (namespace, query) (Query is a record: equality covers every field), at most 64
     * entries, oldest evicted first; failures are never cached. DEMO and NOT_CONNECTED are not cached.
     */
    public OdooInvoiceSearch.Page search(OdooInvoiceSearch.Query query) {
        Availability availability=availability();
        return switch(availability.state()) {
            case "READY" -> cached(availability.namespace(),query);
            case "DEMO" -> demo.search(query);
            default -> new OdooInvoiceSearch.Page(List.of(),false);
        };
    }

    /** READY: live; DEMO: demo; NOT_CONNECTED: throws OdooFailure(code). null when unknown. Never cached. */
    public OdooInvoiceSearch.Move find(long moveId) {
        Availability availability=availability();
        return switch(availability.state()) {
            case "READY" -> search.find(moveId);
            case "DEMO" -> demo.find(moveId);
            default -> throw new OdooFailure(availability.code());
        };
    }

    /** READY: delegates to OdooDocumentService.download(moveId, attachmentId); DEMO: demo bytes; NOT_CONNECTED: OdooFailure(code). */
    public OdooDocumentService.Download attachment(long moveId, long attachmentId) {
        Availability availability=availability();
        return switch(availability.state()) {
            case "READY" -> documents.download(moveId,attachmentId);
            case "DEMO" -> demo.attachment(moveId,attachmentId);
            default -> throw new OdooFailure(availability.code());
        };
    }

    private OdooInvoiceSearch.Page cached(String namespace,OdooInvoiceSearch.Query query) {
        CacheKey key=new CacheKey(namespace,frozen(query));
        synchronized(cache) {
            Cached hit=cache.get(key);
            if(hit!=null&&clock.instant().isBefore(hit.storedAt().plus(CACHE_TTL)))return hit.page();
        }
        // The call runs outside the lock: a slow Odoo must not hold other queries. A failure throws past the cache.
        OdooInvoiceSearch.Page page=search.search(query);
        Instant now=clock.instant();
        synchronized(cache) {
            cache.remove(key);
            cache.put(key,new Cached(page,now));
            cache.values().removeIf(entry->!now.isBefore(entry.storedAt().plus(CACHE_TTL)));
            Iterator<CacheKey> oldest=cache.keySet().iterator();
            while(cache.size()>CACHE_SIZE&&oldest.hasNext()) { oldest.next();oldest.remove(); }
        }
        return page;
    }

    /** An immutable copy for the cache key, so a caller changing its sets afterwards cannot change a cached entry. */
    private static OdooInvoiceSearch.Query frozen(OdooInvoiceSearch.Query query) {
        if(query==null)return null;
        List<OdooInvoiceSearch.Word> words=query.words()==null?null:query.words().stream()
                .map(word->word==null?null:new OdooInvoiceSearch.Word(word.text(),word.amount(),
                        word.alsoMoveIds()==null?null:Collections.unmodifiableSet(new LinkedHashSet<>(word.alsoMoveIds()))))
                .toList();
        return new OdooInvoiceSearch.Query(query.direction(),copy(query.moveTypes()),words,query.from(),query.to(),
                copy(query.excludeMoveIds()),copy(query.onlyMoveIds()),query.after(),query.limit());
    }

    private static <T> Set<T> copy(Set<T> values) {
        return values==null?null:Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
