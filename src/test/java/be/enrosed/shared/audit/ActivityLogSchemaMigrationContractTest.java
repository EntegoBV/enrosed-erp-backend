package be.enrosed.shared.audit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityLogSchemaMigrationContractTest {

    @Test
    void baseAndUpgradeMigrationsKeepStructuredChangesDeployableWithValidate() throws IOException {
        String baseMigration = normalizedSql(
                "docs/migrations/2026-08-27/activity-log-postgresql.sql");
        String upgradeMigration = normalizedSql(
                "docs/migrations/2026-08-29/activity-log-details-postgresql.sql");

        assertTrue(baseMigration.contains("changes_json varchar(16000)"));
        assertTrue(baseMigration.contains(
                "alter table activity_log add column if not exists changes_json varchar(16000)"));
        assertTrue(upgradeMigration.contains(
                "alter table activity_log add column if not exists changes_json varchar(16000)"));
    }

    private static String normalizedSql(String path) throws IOException {
        return Files.readString(Path.of(path))
                .replaceAll("--[^\\r\\n]*", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
    }
}
