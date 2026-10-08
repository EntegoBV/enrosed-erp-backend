package be.enrosed.shared.mail;

import be.enrosed.shared.Language;

import java.math.BigDecimal;
import java.util.List;

/** The two mails a customer gets about a website order: it was received, and Enrosed took it into processing. */
public interface CustomerOrderMailer {
    /** cartons and piecesPerCarton are null when the carton content is not known yet; net is null without a price. */
    record Line(String description, Integer cartons, Integer piecesPerCarton, int quantity, BigDecimal net) {}

    /**
     * What the customer ordered, as it stood when they ordered. The three totals are null unless
     * the page showed the customer a total; deliveryLine is "street, postal code city, country",
     * or the pickup label and address.
     */
    record OrderMail(String to, Language language, String contactName, String company, String number,
                     List<Line> lines, BigDecimal goods, BigDecimal shipping, boolean shippingToConfirm, boolean pickup,
                     BigDecimal totalExclVat, BigDecimal vatAmount, BigDecimal totalInclVat,
                     String deliveryLine) {}

    /** Throws BusinessRuleException when the mail could not leave. */
    void sendOrderReceived(OrderMail mail);

    /** Throws BusinessRuleException when the mail could not leave. */
    void sendOrderInProcessing(OrderMail mail);
}
