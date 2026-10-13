package be.enrosed.finance.odoo;

import be.enrosed.finance.banking.BankStatementService;
import be.enrosed.finance.banking.BankAccountService;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OdooServiceTest {
    final ObjectMapper json=new ObjectMapper();
    JsonNode rows(String value) throws Exception { return json.readTree(value); }
    String row(long id,boolean matched,int journal,int currency) {
        return "{\"id\":"+id+",\"company_id\":[7,\"Enrosed\"],\"journal_id\":["+journal+",\"KBC\"],\"currency_id\":["+currency+",\"EUR\"],\"amount\":-12.34,\"amount_currency\":-50,\"date\":\"2026-10-13\",\"payment_ref\":\"INV/1\",\"partner_name\":\"Supplier\",\"write_date\":\"2026-10-13 10:00:00\",\"is_reconciled\":"+matched+"}";
    }
    OdooService service() {
        var service=spy(new OdooService());service.client=mock(OdooClient.class);service.config=OdooClientTest.config();
        service.state=mock(OdooSyncStateStore.class);service.bank=mock(BankStatementService.class);service.json=json;
        service.accounts=mock(BankAccountService.class);
        doReturn(new OdooService.Scope("Enrosed",1,Map.of(8L,new OdooService.Journal(8,"BE123",1),9L,new OdooService.Journal(9,"USD",2)))).when(service).scope();
        when(service.client.fields("account.bank.statement.line")).thenReturn(Set.copyOf(OdooService.BANK_FIELDS));
        when(service.bank.importExternal(any())).thenReturn(new BankStatementService.ImportResult(1L,BankStatementService.ImportOutcome.CREATED));
        return service;
    }
    @Test void pagesThroughMatchedAndUnmatchedLinesAndRetriesStableIdentitiesWithoutAllocation() throws Exception {
        var service=service();
        when(service.client.search(eq("account.bank.statement.line"),anyList(),eq(List.of("id")),eq(1),eq("id desc"))).thenReturn(rows("[{\"id\":4}]"));
        JsonNode first=rows("["+row(1,true,8,1)+","+row(2,false,8,1)+"]"),second=rows("["+row(3,false,9,2)+","+row(4,true,8,1)+"]");
        when(service.client.search(eq("account.bank.statement.line"),anyList(),eq(OdooService.BANK_FIELDS),eq(200),eq("id asc"))).thenReturn(first,second,first,second);
        var report=service.syncBank();
        assertTrue(report.complete());assertEquals(4,report.scanned());assertEquals(3,report.created());assertEquals(1,report.skippedNonEur());
        var captor=ArgumentCaptor.forClass(BankStatementService.ExternalRequest.class);verify(service.bank,times(3)).importExternal(captor.capture());
        assertTrue(captor.getAllValues().getFirst().sourceReconciled());assertFalse(captor.getAllValues().get(1).sourceReconciled());
        assertEquals(new BigDecimal("-12.34"),captor.getValue().amountEur(),"Use EUR journal amount, never original foreign amount");
        assertEquals(Instant.parse("2026-10-12T22:00:00Z"),captor.getValue().bookedAt(),"Odoo date is represented as Brussels midnight");
        when(service.bank.importExternal(any())).thenReturn(new BankStatementService.ImportResult(1L,BankStatementService.ImportOutcome.UNCHANGED));
        var retried=service.syncBank();assertEquals(3,retried.unchanged());assertEquals(0,retried.created());
        verify(service.bank,times(6)).importExternal(captor.capture());
        assertEquals(captor.getAllValues().get(0).sourceId(),captor.getAllValues().get(3).sourceId());
        verify(service.bank,never()).allocate(anyLong(),any());
        verify(service.accounts,times(2)).registerImportedAccount(service.config.sourceNamespace(),7,8,"BE123");
        verify(service.accounts,never()).registerImportedAccount(anyString(),anyLong(),eq(9L),anyString());
        var domains=ArgumentCaptor.forClass(List.class);verify(service.client,times(4)).search(eq("account.bank.statement.line"),domains.capture(),eq(OdooService.BANK_FIELDS),eq(200),eq("id asc"));
        assertFalse(domains.getAllValues().toString().contains("is_reconciled"));
        assertTrue(domains.getAllValues().stream().allMatch(domain->domain.toString().contains("[state, =, posted]")),"Draft/cancelled entries are not actual booked money");
        assertTrue(domains.getAllValues().stream().allMatch(domain->domain.contains(List.of("amount","!=",0))),"Zero-amount rows contain no bank money and cannot be imported as transactions");
        assertTrue(domains.getAllValues().toString().contains("[id, >, 2]"));
    }
    @Test void laterNetworkFailureIsAnHonestPartialReportAndLeaseIsReleased() throws Exception {
        var service=service();
        when(service.client.search(eq("account.bank.statement.line"),anyList(),eq(List.of("id")),eq(1),eq("id desc"))).thenReturn(rows("[{\"id\":4}]"));
        when(service.client.search(eq("account.bank.statement.line"),anyList(),eq(OdooService.BANK_FIELDS),eq(200),eq("id asc")))
                .thenReturn(rows("["+row(1,true,8,1)+"]")).thenThrow(new OdooFailure("RATE_LIMITED"));
        var report=service.syncBank();assertFalse(report.complete());assertEquals("RATE_LIMITED",report.code());assertEquals(1,report.created());
        verify(service.state).finish(anyString(),anyString(),contains("RATE_LIMITED"));
    }
    @Test void rejectsForeignCompanyOrCurrencyAndFractionalCentsBeforeImport() throws Exception {
        JsonNode good=rows(row(1,false,8,1));
        assertThrows(OdooFailure.class,()->OdooBankMapper.map(good,"scope","bank",99,8,1));
        assertThrows(OdooFailure.class,()->OdooBankMapper.map(good,"scope","bank",7,8,2));
        var fractional=good.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)fractional).put("amount",new BigDecimal("1.005"));
        assertThrows(OdooFailure.class,()->OdooBankMapper.map(fractional,"scope","bank",7,8,1));
    }
    OdooService scopedService(Set<String> fields,String accountFields) throws Exception {
        var service=new OdooService();service.client=mock(OdooClient.class);service.config=OdooClientTest.config();
        when(service.client.search(eq("res.company"),anyList(),anyList(),eq(1),eq("id asc")))
                .thenReturn(rows("[{\"id\":7,\"name\":\"Enrosed\",\"currency_id\":[1,\"EUR\"]}]"));
        when(service.client.search(eq("res.currency"),anyList(),anyList(),eq(1),eq("id asc")))
                .thenReturn(rows("[{\"id\":1,\"name\":\"EUR\"}]"));
        when(service.client.fields("account.journal")).thenReturn(fields);
        when(service.client.search(eq("account.journal"),anyList(),anyList(),eq(200),eq("id asc")))
                .thenReturn(rows("[{\"id\":13,\"company_id\":[7,\"Enrosed\"],\"currency_id\":false,\"name\":\"KBC\""+accountFields+"}]"),rows("[]"));
        return service;
    }
    @Test void readsTheCurrentSaasJournalAccountFieldWithoutRequestingAnUnavailableLegacyField() throws Exception {
        var service=scopedService(Set.of("bank_account_number"),",\"bank_account_number\":\"be12 3456 7890 1234\"");
        var scope=service.scope();
        assertEquals("BE12345678901234",scope.journals().get(13L).account());
        assertEquals(1,scope.journals().get(13L).currencyId());
        verify(service.client,times(2)).search(eq("account.journal"),anyList(),eq(List.of("id","company_id","currency_id","name","bank_account_number")),eq(200),eq("id asc"));
    }
    @Test void prefersTheCurrentAccountFieldAndFallsBackToTheLegacyValueWhenBlank() throws Exception {
        for(String current:List.of("be12 3456 7890 1234","   ")) {
            var service=scopedService(Set.of("bank_account_number","bank_acc_number"),",\"bank_account_number\":\""+current+"\",\"bank_acc_number\":\"be98 7654 3210 9876\"");
            assertEquals(current.isBlank()?"BE98765432109876":"BE12345678901234",service.scope().journals().get(13L).account());
        }
    }
    @Test void retainsLegacyJournalSchemaSupport() throws Exception {
        var service=scopedService(Set.of("bank_acc_number"),",\"bank_acc_number\":\"be98 7654 3210 9876\"");
        assertEquals("BE98765432109876",service.scope().journals().get(13L).account());
        verify(service.client,times(2)).search(eq("account.journal"),anyList(),eq(List.of("id","company_id","currency_id","name","bank_acc_number")),eq(200),eq("id asc"));
    }
    @Test void missingOrBlankJournalAccountKeepsTheStableSyntheticFallback() throws Exception {
        for(String fields:List.of("",",\"bank_account_number\":false",",\"bank_account_number\":\"   \",\"bank_acc_number\":\"\"")) {
            var service=scopedService(fields.isEmpty()?Set.of():Set.of("bank_account_number","bank_acc_number"),fields);
            assertEquals("ODOO-"+service.config.sourceNamespace().substring(0,12)+"-J13",service.scope().journals().get(13L).account());
        }
    }

    @Test void explicitAccountMappingRequiresTheActualRemoteAccountAndNeverAcceptsASyntheticFallback() throws Exception {
        var service=scopedService(Set.of("bank_account_number"),",\"bank_account_number\":\"be12 3456 7890 1234\"");
        var verified=service.verifyBankAccount(13,"BE12 3456 7890 1234");
        assertEquals("BE12345678901234",verified.account());assertEquals(13,verified.journalId());
        assertEquals(7,verified.companyId());assertEquals(service.config.sourceNamespace(),verified.sourceNamespace());
        var wrong=scopedService(Set.of("bank_account_number"),",\"bank_account_number\":\"BE12345678901234\"");
        assertEquals("JOURNAL_ACCOUNT_MISMATCH",assertThrows(OdooFailure.class,()->wrong.verifyBankAccount(13,"KBC")).code);
        var absent=scopedService(Set.of(),"");
        assertThrows(OdooFailure.class,()->absent.verifyBankAccount(13,"ODOO-"+absent.config.sourceNamespace().substring(0,12)+"-J13"));
    }

    @Test void journalMappingIsCheckedBeforeAnyStatementImport() throws Exception {
        var service=scopedService(Set.of("bank_account_number"),",\"bank_account_number\":\"BE12345678901234\"");
        service.accounts=mock(BankAccountService.class);
        doThrow(new OdooFailure("JOURNAL_ACCOUNT_MISMATCH")).when(service.accounts).validateImportAccount(anyString(),eq(7L),eq(13L),eq("BE12345678901234"));
        assertEquals("JOURNAL_ACCOUNT_MISMATCH",assertThrows(OdooFailure.class,service::scope).code);
    }

}
