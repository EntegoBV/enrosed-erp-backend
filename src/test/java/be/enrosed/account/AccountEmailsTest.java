package be.enrosed.account;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AccountEmailsTest {
    @Test
    void stripsAndLowerCasesAValidAddress() {
        assertEquals("a@x.be", AccountEmails.normalize(" A@X.be "));
        assertEquals("a@x.be", AccountEmails.normalize("a@x.be"));
        assertEquals("an.peeters+bloemen@bloemen-peeters.example.com",
                AccountEmails.normalize("\tAn.Peeters+Bloemen@Bloemen-Peeters.Example.COM\r\n"));
    }

    @Test
    void controlCharactersAreNeverRemovedSoTheAddressIsRefused() {
        assertNull(AccountEmails.normalize("a@x.be\u0001"));
        assertNull(AccountEmails.normalize("\u0001a@x.be"));
        assertNull(AccountEmails.normalize("a@x.be\u0000"));
        assertNull(AccountEmails.normalize("a@x\u007f.be"));
        assertNull(AccountEmails.normalize("a\u001f@x.be"));
    }

    @Test
    void overlongMalformedOrMissingAddressesAreRefused() {
        String tooLong = "a".repeat(255 - "@x.be".length()) + "@x.be";
        assertEquals(255, tooLong.length());
        assertNull(AccountEmails.normalize(tooLong));
        assertNull(AccountEmails.normalize("ax.be"));
        assertNull(AccountEmails.normalize("a@x"));
        assertNull(AccountEmails.normalize("a@@x.be"));
        assertNull(AccountEmails.normalize("a b@x.be"));
        assertNull(AccountEmails.normalize("a@x.be, b@x.be"));
        assertNull(AccountEmails.normalize(""));
        assertNull(AccountEmails.normalize("   "));
        assertNull(AccountEmails.normalize(null));
    }

    @Test
    void theLongestAllowedAddressPasses() {
        String longest = "a".repeat(254 - "@x.be".length()) + "@x.be";
        assertEquals(254, longest.length());
        assertEquals(longest, AccountEmails.normalize("  " + longest.toUpperCase() + "  "));
    }

    @Test
    void resultNeverHoldsACharacterThatTrimWouldRemove() {
        for (String raw : new String[]{" A@X.be ", "a@x.be\u0001", "\u0001a@x.be", "\u0001 a@x.be \u0001",
                "\u001fa@x.be", "a@x.be\u0000", " a@x.be", " a@x.be ", "a@x.be"}) {
            String normalized = AccountEmails.normalize(raw);
            if (normalized == null) continue;
            assertEquals(normalized.trim(), normalized, "trim() and strip() agree on " + normalized);
            assertEquals(normalized.strip(), normalized);
            assertEquals(0, normalized.chars().filter(character -> character <= ' ').count());
        }
        /* strip() removes Unicode white space that trim() keeps; the address behind it is the same one. */
        assertEquals("a@x.be", AccountEmails.normalize(" a@x.be "));
    }
}
