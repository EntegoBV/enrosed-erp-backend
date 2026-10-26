package be.enrosed.finance.invoices;

import be.enrosed.finance.odoo.OdooFailure;
import be.enrosed.finance.odoo.OdooInvoiceSearch;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** The real beans as the server builds them: under the test config Odoo is off and the demo flag is false. */
@QuarkusTest
class OdooInvoicesWiringTest {
    @Inject OdooInvoices invoices;
    @Inject OdooInvoiceSearch search;

    @Test void withTheDefaultTestConfigTheFinderIsNotConnectedAndNeverShowsTheDemo() {
        assertEquals(new OdooInvoices.Availability("NOT_CONNECTED","DISABLED",null),invoices.availability());
        assertEquals("DISABLED",search.problem());
        var page=invoices.search(new OdooInvoiceSearch.Query("IN",Set.of("in_invoice","in_refund"),List.of(),null,null,
                Set.of(),null,null,50));
        assertTrue(page.moves().isEmpty());assertFalse(page.more());
        assertEquals("DISABLED",assertThrows(OdooFailure.class,()->invoices.find(900001)).code);
        assertEquals("DISABLED",assertThrows(OdooFailure.class,()->invoices.attachment(900001,9000011)).code);
    }
}
