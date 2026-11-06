package be.enrosed.catalog.application.port.out;

import be.enrosed.catalog.domain.SalesUnit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * The customers a catalogue can be made for, and the prices agreed with them (klantprijzen).
 * Both live on the sales side; the catalogue only reads them through this port.
 */
public interface CatalogRecipients {

    /** A stored customer as a catalogue names it: the company name. */
    record Customer(long id, String name) {}

    /** A customer's agreed price for one product, EUR excl. VAT, on the basis it was set on. */
    record StoredPrice(long productId, BigDecimal priceExclVat, SalesUnit salesUnit, Integer piecesPerUnit,
                       LocalDate validUntil) {}

    Optional<Customer> customer(long customerId);

    /** The customer's stored prices for these products, whatever their validity or basis. */
    Map<Long, StoredPrice> prices(long customerId, Collection<Long> productIds);
}
