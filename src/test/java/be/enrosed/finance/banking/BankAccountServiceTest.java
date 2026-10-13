package be.enrosed.finance.banking;

import be.enrosed.finance.adapter.out.persistence.BankBalanceEntity;
import be.enrosed.finance.adapter.out.persistence.CostPaymentEntity;
import be.enrosed.finance.odoo.OdooFailure;
import be.enrosed.finance.odoo.OdooService;
import be.enrosed.sales.adapter.out.persistence.SalesPaymentEntity;
import be.enrosed.shared.BusinessRuleException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class BankAccountServiceTest {
    private static final String IBAN="BE12345678901234";
    private static final Instant AT=Instant.parse("2026-10-01T10:00:00Z");
    @Inject BankAccountService accounts;
    @Inject BankStatementService bank;
    @Inject EntityManager em;
    @InjectMock OdooService odoo;

    @Test @TestTransaction
    void derivesEveryAccountSourceAndImmediatelyProtectsExistingImports() {
        balance("  Kbc  ");receipt("RECEIPTS ONLY");costPayment("COSTS ONLY");
        imported(IBAN,"scope:1");
        assertEquals(List.of(IBAN,"COSTS ONLY","KBC","RECEIPTS ONLY"),accounts.list().stream().map(BankAccountService.Account::key).toList());
        var source=account(IBAN);assertEquals("ODOO",source.source());assertFalse(source.manualMovementsAllowed());assertNull(source.odooJournalId());
        assertEquals(0,em.createQuery("select count(a) from BankAccountEntity a",Long.class).getSingleResult());
        assertThrows(BusinessRuleException.class,()->bank.create(manual("be12 3456 7890 1234")));
        assertEquals(1,bank.list().size());verifyNoInteractions(odoo);
    }

    @Test @TestTransaction
    void archiveAndRestoreNeverTouchFinancialRowsOrPreventFurtherImports() {
        var reading=balance(IBAN);var receipt=receipt(IBAN);var cost=costPayment(IBAN);var line=imported(IBAN,"scope:2");
        accounts.archive("be12 3456 7890 1234");accounts.archive(IBAN);
        assertTrue(account(IBAN).archived());assertFalse(account(IBAN).manualMovementsAllowed());
        imported(IBAN,"scope:3");
        assertEquals(2,bank.list().size());assertTrue(account(IBAN).archived());
        assertEquals(IBAN,em.find(BankBalanceEntity.class,reading.id).account);
        assertEquals(IBAN,em.find(SalesPaymentEntity.class,receipt.id).bankAccount);
        assertEquals(IBAN,em.find(CostPaymentEntity.class,cost.id).account);
        assertNotNull(em.find(BankStatementLineEntity.class,line.id()));
        var restored=accounts.restore(IBAN);assertFalse(restored.archived());assertEquals("ODOO",restored.source());
        assertFalse(restored.manualMovementsAllowed());verifyNoInteractions(odoo);
    }

    @Test @TestTransaction
    void explicitAliasGroupsHistoryAndProtectsManualCreationWithoutRewritingIt() {
        var reading=balance("Kbc zichtrekening");var receipt=receipt("KBC ZICHTREKENING");
        var old=bank.create(manual("KBC ZICHTREKENING"));imported(IBAN,"scope:4");
        var changed=accounts.update(IBAN,new BankAccountService.Update("KBC zakelijk","ODOO",null,List.of("  kbc   zichtrekening  ")));
        assertEquals(List.of("KBC ZICHTREKENING"),changed.aliases());assertEquals(1,accounts.list().size());
        assertEquals("Kbc zichtrekening",em.find(BankBalanceEntity.class,reading.id).account);
        assertEquals("KBC ZICHTREKENING",em.find(SalesPaymentEntity.class,receipt.id).bankAccount);
        assertEquals("KBC ZICHTREKENING",em.find(BankStatementLineEntity.class,old.id).account);
        assertThrows(BusinessRuleException.class,()->bank.create(manual("Kbc zichtrekening")));
        assertThrows(BusinessRuleException.class,()->accounts.archive("KBC ZICHTREKENING"));
        assertThrows(BusinessRuleException.class,()->accounts.update(IBAN,new BankAccountService.Update("KBC","MANUAL",null,List.of())));
        accounts.update(IBAN,new BankAccountService.Update("KBC zakelijk","ODOO",null,List.of()));
        assertEquals(2,accounts.list().size());assertTrue(account("KBC ZICHTREKENING").manualMovementsAllowed());
    }

    @Test @TestTransaction
    void aliasesCannotStealARegisteredNameOrAnotherImportedAccount() {
        imported(IBAN,"scope:5");imported("NL12345678901234","scope:6");
        accounts.update("CASH",new BankAccountService.Update("Kas","MANUAL",null,List.of("CONTANT")));
        assertThrows(BusinessRuleException.class,()->accounts.update(IBAN,new BankAccountService.Update("Bank","ODOO",null,List.of("CONTANT"))));
        assertThrows(BusinessRuleException.class,()->accounts.update(IBAN,new BankAccountService.Update("Bank","ODOO",null,List.of("CASH"))));
        assertThrows(BusinessRuleException.class,()->accounts.update(IBAN,new BankAccountService.Update("Bank","ODOO",null,List.of("NL12345678901234"))));
        assertEquals(List.of("CONTANT"),account("CASH").aliases());
    }

    @Test @TestTransaction
    void manualAccountCanBeArchivedAndRestoredWithItsHistoryAndAliasesIntact() {
        var row=bank.create(manual("CASH"));
        accounts.update("CASH",new BankAccountService.Update("Kas","MANUAL",null,List.of("CONTANT")));
        accounts.archive("CASH");assertThrows(BusinessRuleException.class,()->bank.create(manual("CONTANT")));
        assertNotNull(em.find(BankStatementLineEntity.class,row.id));
        assertTrue(accounts.restore("CASH").manualMovementsAllowed());bank.create(manual("CONTANT"));
        assertEquals(1,accounts.list().size());assertEquals(2,bank.list().size());
    }

    @Test @TestTransaction
    void aNewOdooClaimRequiresRemoteVerificationAndItsMappingRejectsProviderDrift() {
        assertThrows(BusinessRuleException.class,()->accounts.update(IBAN,new BankAccountService.Update("Bank","ODOO",null,List.of())));
        when(odoo.verifyBankAccount(13,IBAN)).thenReturn(new OdooService.VerifiedBankAccount("scope",1,13,IBAN));
        var mapped=accounts.update(IBAN,new BankAccountService.Update("Bank","ODOO",13L,List.of("OLD KBC")));
        assertEquals(13L,mapped.odooJournalId());assertFalse(mapped.manualMovementsAllowed());
        verify(odoo).verifyBankAccount(13,IBAN);
        accounts.validateImportAccount("scope",1,13,IBAN);
        assertEquals("JOURNAL_ACCOUNT_MISMATCH",assertThrows(OdooFailure.class,()->accounts.validateImportAccount("scope",1,13,"BE98765432109876")).code);
        assertThrows(OdooFailure.class,()->accounts.validateImportAccount("other-instance",1,13,IBAN));
        assertThrows(OdooFailure.class,()->accounts.validateImportAccount("scope",2,13,IBAN));
        assertThrows(OdooFailure.class,()->accounts.validateImportAccount("scope",1,14,IBAN));
        assertThrows(OdooFailure.class,()->accounts.validateImportAccount("scope",1,14,"OLD KBC"));
        var renamed=accounts.update(IBAN,new BankAccountService.Update("New label","ODOO",null,List.of("OLD KBC")));
        assertEquals(13L,renamed.odooJournalId(),"label-only updates preserve a verified binding");
        accounts.update(IBAN,new BankAccountService.Update("Another label","ODOO",13L,List.of("OLD KBC")));
        verify(odoo,times(1)).verifyBankAccount(13,IBAN);
    }

    @Test @TestTransaction
    void rejectedRemoteAccountVerificationDoesNotPersistAnAuthorityClaim() {
        when(odoo.verifyBankAccount(13,IBAN)).thenThrow(new OdooFailure("JOURNAL_ACCOUNT_MISMATCH"));
        assertThrows(OdooFailure.class,()->accounts.update(IBAN,new BankAccountService.Update("Bank","ODOO",13L,List.of())));
        assertTrue(accounts.list().isEmpty());assertNull(em.find(BankAccountEntity.class,IBAN));
    }

    @Test @TestTransaction
    void aVerifiedImportRegistersTheJournalWithoutReactivatingOrRenamingTheAccount() {
        imported(IBAN,"scope:7");
        accounts.update(IBAN,new BankAccountService.Update("KBC zakelijk","ODOO",null,List.of("KBC OLD")));
        accounts.archive(IBAN);
        accounts.registerImportedAccount("scope",1,13,IBAN);
        accounts.registerImportedAccount("scope",1,13,IBAN);
        var registered=account(IBAN);assertEquals(13L,registered.odooJournalId());
        assertEquals("KBC zakelijk",registered.label());assertTrue(registered.archived());assertEquals(List.of("KBC OLD"),registered.aliases());
        assertThrows(OdooFailure.class,()->accounts.registerImportedAccount("other",1,13,IBAN));
        assertEquals("scope",em.find(BankAccountEntity.class,IBAN).odooSourceNamespace);
        assertEquals(1,bank.list().size());verifyNoInteractions(odoo);
    }

    @Test @TestTransaction
    void importedProvenanceCannotBeReboundToAnotherDatabaseAndSyntheticFallbackStaysUnbound() {
        imported(IBAN,"old-source:8");
        assertThrows(OdooFailure.class,()->accounts.registerImportedAccount("new-source",1,13,IBAN));
        assertNull(em.find(BankAccountEntity.class,IBAN));
        accounts.registerImportedAccount("scope",1,14,"ODOO-ABCD-J14");
        assertNull(em.find(BankAccountEntity.class,"ODOO-ABCD-J14"));
    }

    private BankAccountService.Account account(String key) {return accounts.list().stream().filter(value->value.key().equals(key)).findFirst().orElseThrow();}
    private BankStatementService.ImportResult imported(String account,String id) {
        return bank.importExternal(new BankStatementService.ExternalRequest(id,account,BigDecimal.TEN,AT,"Europe/Brussels","Transfer","Party",AT,false));
    }
    private BankStatementService.ManualRequest manual(String account) {
        return new BankStatementService.ManualRequest(account,BigDecimal.TEN,BankStatementService.Direction.INCOMING,AT,"Europe/Brussels","Cash","Party","manual"+account);
    }
    private BankBalanceEntity balance(String account) {
        var row=new BankBalanceEntity();row.account=account;row.date=LocalDate.of(2026,10,1);row.balanceEur=BigDecimal.TEN;row.createdAt=AT;em.persist(row);return row;
    }
    private SalesPaymentEntity receipt(String account) {
        var row=new SalesPaymentEntity();row.salesOrderId=99999;row.bankAccount=account;row.amountEur=BigDecimal.TEN;row.receivedAt=AT;row.recordedAt=AT;row.timeZone="Europe/Brussels";em.persist(row);return row;
    }
    private CostPaymentEntity costPayment(String account) {
        var row=new CostPaymentEntity();row.companyCostId=99999L;row.account=account;row.amountEur=BigDecimal.TEN;row.paidOn=LocalDate.of(2026,10,1);row.recordedAt=AT;em.persist(row);return row;
    }
}
