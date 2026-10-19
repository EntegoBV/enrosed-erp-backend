package be.enrosed.tica;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TicaPortalClientTest {
    @TempDir Path temporary;

    @Test void refusesMissingCredentialsAndOversizedOrReversedWindowsBeforeStartingBrowser() {
        var client = new TicaPortalClient("TT_2132", Optional.empty(), 120);
        assertFalse(client.configured());
        assertCode("NOT_CONFIGURED", () -> client.exportTransactions(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 19)));
        assertCode("INVALID_DATE_RANGE", () -> client.exportTransactions(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 19)));
        assertCode("INVALID_DATE_RANGE", () -> client.exportTransactions(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 19)));
        assertDoesNotThrow(() -> TicaPortalClient.validateDates(LocalDate.of(2026, 8, 19), LocalDate.of(2026, 10, 19)));
    }

    @Test void permitsOnlyFixedHttpsHostsAndSameOriginDownloads() {
        for (String url : new String[]{"https://exposant.tica.nl/dates", "https://auth.pwmarkets.beeyond.nl/if/flow/login/", "https://pwmarkets.beeyond.nl/"})
            assertTrue(TicaPortalWorker.allowedUrl(url));
        for (String url : new String[]{"http://exposant.tica.nl/dates", "https://exposant.tica.nl.attacker.test/", "https://exposant.tica.nl:444/", "https://x@exposant.tica.nl/", "file:///etc/passwd", "javascript:alert(1)", "invalid url"})
            assertFalse(TicaPortalWorker.allowedUrl(url));
        assertTrue(TicaPortalWorker.allowedDownload("blob:https://exposant.tica.nl/uuid"));
        assertTrue(TicaPortalWorker.allowedDownload("https://exposant.tica.nl/export/1.csv"));
        assertFalse(TicaPortalWorker.allowedDownload("blob:https://evil.test/uuid"));
        assertFalse(TicaPortalWorker.allowedDownload("https://auth.pwmarkets.beeyond.nl/export.csv"));
    }

    @Test void preservesInclusiveDatesAndRejectsAmbiguousFilterQueries() {
        String url = TicaPortalWorker.dateUrl(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 19));
        assertEquals("2026-10-01 00:00:00", TicaPortalWorker.queryValue(url, "tableFilters[date][date_from]"));
        assertEquals("2026-10-19 23:59:59", TicaPortalWorker.queryValue(url, "tableFilters[date][date_until]"));
        assertNull(TicaPortalWorker.queryValue(url + "&tableFilters[date][date_from]=2026-01-01", "tableFilters[date][date_from]"));
    }

    @Test void framedResultRejectsOversizeTruncationAndUnexpectedExtraOutput() throws Exception {
        byte[] csv = "test-csv".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(csv, TicaPortalClient.readResult(frame(true, csv.length, csv)));
        assertCode("DOWNLOAD_TOO_LARGE", () -> TicaPortalClient.readResult(frame(true, TicaPortalClient.MAX_CSV_BYTES + 1, new byte[0])));
        assertCode("WORKER_OUTPUT_INVALID", () -> TicaPortalClient.readResult(frame(true, csv.length + 1, csv)));
        assertCode("WORKER_OUTPUT_INVALID", () -> TicaPortalClient.readResult(frame(true, csv.length - 1, csv)));
        var buffer = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buffer)) {
            out.writeInt(TicaPortalClient.PROTOCOL); out.writeBoolean(false); out.writeUTF("secret provider detail");
        }
        assertCode("UNAVAILABLE", () -> TicaPortalClient.readResult(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray()))));
    }

    @Test void oversizeDownloadIsStoppedWhileItIsStillBeingWritten() throws Exception {
        Path downloads = Files.createDirectories(temporary.resolve("downloads"));
        Path partial = downloads.resolve("download.crdownload");
        try (var file = new java.io.RandomAccessFile(partial.toFile(), "rw")) {
            file.setLength(TicaPortalClient.MAX_CSV_BYTES + 1L);
        }
        assertCode("DOWNLOAD_TOO_LARGE", () -> TicaPortalClient.validateDownloadStorage(downloads));
    }

    @Test void rejectsHtmlAndUnexpectedExportHeadersBeforeImport() {
        assertCode("DOWNLOAD_HEADER_INVALID", () -> TicaPortalWorker.validateCsv("<html>Login expired</html>".getBytes(StandardCharsets.UTF_8)));
        assertDoesNotThrow(() -> TicaPortalWorker.validateCsv(("\uFEFF\"Locatie\";\"Exposantennummer\";\"Datum\";\"Tijd\";\"Transactienummer\";\"Kassabonregel\";\"Artikelomschrijving\"\n").getBytes(StandardCharsets.UTF_8)));
        var safe = new TicaPortalException("password = private-secret");
        assertEquals("UNAVAILABLE", safe.getMessage());
        assertNull(safe.getCause());
        assertFalse(safe.toString().contains("private-secret"));
    }

    private DataInputStream frame(boolean success, int length, byte[] bytes) throws Exception {
        var buffer = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buffer)) {
            out.writeInt(TicaPortalClient.PROTOCOL); out.writeBoolean(success); out.writeInt(length); out.write(bytes);
        }
        return new DataInputStream(new ByteArrayInputStream(buffer.toByteArray()));
    }

    private void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(TicaPortalException.class, action).code);
    }
}
