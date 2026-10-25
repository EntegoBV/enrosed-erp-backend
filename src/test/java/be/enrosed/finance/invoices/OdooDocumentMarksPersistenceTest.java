package be.enrosed.finance.invoices;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.util.concurrent.ThreadLocalRandom;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class OdooDocumentMarksPersistenceTest {
    @Inject OdooDocumentMarks marks;
    @Inject EntityManager em;

    /** Every test writes under its own namespace token, so the shared test database never mixes runs. */
    static String token() { return "ZQX"+ThreadLocalRandom.current().nextInt(100000,1000000); }

    /** A fresh read in its own transaction (never a row the test's own session still holds). */
    OdooDocumentMarkEntity stored(String namespace,long moveId) {
        return QuarkusTransaction.requiringNew().call(()->em.find(OdooDocumentMarkEntity.class,namespace+":"+moveId));
    }

    @Test void putReplaceAndClearAMarkPerNamespace() {
        String ns=token(),other=token()+"b";
        var handled=marks.put(ns,900001,"HANDLED",null,"  zit in de jaarafrekening ","emre");
        assertEquals("HANDLED",handled.resolution());assertNull(handled.purchaseOrderId());
        assertEquals("zit in de jaarafrekening",handled.note());assertEquals("emre",handled.markedBy());
        assertNotNull(handled.markedAt());assertEquals(ns,handled.namespace());assertEquals(900001,handled.moveId());
        marks.put(ns,900004,"HANDLED",null,null,"berat");
        marks.put(other,900001,"CONTAINER",41L,null,"emre");

        var all=marks.all(ns);
        assertEquals(2,all.size());assertEquals("HANDLED",all.get(900001L).resolution());assertEquals("berat",all.get(900004L).markedBy());
        assertEquals(1,marks.all(other).size());

        var replaced=marks.put(ns,900001,"CONTAINER",41L,"zit in PO-2026-012","berat");
        assertEquals("CONTAINER",replaced.resolution());assertEquals(41L,replaced.purchaseOrderId());
        var row=stored(ns,900001);
        assertEquals("CONTAINER",row.resolution);assertEquals(41L,row.purchaseOrderId);assertEquals("zit in PO-2026-012",row.note);
        assertEquals("berat",row.markedBy);assertEquals(ns,row.namespace);assertEquals(900001L,row.moveId);
        assertEquals(2,marks.all(ns).size(),"a second put replaces, it does not add");
        assertEquals(41L,marks.all(ns).get(900001L).purchaseOrderId(),"a container mark keeps its order id");

        var back=marks.put(ns,900001,"HANDLED",null,null,"emre");
        assertNull(back.purchaseOrderId());assertNull(back.note());

        assertTrue(marks.clear(ns,900001));
        assertFalse(marks.clear(ns,900001),"clearing twice is harmless");
        assertNull(stored(ns,900001));
        assertEquals(1,marks.all(ns).size());
        assertEquals(1,marks.all(other).size(),"another namespace keeps its marks");
    }

    @Test void theStoreKeepsRowsValidForTheMigrationChecks() {
        String ns=token();
        assertThrows(IllegalArgumentException.class,()->marks.put(ns,1,"CONTAINER",null,null,"emre"));
        assertThrows(IllegalArgumentException.class,()->marks.put(ns,1,"HANDLED",41L,null,"emre"));
        assertThrows(IllegalArgumentException.class,()->marks.put(ns,1,"DONE",null,null,"emre"));
        assertThrows(IllegalArgumentException.class,()->marks.put(ns,1,"HANDLED",null,"x".repeat(201),"emre"));
        assertThrows(IllegalArgumentException.class,()->marks.put(ns,0,"HANDLED",null,null,"emre"));
        assertThrows(IllegalArgumentException.class,()->marks.put(" ",1,"HANDLED",null,null,"emre"));
        assertTrue(marks.all(ns).isEmpty());

        var longActor=marks.put(ns,2,"HANDLED",null,"x".repeat(200),"a".repeat(150));
        assertEquals(100,longActor.markedBy().length());assertEquals(200,longActor.note().length());
        assertEquals("systeem",marks.put(ns,3,"HANDLED",null,null,null).markedBy());
        assertTrue(marks.all(null).isEmpty());assertTrue(marks.all("").isEmpty());
        assertFalse(marks.clear(null,2));
    }
}
