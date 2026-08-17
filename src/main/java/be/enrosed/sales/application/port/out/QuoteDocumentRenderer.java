package be.enrosed.sales.application.port.out;

import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.SalesOrder;

/** Uitgaande poort die van een offerte een PDF maakt. */
public interface QuoteDocumentRenderer {

    record Document(String filename, byte[] content, String contentType) {}

    Document render(SalesOrder order, PricedOrder priced, Customer customer, String portalUrl);
}
