package be.enrosed.finance.odoo;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class OdooPurchaseSnapshotTest {
    final ObjectMapper json=new ObjectMapper();
    OdooDocumentService service(ObjectNode invoice) throws Exception {
        var service=new OdooDocumentService();service.config=OdooClientTest.config();service.client=mock(OdooClient.class);
        when(service.client.fields("account.move")).thenReturn(Set.of("id","name","ref","partner_id","invoice_date","move_type","state","currency_id","amount_total","company_id","amount_untaxed","amount_tax","write_date","peppol_move_state","peppol_message_uuid"));
        when(service.client.search(eq("account.move"),anyList(),anyList(),eq(1),anyString())).thenReturn(json.createArrayNode().add(invoice));
        when(service.client.search(eq("res.partner"),anyList(),anyList(),eq(1),anyString())).thenReturn(json.readTree("[{\"id\":9,\"name\":\"Fixture Supplier\",\"vat\":\"BE0123456789\",\"country_id\":[20,\"Belgium\"]}]"));
        when(service.client.search(eq("res.country"),anyList(),anyList(),eq(1),anyString())).thenReturn(json.readTree("[{\"id\":20,\"code\":\"BE\"}]"));
        when(service.client.search(eq("ir.attachment"),anyList(),anyList(),anyInt(),anyString())).thenReturn(json.createArrayNode());return service;
    }
    ObjectNode invoice() throws Exception{return (ObjectNode)json.readTree("{\"id\":10,\"name\":\"BILL/10\",\"ref\":\"REF-10\",\"partner_id\":[9,\"Fixture Supplier\"],\"invoice_date\":\"2026-09-01\",\"move_type\":\"in_invoice\",\"state\":\"draft\",\"currency_id\":[1,\"EUR\"],\"company_id\":[7,\"Fixture\"],\"amount_total\":1400,\"amount_untaxed\":1214.40,\"amount_tax\":185.60,\"amount_residual\":0,\"write_date\":\"2026-09-01 12:00:00\",\"peppol_move_state\":\"done\"}");}
    @Test void receivedDraftUsesOriginalGrossAndMixedTaxNotPrepaidResidual() throws Exception {
        var result=service(invoice()).purchaseSnapshot(10);assertEquals(new BigDecimal("1400.00"),result.totalEur());assertEquals(new BigDecimal("1214.40"),result.netEur());assertEquals(new BigDecimal("185.60"),result.vatEur());assertEquals("draft",result.document().state());assertEquals(64,result.hash().length());
    }
    @Test void supplierCreditHasSignedFinancialFactsButOriginalDocumentRemainsPositive() throws Exception {
        var invoice=invoice().put("move_type","in_refund");var result=service(invoice).purchaseSnapshot(10);assertEquals(new BigDecimal("-1400.00"),result.totalEur());assertEquals(new BigDecimal("1400.00"),result.document().total());
    }
    @Test void changedOriginalAmountsChangeSnapshotHashAndWrongCompanyIsRejected() throws Exception {
        var invoice=invoice();String hash=service(invoice).purchaseSnapshot(10).hash();invoice.put("amount_total",1401).put("amount_tax",186.60);assertNotEquals(hash,service(invoice).purchaseSnapshot(10).hash());invoice.set("company_id",json.createArrayNode().add(8).add("Other"));assertEquals("INVALID_RESPONSE",assertThrows(OdooFailure.class,()->service(invoice).purchaseSnapshot(10)).code);
    }
    @Test void cancelledForeignCurrencyAndInconsistentFinancialDataFailClosed() throws Exception {
        var invoice=invoice().put("state","cancel");assertEquals("PURCHASE_DOCUMENT_UNAVAILABLE",assertThrows(OdooFailure.class,()->service(invoice).purchaseSnapshot(10)).code);
        invoice.put("state","draft").set("currency_id",json.createArrayNode().add(2).add("USD"));assertEquals("PURCHASE_CURRENCY_UNSUPPORTED",assertThrows(OdooFailure.class,()->service(invoice).purchaseSnapshot(10)).code);
        invoice.set("currency_id",json.createArrayNode().add(1).add("EUR"));invoice.put("amount_tax",1);assertEquals("INVALID_RESPONSE",assertThrows(OdooFailure.class,()->service(invoice).purchaseSnapshot(10)).code);
    }
    @Test void durableSourceMigrationIsIncludedInDeployment() throws Exception {
        String file="odoo-cost-import-postgresql.sql";String sql=Files.readString(Path.of("docs/migrations/2026-10-13",file));
        assertTrue(sql.contains("UNIQUE (source_namespace, company_id, odoo_move_id)"));assertTrue(sql.contains("UNIQUE (company_cost_id)"));assertFalse(sql.contains("REFERENCES company_cost"));
        assertTrue(Files.readString(Path.of("Dockerfile")).contains("COPY docs/migrations/2026-10-13/"+file));assertTrue(Files.readString(Path.of("scripts/run-postgresql-schema-migrations.sh")).contains("--file=/app/migrations/"+file));
    }

    @Test void incomingPickerItemUsesFlatDocumentJsonContract() throws Exception {
        var document=service(invoice()).purchaseSnapshot(10).document();
        var node=json.valueToTree(new OdooPurchaseImportService.Item(document,12L,true,true,null));
        assertEquals(10,node.path("id").asLong());assertEquals("draft",node.path("state").asText());assertEquals("Fixture Supplier",node.path("partner").asText());
        assertFalse(node.has("document"));assertEquals(12,node.path("companyCostId").asLong());assertTrue(node.path("registered").asBoolean());
    }
}
