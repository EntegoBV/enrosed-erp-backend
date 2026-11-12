package be.enrosed.sales.application;

import java.time.Instant;
import java.util.List;

/**
 * The partner portal's meldingen as bell items (kind PARTNER_VERKOOP), implemented in
 * be.enrosed.partnerportal so sales does not depend on the portal.
 */
public interface PartnerSaleNotificationFeed {
    List<NotificationService.Notification> feed(Instant now);
}
