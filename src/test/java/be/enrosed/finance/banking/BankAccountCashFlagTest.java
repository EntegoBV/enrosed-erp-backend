package be.enrosed.finance.banking;

import be.enrosed.finance.adapter.out.persistence.BankBalanceEntity;
import be.enrosed.finance.odoo.OdooService;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class BankAccountCashFlagTest {
    private static final String IBAN = "BE94731074082814";
    private static final Instant AT = Instant.parse("2026-10-01T10:00:00Z");
    @Inject BankAccountService accounts;
    @Inject BankStatementService bank;
    @Inject EntityManager em;
    @InjectMock OdooService odoo;

    @Test @TestTransaction
    void anAccountKnownOnlyFromItsRowsCountsWithoutARegistryRow() {
        imported(IBAN, "cash-flag:1");
        balance("Wise EUR");
        assertTrue(account(IBAN).countsInCash());
        assertTrue(account("WISE EUR").countsInCash());
        assertNull(em.find(BankAccountEntity.class, IBAN), "reading the flag creates nothing");
    }

    @Test @TestTransaction
    void switchingOffCreatesTheRowAndSwitchingOnClearsTheColumn() {
        balance("Wise EUR");
        var off = accounts.setCountsInCash("wise   eur", false);
        assertEquals("WISE EUR", off.key());
        assertFalse(off.countsInCash());
        assertFalse(off.archived(), "the account stays on Bank");
        var row = em.find(BankAccountEntity.class, "WISE EUR");
        assertNotNull(row);
        assertEquals(Boolean.TRUE, row.cashExcluded);
        assertFalse(account("WISE EUR").countsInCash());
        assertEquals("Telt niet mee in de kasprognose", latestAudit());

        var on = accounts.setCountsInCash("WISE EUR", true);
        assertTrue(on.countsInCash());
        assertNull(em.find(BankAccountEntity.class, "WISE EUR").cashExcluded, "counting again stores null, not false");
        assertEquals("Telt mee in de kasprognose", latestAudit());
    }

    @Test @TestTransaction
    void refusesAMissingChoiceAnAliasAndAnUnknownAccount() {
        imported(IBAN, "cash-flag:2");
        balance("Kbc zichtrekening");
        accounts.update(IBAN, new BankAccountService.Update("KBC-zichtrekening", "ODOO", null, List.of("KBC ZICHTREKENING")));
        assertEquals("Kies of deze rekening meetelt",
                assertThrows(BusinessRuleException.class, () -> accounts.setCountsInCash(IBAN, null)).getMessage());
        assertEquals("Beheer deze alternatieve naam via de rekening " + IBAN,
                assertThrows(BusinessRuleException.class, () -> accounts.setCountsInCash("KBC ZICHTREKENING", false)).getMessage());
        assertThrows(NotFoundException.class, () -> accounts.setCountsInCash("NOWHERE", false));
        assertTrue(account(IBAN).countsInCash());
    }

    @Test
    void theEndpointIsAdminOnly() {
        given().contentType("application/json").body("{\"counts\":false}").put("/api/bank-accounts/X/cash").then().statusCode(401);
    }

    @Test @TestSecurity(user = "staff-fixture", roles = "staff")
    void staffCannotSwitchAnAccount() {
        given().contentType("application/json").body("{\"counts\":false}").put("/api/bank-accounts/X/cash").then().statusCode(403);
    }

    private String latestAudit() {
        return em.createQuery("select a.summary from ActivityLogEntity a where a.entityType='BANK_ACCOUNT' order by a.id desc", String.class)
                .setMaxResults(1).getSingleResult();
    }

    private BankAccountService.Account account(String key) {
        return accounts.list().stream().filter(value -> value.key().equals(key)).findFirst().orElseThrow();
    }

    private void imported(String account, String id) {
        bank.importExternal(new BankStatementService.ExternalRequest(id, account, BigDecimal.TEN, AT, "Europe/Brussels",
                "Transfer", "Party", AT, false));
    }

    private void balance(String account) {
        var row = new BankBalanceEntity();
        row.account = account;
        row.date = LocalDate.of(2026, 10, 1);
        row.balanceEur = BigDecimal.TEN;
        row.createdAt = AT;
        em.persist(row);
        em.flush();
    }
}
