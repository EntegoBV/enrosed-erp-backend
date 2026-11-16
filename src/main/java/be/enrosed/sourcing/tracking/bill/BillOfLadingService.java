package be.enrosed.sourcing.tracking.bill;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityChangeDto;
import be.enrosed.shared.audit.ActivityChangeSet;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.PurchaseDocument;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.tracking.PortAreas;
import be.enrosed.sourcing.tracking.VesselDirectory;
import be.enrosed.sourcing.tracking.VesselLinkStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Track &amp; trace from the bill of lading (Emre, 2026-11-15: "Moet ook met bill of lading kunnen, bekijk onze
 * documenten"). The bill in an order's dossier names the container, the bill number, the ship, the voyage and the
 * day the goods went on board, and nobody read it: a ship could only be linked by typing its IMO number, and the
 * departure was the day staff pressed "Vertrokken" (18 days late on PO-2026-004, so the carrier's dates were
 * refused as another voyage).
 *
 * <p>{@link #overview} reads the dossier and says, for every bill it could read, what taking that bill over would
 * change on the order. {@link #apply} takes one bill over. The apply works its proposal out again from the stored
 * document: of what a screen sends it only believes which document, an IMO number typed for a ship the ERP does
 * not know yet, and the reference the screen was opened with.</p>
 *
 * <p>A ship's name becomes an IMO number only through a ship linked before ({@link VesselDirectory}): there is no
 * keyless way to look a ship up by name. No source is asked here; the tracking check runs after the apply, outside
 * its transaction (SourcingResource).</p>
 */
@ApplicationScoped
public class BillOfLadingService {
    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");
    /** PDFs read for one overview: every bill of lading document, then the newest of the others. */
    private static final int CANDIDATES = 12;
    private static final int CACHED_READINGS = 200;
    /**
     * A ship sails up to a few days after loading, and the tracker may already have written that real day: a
     * stored departure this close after the on-board day stands (the window TrackingDateSync.voyageDate uses).
     */
    private static final int SAILS_WITHIN_DAYS = 3;
    /** Never a bill of lading, whatever container they mention: payment proofs and the invoice kinds. */
    private static final Set<PurchaseDocument.Kind> NEVER = EnumSet.of(PurchaseDocument.Kind.PAYMENT_PROOF,
            PurchaseDocument.Kind.INVOICE, PurchaseDocument.Kind.COMMERCIAL_INVOICE, PurchaseDocument.Kind.PROFORMA_INVOICE);
    /** A scan cannot say what it is, its name can: "telex release.pdf", "BL EGLV….pdf", 提单 (bill), 电放 (telex release). */
    private static final Pattern NAMED_BILL = Pattern.compile("(?i)telex|release|b/?l\\b|bill|lading|waybill|提单|电放");

    private static final String NOT_PDF = "Geen PDF: dit bestand kan niet gelezen worden.";
    private static final String SCAN = "Scan zonder tekst: dit document kan niet gelezen worden.";
    private static final String NO_BILL = "Geen bill of lading herkend in dit document.";

    private static final Comparator<PurchaseDocument> NEWEST_FIRST = Comparator
            .comparing(BillOfLadingService::addedAt).thenComparing(PurchaseDocument::id).reversed();
    /**
     * Which bill to start on: a final bill with its on-board day names the ship that sailed and the day it did,
     * another final bill at least the ship, a draft only the plan. Within those a document filed as bill of lading
     * goes before a paper of another kind that happens to read as one, then the newest.
     */
    private static final Comparator<Listed> BEST_FIRST = Comparator
            .comparingInt((Listed item) -> !item.reading().finalCopy() ? 2 : item.reading().onBoard() == null ? 1 : 0)
            .thenComparing((Listed item) -> item.document().kind() != PurchaseDocument.Kind.BILL_OF_LADING)
            .thenComparing(Listed::document, NEWEST_FIRST);

    @Inject PurchaseOrderService purchaseOrders;
    @Inject SourcingRepositories.PurchaseOrders orders;
    @Inject VesselLinkStore links;
    @Inject VesselDirectory directory;
    @Inject ActivityLogService activity;

    /**
     * Readings by document id. A stored document never changes, and the sheet asks for the same dossier again
     * every time it opens; reading a PDF takes far longer than anything else here. The storage key is kept with
     * the reading, so an id can never answer for other bytes.
     */
    private final Map<Long, Read> readings = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Read> eldest) {
            return size() > CACHED_READINGS;
        }
    });

    /* ------------------------------------------------------------------ the answer (JSON names are the contract) */

    /**
     * One document of the dossier as it was read. readable: it counts as a bill of lading and the fields behind
     * note are filled; otherwise note says in Dutch why it could not be used and those fields are empty.
     */
    public record DocumentReading(long documentId, String filename, String label, String kind, String kindLabel,
            Instant addedAt, boolean readable, String note, boolean draft, String carrier, String billNumber,
            List<String> containers, String vessel, String voyage, String portOfLoading, String portOfDischarge,
            LocalDate onBoard, LocalDate issuedOn) {}

    /**
     * What taking over one readable bill would change. reference: the text the order would hold (the current text
     * when it already holds what the bill gives). imo / imoFrom: the bill's ship as the ERP knows it, "LINKED" the
     * ship linked to this order, "KNOWN" a ship linked before under that name; both null when the IMO has to be
     * typed. shippedOn: the on-board day of a final bill when it can be this order's. portNote and warnings are
     * Dutch lines for the screen.
     */
    public record Proposal(long documentId, String reference, boolean referenceChanges, String vessel, String voyage,
            String imo, String imoFrom, boolean shipChanges, boolean billShipChanges, LocalDate shippedOn,
            boolean shippedOnChanges, String portNote, List<String> warnings) {}

    /** The order as it stands, beside the proposals. vesselName: null while no feed named the linked ship. */
    public record Current(String trackingReference, LocalDate shippedOn, String status, String vesselImo,
            String vesselName, String departurePort, String destinationPort, String billVessel, String billVoyage) {}

    /** documents: best bill first, then what could not be read. proposals: one per readable bill, same order. */
    public record Overview(long orderId, Current current, List<DocumentReading> documents, List<Proposal> proposals,
            Long bestDocumentId) {}

    /**
     * Take one bill over. imo: typed for a ship the ERP does not know yet, else null. expectedReference: the
     * reference the screen showed (null: none yet); another text stored by now refuses the apply.
     */
    public record ApplyRequest(Long documentId, String imo, String expectedReference) {}

    /* ------------------------------------------------------------------ what is kept while working */

    /** What a stored document reads as. text: the PDF gave lines at all (a scan gives none). */
    private record Read(String storageKey, boolean text, BillParser.Reading reading) {}

    /** A document the overview shows: a bill with its reading, or a paper with the reason it could not be read. */
    private record Listed(PurchaseDocument document, BillParser.Reading reading, String note) {
        boolean readable() {
            return note == null;
        }
    }

    /**
     * The order's ship as it stands: the link that counts for the current reference, the name a feed gave that
     * ship (null while none did), and what the final bill taken over before named.
     */
    private record Linked(VesselLinkStore.Link link, String name, VesselLinkStore.BillShip bill) {
        String imo() {
            return link == null ? null : link.imo();
        }
    }

    /** The reference text a bill leads to; changes is false when the order's text already holds what the bill gives. */
    record Ref(String text, boolean changes) {}

    /* ------------------------------------------------------------------ reading the dossier */

    /** What the bills of lading in the dossier say and what each would change. Reads stored documents only. */
    public Overview overview(long orderId) {
        PurchaseOrder order = purchaseOrders.get(orderId);
        List<Listed> listed = listed(purchaseOrders.documents(orderId));
        Linked ship = linked(order);
        LocalDate today = LocalDate.now(BRUSSELS);
        List<DocumentReading> documents = new ArrayList<>();
        List<Proposal> proposals = new ArrayList<>();
        for (Listed item : listed) {
            documents.add(shown(item));
            if (!item.readable()) continue;
            /* A draft may be all that reads while the final bill lies beside it as a scan: say so on the draft. */
            boolean newerUnread = listed.stream().anyMatch(other -> !other.readable()
                    && NEWEST_FIRST.compare(other.document(), item.document()) < 0);
            proposals.add(proposal(order, ship, item.document(), item.reading(), newerUnread, today));
        }
        Long best = listed.stream().filter(Listed::readable).findFirst().map(item -> item.document().id()).orElse(null);
        return new Overview(orderId, new Current(blankToNull(order.trackingReference()), order.shippedOn(),
                order.status().name(), ship.imo(), ship.name(), order.departurePort(), order.destinationPort(),
                ship.bill().vessel(), ship.bill().voyage()), List.copyOf(documents), List.copyOf(proposals), best);
    }

    /**
     * The documents worth showing, the best bill first. Every document filed as bill of lading is read (at most
     * five exist) and always listed, with the reason when it could not be used. Of the other PDFs only the newest
     * are read, up to {@link #CANDIDATES} in all: a telex release is often filed as "Andere". Such a paper is
     * listed when it reads as a bill that names a ship (a customs paper that only mentions the container is not
     * a bill), or when it is a scan whose name says it is one. Everything else is left out.
     */
    private List<Listed> listed(List<PurchaseDocument> dossier) {
        List<PurchaseDocument> filed = new ArrayList<>(), others = new ArrayList<>();
        for (PurchaseDocument document : dossier) {
            if (!considered(document)) continue;
            if (document.kind() == PurchaseDocument.Kind.BILL_OF_LADING) filed.add(document);
            else if (pdf(document) && document.sizeBytes() <= BillText.MAX_BYTES) others.add(document);
        }
        others.sort(NEWEST_FIRST);
        long room = Math.max(0, CANDIDATES - filed.stream().filter(BillOfLadingService::pdf).count());
        List<Listed> bills = new ArrayList<>(), unread = new ArrayList<>();
        for (PurchaseDocument document : filed) {
            if (!pdf(document)) {
                unread.add(new Listed(document, BillParser.Reading.NONE, NOT_PDF));
                continue;
            }
            Read read = read(document);
            if (read.reading().bill()) bills.add(new Listed(document, read.reading(), null));
            else unread.add(new Listed(document, BillParser.Reading.NONE, read.text() ? NO_BILL : SCAN));
        }
        for (PurchaseDocument document : others.stream().limit(room).toList()) {
            Read read = read(document);
            if (countsAsBill(document, read.reading())) bills.add(new Listed(document, read.reading(), null));
            else if (!read.text() && NAMED_BILL.matcher(Objects.toString(document.label(), "") + " "
                    + Objects.toString(document.originalFilename(), "")).find())
                unread.add(new Listed(document, BillParser.Reading.NONE, SCAN));
        }
        bills.sort(BEST_FIRST);
        unread.sort(Comparator.comparing(Listed::document, NEWEST_FIRST));
        bills.addAll(unread);
        return bills;
    }

    /** Papers of a payment and the invoice kinds are never read as a bill. */
    private static boolean considered(PurchaseDocument document) {
        return document.paymentId() == null && !NEVER.contains(document.kind());
    }

    /** The upload keeps whatever type the browser named, so the file name counts too. */
    private static boolean pdf(PurchaseDocument document) {
        String type = Objects.toString(document.contentType(), "").strip().toLowerCase(Locale.ROOT);
        String name = Objects.toString(document.originalFilename(), "").strip().toLowerCase(Locale.ROOT);
        return type.startsWith("application/pdf") || name.endsWith(".pdf");
    }

    /**
     * The parser found a bill, and the paper is one: filed as bill of lading, or it names a ship. An arrival
     * notice or a customs declaration cites the container and the words "bill of lading" too, without a ship.
     */
    private static boolean countsAsBill(PurchaseDocument document, BillParser.Reading reading) {
        return reading.bill() && (document.kind() == PurchaseDocument.Kind.BILL_OF_LADING || reading.vessel() != null);
    }

    private Read read(PurchaseDocument document) {
        Read cached = readings.get(document.id());
        if (cached != null && Objects.equals(cached.storageKey(), document.storageKey())) return cached;
        Read nothing = new Read(document.storageKey(), false, BillParser.Reading.NONE);
        if (document.sizeBytes() > BillText.MAX_BYTES) return nothing;
        byte[] bytes;
        try (InputStream data = purchaseOrders.documentData(document)) {
            bytes = data.readAllBytes();
        } catch (IOException | RuntimeException missing) {
            /* The file is gone or the store did not answer: not read this time, and not remembered as unreadable. */
            return nothing;
        }
        List<BillText.Line> lines = BillText.read(bytes);
        BillParser.Reading reading;
        try {
            reading = BillParser.parse(lines);
        } catch (RuntimeException odd) {
            /* A form the parser trips over is a document without a bill, not an overview that fails. */
            reading = BillParser.Reading.NONE;
        }
        Read read = new Read(document.storageKey(), !lines.isEmpty(), reading);
        readings.put(document.id(), read);
        return read;
    }

    private static DocumentReading shown(Listed item) {
        PurchaseDocument document = item.document();
        BillParser.Reading reading = item.reading();
        return new DocumentReading(document.id(), document.originalFilename(), document.label(), document.kind().name(),
                document.kind().dutchLabel(), document.addedAt(), item.readable(), item.note(), reading.draft(),
                reading.carrier(), reading.billNumber(), reading.containers(), reading.vessel(), reading.voyage(),
                reading.portOfLoading(), reading.portOfDischarge(), reading.onBoard(), reading.issuedOn());
    }

    private Linked linked(PurchaseOrder order) {
        VesselLinkStore.Link link = links.find(order);
        return new Linked(link, link == null ? null : directory.nameOf(link.imo()).orElse(null), links.billShip(order.id()));
    }

    /* ------------------------------------------------------------------ what a bill would change */

    private Proposal proposal(PurchaseOrder order, Linked ship, PurchaseDocument document, BillParser.Reading reading,
                              boolean newerUnread, LocalDate today) {
        Ref reference = reference(order.trackingReference(), reading);
        String vessel = reading.vessel();
        /* The linked ship named differently by its own feed is a known ship that is not the bill's. */
        boolean otherShipLinked = vessel != null && ship.name() != null && !VesselDirectory.sameName(ship.name(), vessel);
        String imo = null, imoFrom = null;
        if (vessel != null && ship.name() != null && !otherShipLinked) {
            imo = ship.imo();
            imoFrom = "LINKED";
        } else if (vessel != null) {
            imo = directory.imoOf(vessel).orElse(null);
            imoFrom = imo == null ? null : "KNOWN";
        }
        /* A ship is bound to the reference text: an order that ends without one has nothing to link the ship to. */
        boolean shipChanges = imo != null && reference.text() != null && !imo.equals(ship.imo());

        LocalDate onBoard = reading.onBoard();
        /* Only a final bill's day, and only a day that can be this order's: a bill of another order in the dossier,
           or a year read wrong, lies before the order date or after today. */
        LocalDate shippedOn = reading.finalCopy() && onBoard != null && order.orderDate() != null
                && !onBoard.isBefore(order.orderDate()) && !onBoard.isAfter(today) ? onBoard : null;
        boolean open = order.status() == PurchaseOrderStatus.BESTELD || order.status() == PurchaseOrderStatus.ONDERWEG;
        boolean shippedOnChanges = open && shippedOn != null && (order.shippedOn() == null
                || order.shippedOn().isBefore(shippedOn) || order.shippedOn().isAfter(shippedOn.plusDays(SAILS_WITHIN_DAYS)));

        List<String> warnings = new ArrayList<>();
        if (reading.draft()) warnings.add("Concept-B/L: het schip kan nog wijzigen.");
        if (newerUnread) warnings.add("Een nieuwere B/L in het dossier kan niet gelezen worden: controleer schip en vertrek zelf.");
        if (reading.containers().size() > 1) warnings.add("Meerdere containers op deze B/L: de referentie volgt er één.");
        if (otherShipLinked) warnings.add("Gekoppeld schip is " + ship.name() + "; de B/L noemt " + vessel + ".");
        if (order.status() == PurchaseOrderStatus.BESTELD && shippedOn != null)
            warnings.add("Volgens de B/L is de container aan boord; de order staat nog op Besteld.");

        return new Proposal(document.id(), reference.text(), reference.changes(), vessel, reading.voyage(), imo, imoFrom,
                shipChanges, !billShip(reading).equals(ship.bill()), shippedOn, shippedOnChanges, portNote(order, reading),
                List.copyOf(warnings));
    }

    /**
     * The reference a bill leads to. One container: "&lt;container&gt; · B/L &lt;number&gt;", the form the orders in
     * production already carry (the tracker follows the container, the date lookup finds the Evergreen bill); no
     * container: "BL: &lt;number&gt;". Several containers: the one the current text already names, never a guess;
     * without such a one the bill number alone. A text that already holds what the bill gives is left as it is
     * (compared in upper case, letters and digits only), so a reference typed with more in it is not rewritten.
     */
    static Ref reference(String current, BillParser.Reading reading) {
        String held = plain(current);
        String number = blankToNull(reading.billNumber());
        List<String> containers = reading.containers();
        String container = null;
        if (containers.size() == 1) {
            container = containers.get(0);
        } else if (containers.size() > 1) {
            List<String> named = containers.stream().filter(candidate -> held.contains(plain(candidate))).toList();
            if (named.size() == 1) container = named.get(0);
        }
        /* Nothing to give: a bill without either, or several containers and no number to fall back on. */
        if (container == null && number == null) return new Ref(containers.isEmpty() ? null : blankToNull(current), false);
        boolean holds = (container == null || held.contains(plain(container))) && (number == null || held.contains(plain(number)));
        if (holds) return new Ref(blankToNull(current), false);
        return new Ref(container == null ? "BL: " + number : number == null ? container : container + " · B/L " + number, true);
    }

    /** What the apply stores as "the ship on the bill": a final bill's ship and voyage; a draft's ship can still change. */
    private static VesselLinkStore.BillShip billShip(BillParser.Reading reading) {
        return reading.finalCopy() ? VesselLinkStore.BillShip.of(reading.vessel(), reading.voyage()) : VesselLinkStore.BillShip.NONE;
    }

    /**
     * The bill's ports beside the order's when they are not the same places (a port and its terminals are one,
     * {@link PortAreas}); null when they agree or the bill names none. The order's ports are not changed from
     * here: the line only tells why a source's dates may be refused for this route.
     */
    private static String portNote(PurchaseOrder order, BillParser.Reading reading) {
        String loading = reading.portOfLoading(), discharge = reading.portOfDischarge();
        if (loading == null && discharge == null) return null;
        boolean same = (loading == null || PortAreas.same(order.departurePort(), null, loading))
                && (discharge == null || PortAreas.same(order.destinationPort(), null, discharge));
        if (same) return null;
        return "B/L: " + (loading == null ? "—" : loading) + " → " + (discharge == null ? "—" : discharge)
                + " · order: " + order.departurePort() + " → " + order.destinationPort();
    }

    /* ------------------------------------------------------------------ taking a bill over */

    /**
     * Takes one bill over: the reference and the departure day, what the bill names as ship and voyage, and the
     * ship link. Refused in Dutch (409) when the order can no longer change, the document holds no readable bill,
     * the reference was changed since the screen read it, or the IMO number is not one or belongs to another ship.
     * No source is asked inside this transaction.
     */
    @Transactional
    public void apply(long orderId, ApplyRequest request) {
        if (request == null || request.documentId() == null)
            throw new BusinessRuleException("Kies de bill of lading die je wilt overnemen.");
        /* Found and read before the order is locked: reading a PDF is slow work no row lock should wait for, and
           a stored document never changes. Normally the overview has read it already. */
        PurchaseDocument document = purchaseOrders.document(orderId, request.documentId());
        BillParser.Reading reading = considered(document) && pdf(document) ? read(document).reading() : BillParser.Reading.NONE;

        PurchaseOrder order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new NotFoundException("Inkooporder", orderId));
        if (order.archivedAt() != null || order.status() == PurchaseOrderStatus.CONCEPT || order.status() == PurchaseOrderStatus.ONTVANGEN)
            throw new BusinessRuleException("De B/L overnemen kan bij een bestelde of vertrokken container.");
        if (!countsAsBill(document, reading))
            throw new BusinessRuleException("In dit document staat geen leesbare bill of lading.");
        if (!Objects.equals(blankToNull(request.expectedReference()), blankToNull(order.trackingReference())))
            throw new BusinessRuleException("De trackingreferentie is intussen gewijzigd. Open de container opnieuw.");
        String typed = request.imo() == null || request.imo().isBlank() ? null : request.imo().strip();
        if (typed != null && !VesselLinkStore.validImo(typed))
            throw new BusinessRuleException("Gebruik het IMO-nummer van het schip: zeven cijfers met een geldig controlecijfer.");
        if (typed != null && reading.vessel() != null) {
            /* A check digit says the number is an IMO number, not that it is this ship's: a number the ERP already
               knows under another name would put that ship's port calls, and its arrival, on this container. */
            String known = directory.nameOf(typed).orElse(null);
            if (known != null && !VesselDirectory.sameName(known, reading.vessel()))
                throw new BusinessRuleException("IMO " + typed + " is " + known + "; de B/L noemt " + reading.vessel() + ".");
        }

        Linked before = linked(order);
        Proposal proposal = proposal(order, before, document, reading, false, LocalDate.now(BRUSSELS));

        /* 1. The reference and the departure day, through the order's own narrow save: it moves the ship link with
              the reference and writes the order's history line. */
        PurchaseOrder now = order;
        boolean saved = proposal.referenceChanges() || proposal.shippedOnChanges();
        if (saved) {
            now = purchaseOrders.changeTracking(orderId, proposal.referenceChanges() ? proposal.reference() : null,
                    proposal.shippedOnChanges() ? proposal.shippedOn() : null);
        }
        /* 2. What the bill names as ship and voyage. After step 1, which clears these columns when the reference
              now names another shipment; no order entity is held across the steps (each lock reloads it). */
        VesselLinkStore.BillShip named = billShip(reading);
        links.saveBillShip(orderId, named);
        /* 3. The ship: the IMO typed, else the one the ERP knows for the bill's ship. */
        String imo = typed != null ? typed : proposal.imo();
        VesselLinkStore.Link link = links.find(now);
        if (imo != null && blankToNull(now.trackingReference()) != null && (link == null || !imo.equals(link.imo()))) {
            links.save(orderId, imo, now.trackingReference());
            link = links.find(now);
        }
        /* 4. One line in the order's history, with what step 1 did not already write there. It is also written
              when only step 1 changed something: its own line reads "Inkooporder bijgewerkt", as a form save
              does, and this one says the bill of lading was where it came from. Each value is compared with how
              the order stood before the apply, so a ship link that step 1 let go is told here too. */
        List<ActivityChangeDto> changes = ActivityChangeSet.create()
                .add("billVessel", "B/L-schip", before.bill().vessel(), named.vessel())
                .add("billVoyage", "B/L-reis", before.bill().voyage(), named.voyage())
                .add("vesselImo", "Schip (IMO)", before.imo(), link == null ? null : link.imo())
                .build();
        if (saved || !changes.isEmpty()) {
            activity.record(ActivityLogService.ACTION_UPDATED, ActivityLogService.ENTITY_PURCHASE_ORDER,
                    Long.toString(orderId), now.number(), "Track & trace bijgewerkt uit de bill of lading", changes);
        }
    }

    /* ------------------------------------------------------------------ small things */

    private static Instant addedAt(PurchaseDocument document) {
        return document.addedAt() == null ? Instant.EPOCH : document.addedAt();
    }

    /** Upper case, letters and digits only: how a reference text and a bill's numbers are compared. */
    private static String plain(String text) {
        return text == null ? "" : text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
