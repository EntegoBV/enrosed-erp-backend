package be.enrosed.sales.application;

import java.time.Instant;
import java.util.List;

/**
 * The partner portal's bell items, implemented in be.enrosed.partnerportal so sales does not depend on the
 * portal: its meldingen (kind PARTNER_VERKOOP) and the customers a partner added that staff have not reviewed
 * yet (kind PARTNER_KLANT).
 */
public interface PartnerSaleNotificationFeed {
    List<NotificationService.Notification> feed(Instant now);
}
