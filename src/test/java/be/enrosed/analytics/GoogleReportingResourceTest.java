package be.enrosed.analytics;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
@QuarkusTest
class GoogleReportingResourceTest {
    @Test void googleReportsArePrivateAndMissingCredentialsAreHonestNoStoreResponses() {
        given().when().get("/api/analytics/website/google").then().statusCode(401);
        given().auth().preemptive().basic("emre","named-auth-test-password")
            .when().get("/api/analytics/website/google?days=7").then().statusCode(200)
            .header("Cache-Control","no-store").body("days",equalTo(7))
            .body("googleAnalytics.status",equalTo("NOT_CONFIGURED"))
            .body("googleAnalytics.data",nullValue()).body("searchConsole.status",equalTo("NOT_CONFIGURED"))
            .body("realtime.status",equalTo("NOT_CONFIGURED"));
    }
}
