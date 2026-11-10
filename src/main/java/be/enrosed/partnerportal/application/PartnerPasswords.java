package be.enrosed.partnerportal.application;

import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ServiceUnavailableException;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * bcrypt (cost 12) for partner passwords, at most two hashes at a time on the worker pool the
 * staff ERP shares, like the website logins. A password is never returned, logged or stored
 * as typed.
 */
@ApplicationScoped
public class PartnerPasswords {

    private static final int COST = 12;
    private static final Semaphore BCRYPT = new Semaphore(2);

    public String hash(String password) {
        return hashing(() -> BcryptUtil.bcryptHash(password, COST));
    }

    public boolean matches(String password, String hash) {
        if (password == null || hash == null || hash.isBlank()) return false;
        return hashing(() -> BcryptUtil.matches(password, hash));
    }

    /** No permit within two seconds answers 503: the client tries again a moment later. */
    private static <T> T hashing(Supplier<T> work) {
        boolean permitted;
        try {
            permitted = BCRYPT.tryAcquire(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            permitted = false;
        }
        if (!permitted) throw new ServiceUnavailableException("Even geduld: probeer het zo opnieuw");
        try {
            return work.get();
        } finally {
            BCRYPT.release();
        }
    }
}
