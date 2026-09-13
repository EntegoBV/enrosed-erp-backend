package be.enrosed.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real entry script against a fake psql; never connects to any database. */
class RailwayDatabaseReadinessTest {
    @TempDir Path temporary;
    private static final String SECRET = "test-password-must-not-appear";

    @Test
    void transientDnsRecoveryRunsReadOnlyProbesThenTheMigrationExactlyOnce() throws Exception {
        var result = run("RECOVER", "could not translate host name postgres.railway.internal: Name or service not known");
        assertEquals(0, result.exit());
        assertEquals(3, result.probes());
        assertEquals(1, result.migrations());
        assertTrue(result.output().contains("starting schema migrations once"));
        assertTrue(result.arguments().getLast().contains("pg_advisory_lock"));
        assertTrue(result.arguments().getLast().contains("--set=ON_ERROR_STOP=1"));
        assertFalse(result.arguments().toString().contains(SECRET));
        assertNoProbeFile();
    }

    @Test
    void unrecoveredDatabaseStopsAfterBoundedProbesWithoutAnyMigration() throws Exception {
        var result = run("DOWN", "connection refused");
        assertEquals(2, result.exit());
        assertEquals(3, result.probes());
        assertEquals(0, result.migrations());
        assertTrue(result.output().contains("no migrations started"));
        assertNoProbeFile();
    }

    @Test
    void authenticationFailureStopsImmediatelyWithoutLoggingConnectionSecrets() throws Exception {
        var result = run("AUTH", "password authentication failed for " + SECRET);
        assertEquals(2, result.exit());
        assertEquals(1, result.probes());
        assertEquals(0, result.migrations());
        assertTrue(result.output().contains("non-transient"));
        assertFalse(result.output().contains(SECRET));
    }

    @Test
    void aMissingDatabaseIsAConfigurationErrorAndIsNotRetried() throws Exception {
        var result = run("AUTH", "FATAL: database enrosed does not exist");
        assertEquals(2, result.exit());
        assertEquals(1, result.probes());
        assertEquals(0, result.migrations());
    }

    @Test
    void databaseRecoveryModeCanResolveBeforeMigrationStarts() throws Exception {
        var result = run("RECOVER", "FATAL: the database system is not yet accepting connections");
        assertEquals(0, result.exit());
        assertEquals(3, result.probes());
        assertEquals(1, result.migrations());
    }

    @Test
    void aPartiallyFailedMigrationIsNeverRetriedEvenIfItsErrorLooksTransient() throws Exception {
        var result = run("MIGRATION_FAIL", "connection refused after a migration committed");
        assertEquals(3, result.exit());
        assertEquals(1, result.probes());
        assertEquals(1, result.migrations());
        assertEquals(2, result.arguments().size());
        assertNoProbeFile();
    }

    @Test
    void railwayKeepsDatabaseReadinessAsTheReleaseGateAndRestartsStoppedProcesses() throws Exception {
        var deploy = new ObjectMapper().readTree(Path.of("railway.json").toFile()).path("deploy");
        assertEquals("/api/public/terms", deploy.path("healthcheckPath").asText());
        assertEquals(300, deploy.path("healthcheckTimeout").asInt());
        assertEquals("ALWAYS", deploy.path("restartPolicyType").asText());
        assertFalse(deploy.has("restartPolicyMaxRetries"), "do not retain the old three-restart ceiling");
    }

    private Result run(String mode, String error) throws Exception {
        Path bin = Files.createDirectory(temporary.resolve("bin"));
        Path psql = bin.resolve("psql");
        Files.writeString(psql, """
                #!/bin/sh
                set -eu
                printf '%s\\n' "$*" >> "$FAKE_PSQL_ARGUMENTS"
                case "$*" in
                  *'--command=SELECT 1;'*)
                    count=0
                    if [ -f "$FAKE_PSQL_PROBES" ]; then count=$(cat "$FAKE_PSQL_PROBES"); fi
                    count=$((count + 1))
                    printf '%s' "$count" > "$FAKE_PSQL_PROBES"
                    case "$FAKE_PSQL_MODE" in
                      AUTH|DOWN) printf '%s\\n' "$FAKE_PSQL_ERROR" >&2; exit 2 ;;
                      RECOVER)
                        if [ "$count" -lt 3 ]; then printf '%s\\n' "$FAKE_PSQL_ERROR" >&2; exit 2; fi ;;
                    esac
                    printf '1\\n'
                    exit 0 ;;
                  *)
                    printf 'migration\\n' >> "$FAKE_PSQL_MIGRATIONS"
                    if [ "$FAKE_PSQL_MODE" = MIGRATION_FAIL ]; then
                      printf '%s\\n' "$FAKE_PSQL_ERROR" >&2
                      exit 3
                    fi
                    exit 0 ;;
                esac
                """);
        assertTrue(psql.toFile().setExecutable(true));
        var process = new ProcessBuilder("/bin/sh", Path.of("scripts/run-postgresql-schema-migrations.sh").toAbsolutePath().toString());
        var env = process.environment();
        env.put("PATH", bin + ":" + env.getOrDefault("PATH", "/usr/bin:/bin"));
        env.put("TMPDIR", temporary.toString());
        env.put("PGHOST", "never-connect.invalid");
        env.put("PGPORT", "5432");
        env.put("PGDATABASE", "fixture");
        env.put("PGUSER", "fixture");
        env.put("PGPASSWORD", SECRET);
        env.put("PGCONNECT_TIMEOUT", "3");
        env.put("ENROSED_DB_WAIT_ATTEMPTS", "3");
        env.put("ENROSED_DB_WAIT_DELAY_SECONDS", "0");
        env.put("FAKE_PSQL_MODE", mode);
        env.put("FAKE_PSQL_ERROR", error);
        env.put("FAKE_PSQL_PROBES", temporary.resolve("probes").toString());
        env.put("FAKE_PSQL_MIGRATIONS", temporary.resolve("migrations").toString());
        env.put("FAKE_PSQL_ARGUMENTS", temporary.resolve("arguments").toString());
        Path log = temporary.resolve("output.log");
        var child = process.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!child.waitFor(10, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            fail("database readiness script did not finish within its fixture deadline");
        }
        return new Result(child.exitValue(), Integer.parseInt(Files.readString(temporary.resolve("probes"))),
                Files.exists(temporary.resolve("migrations")) ? Files.readAllLines(temporary.resolve("migrations")).size() : 0,
                Files.readString(log), Files.readAllLines(temporary.resolve("arguments")));
    }

    private void assertNoProbeFile() throws Exception {
        try (var paths = Files.list(temporary)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith("enrosed-db-readiness.")));
        }
    }

    private record Result(int exit, int probes, int migrations, String output, List<String> arguments) {}
}
