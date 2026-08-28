package be.enrosed.contact;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ContactInquiryPersistenceTest {
    @Inject ContactInquiryService service;
    @Inject ContactInquiryRetentionJob retention;
    @Inject EntityManager entityManager;

    @Test
    @TestTransaction
    void contactCreatesOnlyInquiryAndDurableOutboxWithServerPolicyVersion() {
        long customersBefore = count("customer");
        long ordersBefore = count("sales_order");

        ContactDtos.Response response = service.submit(ContactInquiryServiceTest.request(
                "Line one\r\nLine two", "Buyer BV"));

        ContactInquiryEntity inquiry = ContactInquiryEntity.find(
                "reference", response.reference()).firstResult();
        assertNotNull(inquiry);
        assertEquals(24, response.reference().length());
        assertEquals("2026-08-28", inquiry.privacyPolicyVersion);
        assertEquals("Line one\nLine two", inquiry.message);
        ContactNotificationOutboxEntity outbox = ContactNotificationOutboxEntity.find(
                "inquiryId", inquiry.id).firstResult();
        assertNotNull(outbox);
        assertEquals(ContactOutboxStatus.PENDING, outbox.status);
        assertEquals(customersBefore, count("customer"));
        assertEquals(ordersBefore, count("sales_order"));
    }

    @Test
    @TestTransaction
    void retentionDeletesChildOutboxBeforeExpiredInquiryAndKeepsRecentInquiry() {
        ContactDtos.Response oldResponse = service.submit(ContactInquiryServiceTest.request(
                "An old useful message", "Old Buyer"));
        ContactDtos.Response recentResponse = service.submit(ContactInquiryServiceTest.request(
                "A recent useful message", "Recent Buyer"));
        ContactInquiryEntity old = ContactInquiryEntity.find(
                "reference", oldResponse.reference()).firstResult();
        old.createdAt = Instant.now().minus(731, ChronoUnit.DAYS);
        entityManager.flush();

        retention.cleanup();

        assertNull(ContactInquiryEntity.find("reference", oldResponse.reference()).firstResult());
        assertNotNull(ContactInquiryEntity.find("reference", recentResponse.reference()).firstResult());
        assertEquals(0, ContactNotificationOutboxEntity.count("inquiryId", old.id));
        assertTrue(ContactNotificationOutboxEntity.count() >= 1);
    }

    private long count(String table) {
        return ((Number) entityManager.createNativeQuery(
                "select count(*) from " + table).getSingleResult()).longValue();
    }
}
