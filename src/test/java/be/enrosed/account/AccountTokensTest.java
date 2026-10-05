package be.enrosed.account;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountTokensTest {
    @Test
    void invitationAndSessionTokensHaveTheirOwnPrefixAnd256RandomBits() {
        Set<String> seen = new HashSet<>();
        for (int round = 0; round < 200; round++) {
            String invitation = AccountTokens.newInvitationToken();
            String session = AccountTokens.newSessionToken();

            assertTrue(invitation.matches("^eci1_[A-Za-z0-9_-]{43}$"), invitation);
            assertTrue(session.matches("^ecs1_[A-Za-z0-9_-]{43}$"), session);
            assertEquals(48, invitation.length());
            assertEquals(48, session.length());
            assertTrue(seen.add(invitation));
            assertTrue(seen.add(session));
        }
    }

    @Test
    void eachRegexAcceptsOnlyItsOwnKindOfToken() {
        String invitation = AccountTokens.newInvitationToken();
        String session = AccountTokens.newSessionToken();

        assertTrue(AccountTokens.isInvitationToken(invitation));
        assertTrue(AccountTokens.isSessionToken(session));
        assertFalse(AccountTokens.isInvitationToken(session));
        assertFalse(AccountTokens.isSessionToken(invitation));
    }

    @Test
    void malformedTokensAreRefusedBeforeAnyLookup() {
        String session = "ecs1_" + "A".repeat(43);
        assertTrue(AccountTokens.isSessionToken(session));
        for (String malformed : new String[]{null, "", " ", "ecs1_", "ecs1_" + "A".repeat(42),
                "ecs1_" + "A".repeat(44), "ECS1_" + "A".repeat(43), "ecs2_" + "A".repeat(43),
                "ecs1_" + "A".repeat(42) + "=", "ecs1_" + "A".repeat(42) + "+",
                "ecs1_" + "A".repeat(42) + "/", " " + session, session + " ", session + "\n",
                "Bearer " + session}) {
            assertFalse(AccountTokens.isSessionToken(malformed), String.valueOf(malformed));
        }
        String invitation = "eci1_" + "b".repeat(43);
        assertTrue(AccountTokens.isInvitationToken(invitation));
        for (String malformed : new String[]{null, "", "eci1_" + "b".repeat(42),
                "eci1_" + "b".repeat(44), invitation + "\n", invitation + "#", "#activate=" + invitation}) {
            assertFalse(AccountTokens.isInvitationToken(malformed), String.valueOf(malformed));
        }
    }

    @Test
    void hashIsSixtyFourLowerCaseHexAndNeverTheToken() {
        String invitation = AccountTokens.newInvitationToken();
        String session = AccountTokens.newSessionToken();

        for (String token : new String[]{invitation, session}) {
            String hash = AccountTokens.hash(token);
            assertTrue(hash.matches("^[0-9a-f]{64}$"), hash);
            assertNotEquals(token, hash);
            assertFalse(hash.contains(token.substring(5)));
            assertEquals(hash, AccountTokens.hash(token), "the same token always finds the same row");
        }
        assertNotEquals(AccountTokens.hash(invitation), AccountTokens.hash(session));
        /* Plain SHA-256 on purpose: no secret that could rotate and log everyone out. */
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                AccountTokens.hash("abc"));
    }
}
