package be.enrosed.finance.odoo;

import com.fasterxml.jackson.databind.*;
import be.enrosed.sales.application.*;
import be.enrosed.sales.domain.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OdooOutboxServiceTest {
    final ObjectMapper json=new ObjectMapper();
    final AtomicReference<OdooOutboxStore.Saved> saved=new AtomicReference<>();
    final AtomicReference<String> state=new AtomicReference<>("draft");
    final AtomicReference<String> peppol=new AtomicReference<>("ready");
    final AtomicReference<String> mode=new AtomicReference<>("test");
    @Test void advanceAndFinalSettlementDocumentsAreRefusedBeforeMappingOrdinaryInvoiceLines() throws Exception {
        var service=new OdooOutboxService();service.sales=mock(SalesOrderService.class);service.customers=mock(CustomerService.class);
        service.advances=mock(SalesAdvanceBillingService.class);
        var fixtures=new ObjectMapper().findAndRegisterModules();
        SalesOrder order=fixtures.readValue("{\"id\":17,\"customerId\":25,\"docType\":\"FACTUUR\",\"status\":\"UITGEREIKT\"}",SalesOrder.class);
        Customer customer=fixtures.readValue("{\"id\":25,\"company\":\"Buyer\"}",Customer.class);
        when(service.sales.get(17)).thenReturn(order);
        when(service.customers.get(25)).thenReturn(customer);
        when(service.advances.presentation(order)).thenReturn(new SalesAdvanceBillingService.Presentation(null,List.of()));
        assertEquals("OUTBOX_UNSUPPORTED_DOCUMENT",assertThrows(OdooFailure.class,()->service.localData(17)).code);
        verify(service.sales,never()).price(any(SalesOrder.class));
    }
    OdooOutboxService.LocalData local() {
        return new OdooOutboxService.LocalData(new OdooOutboxService.LocalPreview("F-2026-17","out_invoice","2026-10-13","2026-11-13","Buyer","BE0123456789",
                "BINNENLAND",new BigDecimal("21"),new BigDecimal("100.00"),new BigDecimal("21.00"),new BigDecimal("121.00"),
                List.of(new OdooOutboxService.Line("Roses",new BigDecimal("2"),new BigDecimal("50.00"),BigDecimal.ZERO,new BigDecimal("100.00"))),null,null),
                new OdooOutboxService.Identity("Buyer","BE0123456789","BE","Street 1","1000","Brussels"),
                new OdooOutboxService.Identity("Enrosed","BE1034273386","BE","Street 2","2400","Mol"));
    }
    OdooOutboxService.Snapshot snapshot() throws Exception {
        var local=local();return new OdooOutboxService.Snapshot(local,OdooOutboxService.hash(json.writeValueAsString(local)),25,7,8,9,10,1,null);
    }
    JsonNode remote() throws Exception {
        return json.readTree("{\"id\":30,\"name\":\"F-2026-17\",\"ref\":\"MARKER\",\"invoice_origin\":\"F-2026-17\",\"company_id\":[7,\"Enrosed\"],\"partner_id\":[25,\"Buyer\"],\"journal_id\":[8,\"Sales\"],\"currency_id\":[1,\"EUR\"],\"invoice_date\":\"2026-10-13\",\"invoice_date_due\":\"2026-11-13\",\"move_type\":\"out_invoice\",\"state\":\""+state.get()+"\",\"amount_untaxed\":100,\"amount_tax\":21,\"amount_total\":121,\"peppol_move_state\":\""+peppol.get()+"\"}");
    }
    OdooOutboxService service(String initialState) throws Exception {
        var service=spy(new OdooOutboxService());service.json=json;service.config=OdooClientTest.config();
        service.outbound=new OdooOutboundConfiguration(true,true,Optional.of(8L),Optional.of(9L),Optional.of("{\"BINNENLAND\":10}"),json);
        service.client=mock(OdooClient.class);service.store=mock(OdooOutboxStore.class);service.leases=mock(OdooSyncStateStore.class);
        doReturn(local()).when(service).localData(17);
        var snapshot=snapshot();String encoded=json.writeValueAsString(snapshot);
        saved.set(new OdooOutboxStore.Saved(17,service.config.sourceNamespace(),"MARKER",OdooOutboxService.hash(encoded),encoded,
                initialState,initialState.equals("PREVIEW")?null:30L,null,null,Instant.now()));
        when(service.store.get(17)).thenAnswer(call->saved.get());
        when(service.store.transition(eq(17L),anyString(),anyString(),any(),any(),any())).thenAnswer(call->{
            var previous=saved.get();Long id=call.getArgument(3);
            var next=new OdooOutboxStore.Saved(17,previous.sourceNamespace(),previous.marker(),previous.snapshotHash(),previous.snapshotJson(),
                    call.getArgument(2),id==null?previous.odooMoveId():id,call.getArgument(4),call.getArgument(5),Instant.now());saved.set(next);return next;
        });
        when(service.client.fields("account.move")).thenReturn(Set.of("id","name","ref","invoice_origin","company_id","partner_id","journal_id","currency_id",
                "invoice_date","invoice_date_due","move_type","state","amount_untaxed","amount_tax","amount_total","peppol_move_state","reversed_entry_id"));
        when(service.client.search(anyString(),anyList(),anyList(),anyInt(),anyString())).thenAnswer(call->{
            String model=call.getArgument(0);List<?> domain=call.getArgument(1);
            return switch(model) {
                case "res.company"->json.readTree("[{\"id\":7,\"vat\":\"BE1034273386\",\"currency_id\":[1,\"EUR\"]}]");
                case "res.currency"->json.readTree("[{\"id\":1}]");
                case "account.journal"->json.readTree("[{\"id\":8,\"currency_id\":false}]");
                case "account.account"->json.readTree("[{\"id\":9}]");
                case "account.tax"->json.readTree("[{\"id\":10,\"amount_type\":\"percent\",\"price_include\":false,\"amount\":21}]");
                case "account_edi_proxy_client.user"->json.readTree("[{\"id\":11,\"edi_mode\":\""+mode.get()+"\"}]");
                case "res.partner"->json.readTree("[{\"id\":25,\"name\":\"Buyer\",\"vat\":\"BE0123456789\",\"country_code\":\"BE\",\"street\":\"Street 1\",\"street2\":false,\"zip\":\"1000\",\"city\":\"Brussels\",\"company_id\":false}]");
                case "account.move.line"->json.readTree("[{\"id\":99,\"name\":\"Roses\",\"quantity\":2,\"price_unit\":50,\"discount\":0,\"price_subtotal\":100,\"account_id\":[9,\"Income\"],\"tax_ids\":[10]}]");
                case "account.move"->domain.toString().contains("[ref, =, MARKER]")&&saved.get().odooMoveId()==null?json.createArrayNode():json.createArrayNode().add(remote());
                default->throw new AssertionError(model);
            };
        });
        return service;
    }
    @Test void ambiguousCreatePersistsUnknownAndNeverBlindlyCreatesAgain() throws Exception {
        var service=service("PREVIEW");
        when(service.client.write(eq("account.move"),eq("create"),anyMap())).thenAnswer(call->{
            assertEquals("UNKNOWN_CREATE",saved.get().state(),"Must commit uncertainty before remote mutation");throw new OdooFailure("UNAVAILABLE");
        });
        var request=new OdooOutboxService.MutationRequest(saved.get().snapshotHash());
        assertEquals("UNAVAILABLE",assertThrows(OdooFailure.class,()->service.draft(17,request)).code);
        assertEquals("UNKNOWN_CREATE",saved.get().state());
        assertEquals("OUTBOX_RETRY_BLOCKED",assertThrows(OdooFailure.class,()->service.draft(17,request)).code);
        verify(service.client,times(1)).write(eq("account.move"),eq("create"),anyMap());
    }
    @Test void explicitSendPostsExactCheckedInvoiceThenUsesOnlyPeppolAndReportsQueueNotDelivery() throws Exception {
        var service=service("DRAFT");
        when(service.client.write(anyString(),anyString(),anyMap())).thenAnswer(call->{
            String model=call.getArgument(0),method=call.getArgument(1);Map<?,?> args=call.getArgument(2);
            assertEquals("UNKNOWN_SEND",saved.get().state());
            if(method.equals("action_post")){state.set("posted");return json.getNodeFactory().booleanNode(true);}
            if(model.equals("account.move.send.wizard")&&method.equals("create")) {
                assertEquals(List.of("peppol"),((Map<?,?>)((List<?>)args.get("vals_list")).getFirst()).get("sending_methods"));
                return json.getNodeFactory().numberNode(40);
            }
            assertEquals("action_send_and_print",method);assertEquals(false,args.get("allow_fallback_pdf"));peppol.set("processing");return json.createObjectNode();
        });
        var result=service.send(17,new OdooOutboxService.MutationRequest(saved.get().snapshotHash()));
        assertEquals("QUEUED",result.entry().state());assertEquals("processing",result.entry().peppolState());
        verify(service.client,times(3)).write(anyString(),anyString(),anyMap());
    }
    @Test void productionPeppolProxyAndMissingSnapshotConfirmationCannotMutate() throws Exception {
        var service=service("DRAFT");mode.set("prod");
        assertEquals("OUTBOX_TEST_MODE_REQUIRED",assertThrows(OdooFailure.class,()->service.send(17,new OdooOutboxService.MutationRequest(saved.get().snapshotHash()))).code);
        assertEquals("INVALID_REQUEST",assertThrows(OdooFailure.class,()->service.send(17,null)).code);
        verify(service.client,never()).write(anyString(),anyString(),anyMap());
    }
    @Test void changedTotalsOrNumberBlockPostingAndRefreshDoesNotMakeUnknownSendRetryable() throws Exception {
        var service=service("UNKNOWN_SEND");
        service.refresh(17);assertEquals("UNKNOWN_SEND",saved.get().state());
        var wrongTotal=remote().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)wrongTotal).put("amount_total",new BigDecimal("120.99"));
        assertEquals("OUTBOX_REMOTE_MISMATCH",assertThrows(OdooFailure.class,()->service.validateRemote(wrongTotal,saved.get(),snapshot(),false)).code);
        var wrongName=remote().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)wrongName).put("name","ODOO/2026/1");
        assertEquals("OUTBOX_REMOTE_MISMATCH",assertThrows(OdooFailure.class,()->service.validateRemote(wrongName,saved.get(),snapshot(),false)).code);
        verify(service.client,never()).write(anyString(),anyString(),anyMap());
    }
}
