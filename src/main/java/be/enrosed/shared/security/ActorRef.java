package be.enrosed.shared.security;

import java.util.Locale;

/** Stable staff identity for persistence, with a separate human-facing name. */
public record ActorRef(String username, String displayName) {

    public static final ActorRef SYSTEM = new ActorRef("systeem", "Systeem");

    public ActorRef {
        String originalUsername = username;
        username = canonicalUsername(username);
        displayName = displayName == null || displayName.isBlank()
                ? originalUsername == null || originalUsername.isBlank() ? username : originalUsername.strip()
                : displayName.strip();
    }

    public static String canonicalUsername(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}
