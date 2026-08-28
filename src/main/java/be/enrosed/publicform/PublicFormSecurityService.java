package be.enrosed.publicform;

import jakarta.enterprise.context.ApplicationScoped;

/** Orders cheap local checks before the external human-verification request. */
@ApplicationScoped
public class PublicFormSecurityService {
    private final PublicFormTokenService tokens;
    private final TurnstileVerificationService turnstile;

    public PublicFormSecurityService(PublicFormTokenService tokens,
                                     TurnstileVerificationService turnstile) {
        this.tokens = tokens;
        this.turnstile = turnstile;
    }

    public void verifySubmission(PublicFormPurpose purpose, String formToken,
                                 String challengeToken) {
        tokens.verify(formToken, purpose);
        turnstile.verify(purpose, challengeToken);
    }
}
