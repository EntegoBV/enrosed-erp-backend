package be.enrosed.shared;

import be.enrosed.push.DailyAgendaPush;
import be.enrosed.sourcing.adapter.out.market.MarketSourceRefreshJob;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.common.runtime.util.SchedulerUtils;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerConfigurationContractTest {

    @Test
    void outwardFacingJobsUseConfigurableCronsAndCanBeDisabledPerEnvironment() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(
                Path.of("src/main/resources/application.properties"))) {
            properties.load(input);
        }

        assertEquals("${DAILY_AGENDA_PUSH_CRON:0 0 9 * * ?}",
                properties.getProperty("enrosed.push.daily-agenda.cron"));
        assertEquals("${MARKET_DATA_REFRESH_CRON:0 15 3 * * ?}",
                properties.getProperty("enrosed.market.refresh.cron"));
        assertTrue(SchedulerUtils.isOff("off"));

        Scheduled agenda = DailyAgendaPush.class.getDeclaredMethod("morningDigest")
                .getAnnotation(Scheduled.class);
        Scheduled market = MarketSourceRefreshJob.class.getDeclaredMethod("refreshDaily")
                .getAnnotation(Scheduled.class);
        assertEquals("{enrosed.push.daily-agenda.cron}", agenda.cron());
        assertEquals("{enrosed.market.refresh.cron}", market.cron());
    }
}
