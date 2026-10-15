package be.enrosed.finance.forecast;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.company.CompanyProfileEntity;
import be.enrosed.shared.company.CompanyProfileService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The company's buffer for "krap" in the Kasprognose and the Kaslijn: one
 * amount for the company, on the profile row, which the profile's own PUT
 * never touches. Nothing stored reads as EUR 10.000.
 */
@ApplicationScoped
public class CashForecastSettingsService {
    public static final BigDecimal DEFAULT_BUFFER_EUR = new BigDecimal("10000.00");
    private static final BigDecimal MAX_BUFFER_EUR = new BigDecimal("10000000");

    @Inject EntityManager em;
    @Inject CompanyProfileService company;

    public record Settings(BigDecimal cashBufferEur, boolean cashBufferStored) {}
    public record Request(BigDecimal cashBufferEur) {}

    public Settings get() {
        CompanyProfileEntity profile = em.find(CompanyProfileEntity.class, 1L);
        return settings(profile == null ? null : profile.cashBufferEur);
    }

    /** A buffer from EUR 0 to 10.000.000 in cents; null clears it back to the default. */
    @Transactional
    public Settings save(Request request) {
        BigDecimal value = request == null ? null : request.cashBufferEur();
        if (value != null) {
            if (value.signum() < 0 || value.compareTo(MAX_BUFFER_EUR) > 0) {
                throw new BusinessRuleException("Geef een buffer tussen € 0 en € 10.000.000");
            }
            if (value.stripTrailingZeros().scale() > 2) {
                throw new BusinessRuleException("Een bedrag heeft maximaal twee decimalen");
            }
            value = value.setScale(2, RoundingMode.UNNECESSARY);
        }
        CompanyProfileEntity profile = em.find(CompanyProfileEntity.class, 1L, LockModeType.PESSIMISTIC_WRITE);
        if (profile == null) {
            /* The one profile row, created by the profile's own save with the seeded identity it shows while missing. */
            company.save(company.get());
            profile = em.find(CompanyProfileEntity.class, 1L, LockModeType.PESSIMISTIC_WRITE);
        }
        profile.cashBufferEur = value;
        em.flush();
        return settings(value);
    }

    private static Settings settings(BigDecimal stored) {
        return stored == null ? new Settings(DEFAULT_BUFFER_EUR, false) : new Settings(stored.setScale(2, RoundingMode.HALF_UP), true);
    }
}
