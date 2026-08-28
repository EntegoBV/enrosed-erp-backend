package be.enrosed.shared.mail;

/** Outbound internal plain-text message transport shared by business modules. */
public interface InternalMessageSender {
    void sendInternal(String subject, String body);
}
