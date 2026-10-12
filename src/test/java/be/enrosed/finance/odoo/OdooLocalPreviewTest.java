package be.enrosed.finance.odoo;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.*;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise real persisted invoice pricing through HTTP; no spy may bypass the local preview guards. */
@QuarkusTest
@TestSecurity(user="test-admin",roles="admin")
class OdooLocalPreviewTest {
    @Inject EntityManager em;
    @Inject SalesOrderService sales;
    record Fixture(long invoiceId,long customerId,long productId) {}

    @Test void ordinaryDefaultAndExplicitFullPlansHaveLocalPreviewsButStructuredPlansAreRefused() {
        Fixture fixture=QuarkusTransaction.requiringNew().call(this::fixture);
        try {
            assertNull(QuarkusTransaction.requiringNew().call(()->sales.get(fixture.invoiceId()).paymentPlanOrNull()));
            assertEquals(SalesPaymentPlan.FULL,QuarkusTransaction.requiringNew().call(()->sales.get(fixture.invoiceId()).paymentPlan()));
            assertPreview(fixture);
            QuarkusTransaction.requiringNew().run(()->em.find(SalesOrderEntity.class,fixture.invoiceId()).paymentPlan=SalesPaymentPlan.FULL);
            assertPreview(fixture);
            QuarkusTransaction.requiringNew().run(()->em.find(SalesOrderEntity.class,fixture.invoiceId()).paymentPlan=SalesPaymentPlan.THIRD_TWO_THIRDS_PRODUCTION);
            given().get("/api/odoo/outbox/"+fixture.invoiceId()).then().statusCode(200)
                    .body("code",is("OUTBOX_UNSUPPORTED_DOCUMENT")).body("localPreview",nullValue());
        } finally {
            QuarkusTransaction.requiringNew().run(()->{
                em.remove(em.find(SalesOrderEntity.class,fixture.invoiceId()));em.flush();
                em.remove(em.find(CustomerEntity.class,fixture.customerId()));
                em.remove(em.find(ProductEntity.class,fixture.productId()));
            });
        }
    }

    private void assertPreview(Fixture fixture) {
        var priced=QuarkusTransaction.requiringNew().call(()->sales.price(sales.get(fixture.invoiceId())));
        var expected=priced.totals();
        var result=given().get("/api/odoo/outbox/"+fixture.invoiceId()).then().statusCode(200)
                .body("code",is("OUTBOUND_DISABLED")).body("entry",nullValue())
                .body("localPreview.moveType",is("out_invoice")).body("localPreview.lines.description",hasItems(priced.lines().getFirst().customerDescription(),"Extra service","Transport"))
                .body("localPreview.lines[0].quantity",equalTo(24))
                .extract().jsonPath();
        assertEquals(0,expected.total().compareTo(new BigDecimal(result.get("localPreview.net").toString())));
        assertEquals(0,expected.vatAmount().compareTo(new BigDecimal(result.get("localPreview.vat").toString())));
        assertEquals(0,expected.totalInclVat().compareTo(new BigDecimal(result.get("localPreview.total").toString())));
    }

    private Fixture fixture() {
        var customer=new CustomerEntity();customer.company="Odoo preview buyer";customer.vatNumber="BE0000000000";
        customer.countryCode="BE";customer.address="Main 1";customer.postalCode="2000";customer.city="Antwerpen";
        em.persist(customer);
        var product=new ProductEntity();product.sku="ODOO-PREVIEW-"+UUID.randomUUID();product.name="Preview roses";product.active=true;
        product.piecesPerCarton=12;product.productLengthCm=product.productWidthCm=product.productHeightCm=BigDecimal.ONE;
        product.cartonLengthCm=product.cartonWidthCm=product.cartonHeightCm=BigDecimal.TEN;product.cartonWeightKg=BigDecimal.ONE;
        em.persist(product);em.flush();
        var order=new SalesOrderEntity();order.number="ODOO-PREVIEW-"+UUID.randomUUID();order.customerId=customer.id;
        order.countryCode="BE";order.orderDate=LocalDate.now();order.invoiceDueDate=LocalDate.now().plusDays(30);
        order.docType=DocumentType.FACTUUR;order.status=QuoteStatus.UITGEREIKT;order.freightPricingStrategy=FreightPricingStrategy.FIXED;
        order.manualFreightEur=new BigDecimal("12.34");order.freight=FreightState.AANGEVULD;
        order.extraLinesJson="[{\"description\":\"Extra service\",\"quantity\":2,\"unitPriceEur\":75}]";
        var line=new SalesOrderLineEntity();line.order=order;line.productId=product.id;line.quantity=24;
        line.unitPriceEur=new BigDecimal("4.25");line.unitCostEur=BigDecimal.ONE;order.lines.add(line);
        em.persist(order);em.flush();return new Fixture(order.id,customer.id,product.id);
    }
}
