package be.enrosed.partnerportal.adapter.out.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The few customer columns the partner may see (name, city, country), read straight from the customer table
 * so the partner side never loads a whole customer record. Partner customers are never returned.
 */
@ApplicationScoped
public class PartnerCustomerQueries {

    /** What the partner sees of a customer. */
    public record Row(long id, String company, String city, String countryCode) {}

    /**
     * LIKE pattern (escape \) of the marker a website request's internal note starts with until staff review
     * it (SalesOrderService.WEBSITE_REQUEST_MARKER, "[WEBSITE_AANVRAAG]").
     */
    static final String WEBSITE_REQUEST_PATTERN = "[WEBSITE\\_AANVRAAG]%";

    private final EntityManager entityManager;

    public PartnerCustomerQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Customers whose company or city contains {@code pattern} (already lower case, %, _ and \ escaped with \),
     * no partner and not {@code partnerId}, names starting with it first, then by name; at most {@code limit}.
     * Left out: a customer whose every sales document (trashed ones included) is a website request still waiting
     * for its first review, unless it is one of {@code mine} (the customers this partner added).
     */
    public List<Row> search(long partnerId, String pattern, String prefix, Collection<Long> mine, int limit) {
        boolean anyMine = mine != null && !mine.isEmpty();
        String sql = """
                select c.id, c.company, c.city, c.countryCode from customer c
                where coalesce(c.partner, false) = false and c.id <> :partner
                  and (lower(c.company) like :pattern escape '\\' or lower(coalesce(c.city, '')) like :pattern escape '\\')
                  and (""" + (anyMine ? "c.id in (:mine) or " : "") + """
                       not exists (select 1 from sales_order o where o.customerId = c.id)
                       or exists (select 1 from sales_order o where o.customerId = c.id
                                  and not (o.status = 'CONCEPT' and coalesce(o.docType, 'OFFERTE') = 'OFFERTE'
                                           and coalesce(o.internalNotes, '') like :marker escape '\\')))
                order by case when lower(c.company) like :prefix escape '\\' then 0 else 1 end, lower(c.company), c.id
                """;
        var query = entityManager.createNativeQuery(sql)
                .setParameter("partner", partnerId)
                .setParameter("pattern", pattern)
                .setParameter("prefix", prefix)
                .setParameter("marker", WEBSITE_REQUEST_PATTERN)
                .setMaxResults(limit);
        if (anyMine) query.setParameter("mine", mine);
        return rows(query.getResultList());
    }

    /**
     * Whether the partner may name this customer as a buyer: the same rule as {@link #search} (no partner, not
     * itself, not a customer known only from website requests still waiting for review unless it added it).
     */
    public boolean visible(long partnerId, long customerId, boolean addedByPartner) {
        List<?> found = entityManager.createNativeQuery("""
                        select c.id from customer c
                        where c.id = :id and coalesce(c.partner, false) = false and c.id <> :partner
                          and (:mine = true
                               or not exists (select 1 from sales_order o where o.customerId = c.id)
                               or exists (select 1 from sales_order o where o.customerId = c.id
                                          and not (o.status = 'CONCEPT' and coalesce(o.docType, 'OFFERTE') = 'OFFERTE'
                                                   and coalesce(o.internalNotes, '') like :marker escape '\\')))""")
                .setParameter("id", customerId)
                .setParameter("partner", partnerId)
                .setParameter("mine", addedByPartner)
                .setParameter("marker", WEBSITE_REQUEST_PATTERN)
                .getResultList();
        return !found.isEmpty();
    }

    /** Every partner customer's id, ascending (the order the partner locks are taken in). */
    public List<Long> partnerIds() {
        List<Long> ids = new ArrayList<>();
        for (Object id : entityManager.createNativeQuery(
                "select c.id from customer c where coalesce(c.partner, false) = true order by c.id").getResultList()) {
            ids.add(((Number) id).longValue());
        }
        return ids;
    }

    /** These customers (no partner, not partnerId), in no particular order; missing ids are left out. */
    public List<Row> byIds(long partnerId, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return rows(entityManager.createNativeQuery("""
                        select c.id, c.company, c.city, c.countryCode from customer c
                        where c.id in (:ids) and coalesce(c.partner, false) = false and c.id <> :partner""")
                .setParameter("ids", ids)
                .setParameter("partner", partnerId)
                .getResultList());
    }

    private static List<Row> rows(List<?> result) {
        List<Row> rows = new ArrayList<>();
        for (Object item : result) {
            Object[] row = (Object[]) item;
            rows.add(new Row(((Number) row[0]).longValue(), (String) row[1], (String) row[2], (String) row[3]));
        }
        return rows;
    }
}
