package be.enrosed.tica;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs each read-only portal export in a disposable browser process. The process boundary
 * bounds even a stalled Chromium download; credentials travel only over stdin, never arguments.
 */
@ApplicationScoped
public class TicaPortalClient {
    static final int PROTOCOL = 0x54494341;
    static final int MAX_CSV_BYTES = 10 * 1024 * 1024;
    private final String username;
    private final String password;
    private final int timeoutSeconds;
    private final AtomicBoolean running = new AtomicBoolean();

    @Inject
    public TicaPortalClient(
            @ConfigProperty(name = "enrosed.tica.username", defaultValue = "TT_2132") String username,
            @ConfigProperty(name = "enrosed.tica.password") Optional<String> password,
            @ConfigProperty(name = "enrosed.tica.timeout-seconds", defaultValue = "120") int timeoutSeconds) {
        this.username = username == null ? "" : username.trim();
        this.password = password.orElse("");
        this.timeoutSeconds = Math.max(30, Math.min(180, timeoutSeconds));
    }

    public boolean configured() { return !username.isEmpty() && !password.isBlank(); }

    public String exportTransactions(LocalDate from, LocalDate to) {
        validateDates(from, to);
        if (!configured()) throw new TicaPortalException("NOT_CONFIGURED");
        if (!username.matches("TT_[0-9]{1,8}") || password.length() > 512)
            throw new TicaPortalException("INVALID_CONFIGURATION");
        if (!running.compareAndSet(false, true)) throw new TicaPortalException("BUSY");
        Process process = null;
        Path temporary = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try {
            temporary = Files.createTempDirectory("enrosed-tica-");
            ProcessBuilder builder = new ProcessBuilder(javaExecutable(), "-Xmx192m", "-Djava.io.tmpdir=" + temporary, "-cp", workerClasspath(),
                    TicaPortalWorker.class.getName());
            // Playwright can print launch/selector diagnostics on stderr; they must not enter ERP logs.
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            var inherited = new java.util.HashMap<>(builder.environment());
            builder.environment().clear();
            for (String key : java.util.List.of("PATH", "HOME", "PLAYWRIGHT_BROWSERS_PATH", "LD_LIBRARY_PATH",
                    "FONTCONFIG_PATH", "SSL_CERT_FILE", "SSL_CERT_DIR")) {
                if (inherited.containsKey(key)) builder.environment().put(key, inherited.get(key));
            }
            // Browser binaries are installed by the deployment build, never downloaded during a sync.
            builder.environment().put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
            builder.environment().put("TMPDIR", temporary.toString());
            builder.environment().put("TMP", temporary.toString());
            builder.environment().put("TEMP", temporary.toString());
            builder.environment().remove("TICA_PASSWORD");
            process = builder.start();
            Process child = process;
            CompletableFuture<byte[]> output = new CompletableFuture<>();
            Thread.ofVirtual().name("tica-portal-output").start(() -> {
                try (DataInputStream input = new DataInputStream(child.getInputStream())) {
                    output.complete(readResult(input));
                } catch (Exception failure) {
                    output.completeExceptionally(failure instanceof TicaPortalException
                            ? failure : new TicaPortalException("UNAVAILABLE"));
                }
            });
            try (DataOutputStream input = new DataOutputStream(process.getOutputStream())) {
                input.writeInt(PROTOCOL);
                input.writeUTF(username);
                input.writeUTF(password);
                input.writeUTF(from.toString());
                input.writeUTF(to.toString());
                input.writeUTF(temporary.toString());
                input.writeInt(timeoutSeconds);
            }
            byte[] bytes = awaitOutput(output, temporary, deadline);
            if (!process.waitFor(remainingMillis(deadline), TimeUnit.MILLISECONDS))
                throw new TicaPortalException("TIMEOUT");
            if (process.exitValue() != 0) throw new TicaPortalException("UNAVAILABLE");
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (TicaPortalException failure) {
            throw failure;
        } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof TicaPortalException safe) throw safe;
            throw new TicaPortalException("UNAVAILABLE");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TicaPortalException("UNAVAILABLE");
        } catch (Exception unavailable) {
            throw new TicaPortalException("UNAVAILABLE");
        } finally {
            terminate(process);
            deleteTemporaryDirectory(temporary);
            running.set(false);
        }
    }

    /** Explicit read-only diagnostic. Credentials are environment-only; output contains no sales rows. */
    public static void main(String[] args) {
        if (args.length != 3 || !"--check".equals(args[0])) {
            System.err.println("Usage: TicaPortalClient --check YYYY-MM-DD YYYY-MM-DD");
            System.exit(2);
        }
        try {
            var client = new TicaPortalClient(System.getenv().getOrDefault("TICA_USERNAME", "TT_2132"),
                    Optional.ofNullable(System.getenv("TICA_PASSWORD")), 120);
            String csv = client.exportTransactions(LocalDate.parse(args[1]), LocalDate.parse(args[2]));
            System.out.println("TICA_EXPORT_OK bytes=" + csv.getBytes(StandardCharsets.UTF_8).length
                    + " dataLines=" + Math.max(0, csv.lines().count() - 1));
        } catch (TicaPortalException safe) {
            System.err.println("TICA_EXPORT_FAILED " + safe.code);
            System.exit(1);
        }
    }

    static void validateDates(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || to.isAfter(from.plusMonths(2)))
            throw new TicaPortalException("INVALID_DATE_RANGE");
    }

    static byte[] readResult(DataInputStream input) throws IOException {
        if (input.readInt() != PROTOCOL) throw new TicaPortalException("WORKER_PROTOCOL_INVALID");
        if (!input.readBoolean()) throw new TicaPortalException(input.readUTF());
        int size = input.readInt();
        if (size <= 0 || size > MAX_CSV_BYTES) throw new TicaPortalException("DOWNLOAD_TOO_LARGE");
        byte[] result = input.readNBytes(size);
        if (result.length != size || input.read() != -1) throw new TicaPortalException("WORKER_OUTPUT_INVALID");
        return result;
    }

    private static byte[] awaitOutput(CompletableFuture<byte[]> output, Path temporary, long deadline)
            throws java.util.concurrent.ExecutionException, InterruptedException {
        while (true) {
            validateDownloadStorage(temporary.resolve("downloads"));
            try {
                return output.get(Math.min(250, remainingMillis(deadline)), TimeUnit.MILLISECONDS);
            } catch (TimeoutException pending) {
                remainingMillis(deadline);
            }
        }
    }

    static void validateDownloadStorage(Path downloads) {
        if (!Files.isDirectory(downloads)) return;
        try (var files = Files.walk(downloads, 2)) {
            int count = 0;
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (++count > 4) throw new TicaPortalException("DOWNLOAD_INVALID");
                if (Files.size(file) > MAX_CSV_BYTES) throw new TicaPortalException("DOWNLOAD_TOO_LARGE");
            }
        } catch (IOException changingDownload) {
            // Chromium renames completed downloads. The worker validates the final file independently.
        }
    }

    private static long remainingMillis(long deadline) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) throw new TicaPortalException("TIMEOUT");
        return remaining;
    }

    static String workerClasspath() throws Exception {
        Path location = Path.of(TicaPortalWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String classpath = System.getProperty("java.class.path") + File.pathSeparator + location;
        // Quarkus fast-jar puts application classes in app/*.jar and dependencies in lib/main/*.jar.
        Path app = Files.isDirectory(location) ? location : location.getParent();
        if (app != null && app.getFileName().toString().equals("app")) {
            Path root = app.getParent();
            if (root != null && Files.isDirectory(root.resolve("lib/main")))
                classpath += File.pathSeparator + app.resolve("*") + File.pathSeparator + root.resolve("lib/main/*");
        }
        return classpath;
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    static void terminate(Process process) {
        if (process == null) return;
        // Capture descendants before killing their parent, otherwise Chromium can become orphaned.
        var descendants = process.toHandle().descendants().toList();
        descendants.reversed().forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
        if (process.isAlive()) process.destroyForcibly();
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
        long cleanupDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        try {
            process.waitFor(2, TimeUnit.SECONDS);
            for(var child:descendants) {
                long remaining=TimeUnit.NANOSECONDS.toMillis(cleanupDeadline-System.nanoTime());
                if(child.isAlive()&&remaining>0)try { child.onExit().get(remaining,TimeUnit.MILLISECONDS); }
                catch(java.util.concurrent.ExecutionException|TimeoutException ignored) { }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    static void deleteTemporaryDirectory(Path root) {
        if (root == null) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
