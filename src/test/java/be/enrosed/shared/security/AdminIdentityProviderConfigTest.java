package be.enrosed.shared.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminIdentityProviderConfigTest {

    @Test
    void accountConfigurationHasCanonicalUsernamesAndSeparateDisplayNames() {
        var accounts = AdminIdentityProvider.parseAccounts(" EmRe | Emre ,BERAT|Berat");

        assertEquals(2, accounts.size());
        assertEquals("emre", accounts.get(0).username());
        assertEquals("Emre", accounts.get(0).displayName());
        assertEquals("berat", accounts.get(1).username());
        assertEquals("Berat", accounts.get(1).displayName());
    }

    @Test
    void malformedOrDuplicateAccountsFailClosed() {
        assertThrows(IllegalStateException.class,
                () -> AdminIdentityProvider.parseAccounts(""));
        assertThrows(IllegalStateException.class,
                () -> AdminIdentityProvider.parseAccounts("emre|Emre,EMRE|Other"));
        assertThrows(IllegalStateException.class,
                () -> AdminIdentityProvider.parseAccounts("not valid|Name"));
        assertThrows(IllegalStateException.class,
                () -> AdminIdentityProvider.parseAccounts("emre|"));
    }
}
