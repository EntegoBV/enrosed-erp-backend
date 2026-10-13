package be.enrosed.finance.banking;

import be.enrosed.finance.application.CompanyCostService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class BankCostRegistrationResourceTest {
    @Inject BankStatementService bank;
    @Inject CompanyCostService costs;
    @Inject EntityManager em;
    @Test void registrationIsNotPublic(){given().contentType("application/json").body("{}").post("/api/bank-statements/1/cost-registration").then().statusCode(401);}
    @Test @TestSecurity(user="fixture-staff",roles="staff") void registrationRequiresAdmin(){given().contentType("application/json").body("{}").post("/api/bank-statements/1/cost-registration").then().statusCode(403);}
    @Test @TestSecurity(user="fixture-admin",roles="admin") void resourceReturnsFlatCostViewAndDistinguishesValidationFromRetryConflicts() {
        var row=bank.create(new BankStatementService.ManualRequest("RESOURCE-TEST",new BigDecimal("121"),BankStatementService.Direction.OUTGOING,Instant.parse("2026-09-08T10:00:00Z"),"Europe/Brussels","Invoice","Fixture Supplier",UUID.randomUUID().toString()));
        String path="/api/bank-statements/"+row.id+"/cost-registration",key=UUID.randomUUID().toString();
        var cost=new HashMap<String,Object>(Map.of("date","2026-09-01","category","SOFTWARE","description","Resource fixture","amountExclEur",100,"amountInclEur",121,"vatPct",21));
        try {
            given().contentType("application/json").body(Map.of("requestId",key,"cost",cost)).post(path).then().statusCode(422).body("message",equalTo("Kies de btw-regeling"));
            cost.put("vatRegime","BE");cost.put("vatRegimeConfirmed",true);
            long id=given().contentType("application/json").body(Map.of("requestId",key,"cost",cost)).post(path).then().statusCode(200)
                    .body("line.allocated",is(true)).body("line.amountEur",equalTo(-121.00f)).body("cost.amountExclEur",equalTo(100.00f))
                    .body("cost.cashTotalEur",equalTo(121.00f)).body("cost.openEur",equalTo(0.00f)).body("cost.payments",hasSize(1)).extract().jsonPath().getLong("cost.id");
            given().contentType("application/json").body(Map.of("requestId",key,"cost",cost)).post(path).then().statusCode(200).body("cost.id",equalTo((int)id)).body("cost.payments",hasSize(1));
            cost.put("description","Changed after success");
            given().contentType("application/json").body(Map.of("requestId",key,"cost",cost)).post(path).then().statusCode(409).body("code",equalTo("BANK_COST_REGISTRATION_CHANGED"));
            given().contentType("application/json").body(Map.of("requestId",UUID.randomUUID().toString(),"cost",cost)).post(path).then().statusCode(409).body("code",equalTo("BANK_COST_REGISTRATION_EXISTS"));
        } finally {
            QuarkusTransaction.requiringNew().run(()->{
                var receipt=em.find(BankCostRegistrationEntity.class,row.id);
                if(receipt!=null){long costId=receipt.companyCostId;em.remove(receipt);costs.delete(costId);}
                em.remove(em.find(BankStatementLineEntity.class,row.id));
            });
        }
    }
}
