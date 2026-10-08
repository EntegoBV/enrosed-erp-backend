package be.enrosed.sales.application;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * When the customer mail of a website order leaves in tests. In production
 * a background pool sends it after the commit. Under test it leaves on the
 * committing thread from the start of the application, so no test class has
 * a late mail of an earlier one land in its mailbox; a test that looks at
 * the moment between the commit and the mail captures the deliveries and
 * runs them itself, and puts the direct executor back afterwards.
 */
@ApplicationScoped
public class WebOrderMailsExecutor {

    void onStart(@Observes StartupEvent started, WebOrderMails mails) {
        direct(mails);
    }

    /** The mail leaves on the committing thread, before the call returns. */
    public static void direct(WebOrderMails mails) {
        mails.useExecutor(Runnable::run);
    }

    /** Nothing leaves until the test runs the deliveries it finds in the list. */
    public static List<Runnable> capture(WebOrderMails mails) {
        List<Runnable> captured = new CopyOnWriteArrayList<>();
        mails.useExecutor(captured::add);
        return captured;
    }
}
