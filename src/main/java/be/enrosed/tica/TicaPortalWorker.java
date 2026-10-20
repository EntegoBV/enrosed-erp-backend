package be.enrosed.tica;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.WaitUntilState;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** Internal subprocess entry point; stdout is a framed result, stderr is never exposed. */
public final class TicaPortalWorker {
    private static final String PORTAL = "https://exposant.tica.nl";
    private static final Set<String> HOSTS = Set.of("exposant.tica.nl", "auth.pwmarkets.beeyond.nl", "pwmarkets.beeyond.nl");
    private static final DateTimeFormatter DUTCH_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.forLanguageTag("nl-NL"));
    private static final String DATE_FROM = "[id='tableFilters.date.date_from']";
    private static final String DATE_TO = "[id='tableFilters.date.date_until']";
    private static final String EXHIBITOR = "[id='tableFilters.exhibitor_id.value']";
    private static final int MAX_DOWNLOADS = 1;
    private static final int MAX_LINES = 100_001;

    private TicaPortalWorker() { }

    public static void main(String[] args) {
        try (DataInputStream input = new DataInputStream(System.in);
             DataOutputStream output = new DataOutputStream(System.out)) {
            // Keep third-party launcher diagnostics out of the binary result and ERP logs.
            System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
            byte[] csv = null;
            String failure = null;
            try {
                if (input.readInt() != TicaPortalClient.PROTOCOL) throw new TicaPortalException("INVALID_CONFIGURATION");
                String username = input.readUTF();
                String password = input.readUTF();
                LocalDate from = LocalDate.parse(input.readUTF());
                LocalDate to = LocalDate.parse(input.readUTF());
                Path temporary = Path.of(input.readUTF());
                int timeout = input.readInt();
                if (!username.matches("TT_[0-9]{1,8}") || password.isBlank() || password.length() > 512
                        || !Files.isDirectory(temporary) || timeout < 30 || timeout > 180)
                    throw new TicaPortalException("INVALID_CONFIGURATION");
                TicaPortalClient.validateDates(from, to);
                csv = export(username, password, from, to, temporary, timeout);
            } catch (TicaPortalException safe) {
                failure = safe.code;
            } catch (TimeoutError timeout) {
                failure = "TIMEOUT";
            } catch (Throwable unavailable) {
                // Browser exceptions contain URLs, form fields and provider messages. Do not serialize them.
                failure = "UNAVAILABLE";
            }
            output.writeInt(TicaPortalClient.PROTOCOL);
            output.writeBoolean(failure == null);
            if (failure == null) {
                output.writeInt(csv.length);
                output.write(csv);
            } else output.writeUTF(failure);
            output.flush();
        } catch (IOException ignored) {
            // A terminated parent has closed the pipe; there is no safe secondary output destination.
        }
    }

    static byte[] export(String username, String password, LocalDate from, LocalDate to,
                         Path temporary, int timeoutSeconds) throws IOException {
        // Reserve time to close Chromium and return a safe operation code before the parent's hard limit.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds - 10L);
        AtomicBoolean oversized = new AtomicBoolean();
        AtomicInteger downloads = new AtomicInteger();
        String stage="BROWSER_START_FAILED";
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(true).setTimeout(remaining(deadline)).setDownloadsPath(temporary.resolve("downloads")));
             BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                     .setAcceptDownloads(true).setIgnoreHTTPSErrors(false).setLocale("nl-NL")
                     .setTimezoneId("Europe/Brussels").setServiceWorkers(ServiceWorkerPolicy.BLOCK))) {
            context.setDefaultTimeout(Math.min(20_000, remaining(deadline)));
            context.setDefaultNavigationTimeout(Math.min(30_000, remaining(deadline)));
            context.route("**/*", route -> {
                if (allowedUrl(route.request().url())) route.resume();
                else route.abort();
            });
            Page page = context.newPage();
            page.onDownload(download -> {
                if (downloads.incrementAndGet() > MAX_DOWNLOADS || oversized.get()) download.cancel();
            });
            page.onResponse(response -> {
                String disposition = response.headers().getOrDefault("content-disposition", "");
                if (disposition.toLowerCase(Locale.ROOT).contains("attachment")) {
                    String size = response.headers().get("content-length");
                    if (size != null) {
                        try { if (Long.parseLong(size) > TicaPortalClient.MAX_CSV_BYTES) oversized.set(true); }
                        catch (NumberFormatException invalid) { oversized.set(true); }
                    }
                }
            });
            stage="LOGIN_UNAVAILABLE";
            authenticate(page, username, password, deadline);
            stage="DATE_FILTER_UNAVAILABLE";
            prepareDates(page, username, from, to, deadline);
            verifyAccount(page, username);
            checkChallenge(page);
            stage="EXPORT_OPEN_DIALOG";
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Export Transacties").setExact(true)).click();
            page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Export Transacties").setExact(true))
                    .waitFor(new Locator.WaitForOptions().setTimeout(Math.min(20_000,remaining(deadline))));
            stage="EXPORT_SELECT_TYPE";
            Locator type = page.locator("[id='mountedActionsData.0.file_type']:visible");
            type.selectOption(new SelectOption().setLabel("CSV"));
            if (!type.locator("option:checked").innerText().trim().equals("CSV"))
                throw new TicaPortalException("EXPORT_TYPE_INVALID");
            // Use the current date filters, already verified in the real portal fields and URL.
            verifyDateFilters(page, from, to);
            stage="EXPORT_WAIT_DOWNLOAD";
            Download download = page.waitForDownload(new Page.WaitForDownloadOptions().setTimeout(remaining(deadline)),
                    () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Export").setExact(true)).click());
            if (!allowedDownload(download.url())) {
                download.cancel();
                throw new TicaPortalException("DOWNLOAD_ORIGIN_INVALID");
            }
            if (!download.suggestedFilename().toLowerCase(Locale.ROOT).endsWith(".csv")) {
                download.cancel();
                throw new TicaPortalException("DOWNLOAD_FILENAME_INVALID");
            }
            if (oversized.get()) {
                download.cancel();
                throw new TicaPortalException("DOWNLOAD_TOO_LARGE");
            }
            // The parent kills this isolated process tree at the overall deadline, including while
            // Playwright's path() waits for a download (that API has no separate completion timeout).
            stage="DOWNLOAD_READ_FAILED";
            Path path = download.path();
            if (path == null || download.failure() != null || downloads.get() != MAX_DOWNLOADS)
                throw new TicaPortalException("DOWNLOAD_INCOMPLETE");
            remaining(deadline);
            long size = Files.size(path);
            if (size < 1 || size > TicaPortalClient.MAX_CSV_BYTES) throw new TicaPortalException("DOWNLOAD_TOO_LARGE");
            byte[] bytes;
            try (var stream = Files.newInputStream(path)) {
                bytes = stream.readNBytes(TicaPortalClient.MAX_CSV_BYTES + 1);
            }
            validateCsv(bytes);
            return bytes;
        } catch(TicaPortalException safe) {if("TIMEOUT".equals(safe.code))throw new TicaPortalException(stage);throw safe;}
        catch(TimeoutError timeout) {throw new TicaPortalException(stage);}
        catch(Exception unavailable) {throw new TicaPortalException(stage);}
    }

    private static void authenticate(Page page, String username, String password, long deadline) {
        String stage="AUTH_OPEN_PORTAL";
        try {
        page.navigate(PORTAL + "/dates", new Page.NavigateOptions()
                .setTimeout(remaining(deadline)).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        checkChallenge(page);
        if (!isDates(page.url())) {
            stage="AUTH_OPEN_LOGIN";
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("PW Markets").setExact(true)).click();
            stage="AUTH_USERNAME";
            // The real Authentik control is uidField; name=username belongs to its offscreen autofill helper.
            page.waitForCondition(()->formInput(page,"input[autocomplete='username']")!=null,
                    new Page.WaitForConditionOptions().setTimeout(Math.min(20_000,remaining(deadline))));
            Locator user = formInput(page,"input[autocomplete='username']");
            requireAuthenticationHost(page.url());
            checkChallenge(page);
            user.fill(username);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Log in").setExact(true)).click();
            stage="AUTH_PASSWORD";
            page.waitForCondition(() -> {
                checkChallenge(page);
                return formInput(page,"input[type='password']")!=null;
            }, new Page.WaitForConditionOptions().setTimeout(Math.min(20_000, remaining(deadline))));
            requireAuthenticationHost(page.url());
            Locator secret=formInput(page,"input[type='password']");
            secret.fill(password);
            // Exactly one password submission. Expiry, MFA or rejection requires user attention.
            stage="AUTH_SUBMIT";
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Continue").setExact(true)).click();
            try {
                page.waitForCondition(() -> {
                    checkChallenge(page);
                    return isDates(page.url());
                }, new Page.WaitForConditionOptions().setTimeout(Math.min(30_000, remaining(deadline))));
            } catch (TimeoutError rejected) {
                throw new TicaPortalException("AUTH_FAILED");
            }
        }
        stage="AUTH_VERIFY";
        if (!isDates(page.url())) throw new TicaPortalException("AUTH_FAILED");
        page.locator("img[alt^='Avatar van ']").waitFor(new Locator.WaitForOptions().setTimeout(Math.min(20_000,remaining(deadline))));
        verifyAccount(page, username);
        } catch(TicaPortalException safe) {if("TIMEOUT".equals(safe.code))throw new TicaPortalException(stage);throw safe;}
        catch(TimeoutError timeout) {throw new TicaPortalException(stage);}
        catch(RuntimeException failure) {
            String message=failure.getMessage();
            if(message!=null&&message.contains("net::ERR_CERT_"))throw new TicaPortalException("AUTH_TLS_ERROR");
            if(message!=null&&message.contains("net::ERR_BLOCKED_BY_CLIENT"))throw new TicaPortalException("AUTH_NETWORK_BLOCKED");
            if(message!=null&&message.contains("net::ERR_ABORTED"))throw new TicaPortalException("AUTH_NAVIGATION_ABORTED");
            throw new TicaPortalException(stage);
        }
    }

    private static void prepareDates(Page page, String username, LocalDate from, LocalDate to, long deadline) {
        String stage="FILTER_NAVIGATE";
        try {
        page.navigate(dateUrl(from, to), new Page.NavigateOptions().setTimeout(remaining(deadline))
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        if (!isDates(page.url())) throw new TicaPortalException("AUTH_FAILED");
        verifyAccount(page, username);
        verifyDateFilters(page, from, to);
        stage="FILTER_EXHIBITOR";
        Locator exhibitor = page.locator(EXHIBITOR);
        String number = username.substring(3);
        // The visible exhibitor number is not its option value (TT_2132 currently has value 843).
        exhibitor.selectOption(new SelectOption().setLabel(number));
        String selectedValue = exhibitor.inputValue();
        if (selectedValue.isBlank() || !exhibitor.locator("option:checked").innerText().trim().equals(number))
            throw new TicaPortalException("ACCOUNT_MISMATCH");
        page.waitForCondition(() -> selectedValue.equals(queryValue(page.url(), "tableFilters[exhibitor_id][value]")),
                new Page.WaitForConditionOptions().setTimeout(Math.min(20_000,remaining(deadline))));
        stage="FILTER_SETTLE";
        page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions()
                .setTimeout(Math.min(10_000, remaining(deadline))));
        verifyDateFilters(page, from, to);
        } catch(TicaPortalException safe) {if("TIMEOUT".equals(safe.code))throw new TicaPortalException(stage);throw safe;}
        catch(RuntimeException failure) {throw new TicaPortalException(stage);}
    }

    static String dateUrl(LocalDate from, LocalDate to) {
        return PORTAL + "/dates?tableFilters[date][date_from]=" + encode(from + " 00:00:00")
                + "&tableFilters[date][date_until]=" + encode(to + " 23:59:59");
    }

    private static void verifyDateFilters(Page page, LocalDate from, LocalDate to) {
        if (!isDates(page.url()))throw new TicaPortalException("AUTH_FAILED");
        if (!(from + " 00:00:00").equals(queryValue(page.url(), "tableFilters[date][date_from]")))
            throw new TicaPortalException("FILTER_FROM_URL_INVALID");
        if (!(to + " 23:59:59").equals(queryValue(page.url(), "tableFilters[date][date_until]")))
            throw new TicaPortalException("FILTER_TO_URL_INVALID");
        // Alpine initializes these display-only inputs after DOMContentLoaded; wait for the actual values.
        try {page.waitForCondition(()->from.format(DUTCH_DATE).equals(page.locator(DATE_FROM).inputValue()),
                new Page.WaitForConditionOptions().setTimeout(10_000));}
        catch(TimeoutError mismatch) {throw new TicaPortalException("FILTER_FROM_VALUE_INVALID");}
        try {page.waitForCondition(()->to.format(DUTCH_DATE).equals(page.locator(DATE_TO).inputValue()),
                new Page.WaitForConditionOptions().setTimeout(10_000));}
        catch(TimeoutError mismatch) {throw new TicaPortalException("FILTER_TO_VALUE_INVALID");}
    }

    private static void verifyAccount(Page page, String username) {
        if (!isDates(page.url())) throw new TicaPortalException("AUTH_FAILED");
        Locator avatar = page.locator("img[alt^='Avatar van ']");
        if (avatar.count() != 1) throw new TicaPortalException("ACCOUNT_MISMATCH");
        String alt = avatar.getAttribute("alt");
        if (alt == null || !alt.startsWith("Avatar van " + username + " "))
            throw new TicaPortalException("ACCOUNT_MISMATCH");
    }

    private static Locator formInput(Page page,String selector) {
        Locator selected=null;
        for(Locator candidate:page.locator(selector+":visible").all())if(inDocument(candidate)) {
            if(selected!=null)throw new TicaPortalException("AUTH_MULTIPLE_INPUTS");
            selected=candidate;
        }
        return selected;
    }

    private static boolean hasInDocument(Locator candidates) {
        for(Locator candidate:candidates.all())if(inDocument(candidate))return true;
        return false;
    }

    private static boolean inDocument(Locator candidate) {
        var box=candidate.boundingBox();
        // Authentik's autofill helper is an absolute input at (-2000,-2000). It has a
        // nonzero box, so Playwright :visible includes it although it is outside the document.
        // Real controls below the current viewport are still included; no MFA is bypassed.
        return box!=null&&box.x+box.width>0&&box.y+box.height>0;
    }

    private static void checkChallenge(Page page) {
        if(hasInDocument(page.locator("input[autocomplete='one-time-code']:visible, input[name='otp']:visible")))
            throw new TicaPortalException("AUTH_MFA_INPUT");
        if(hasInDocument(page.locator("iframe[src*='captcha']:visible")))
            throw new TicaPortalException("AUTH_CAPTCHA_FRAME");
        if(hasInDocument(page.locator("input[name*='captcha']:visible")))
            throw new TicaPortalException("AUTH_CAPTCHA_INPUT");
        if(page.getByText(Pattern.compile("^captcha.*",Pattern.CASE_INSENSITIVE)).first().isVisible())
            throw new TicaPortalException("AUTH_CAPTCHA_TEXT");
        if(page.getByText(Pattern.compile("^(verification code|authenticator code|authenticatiecode|beveiligingscode|two-factor authentication|multi-factor authentication).*",Pattern.CASE_INSENSITIVE)).first().isVisible())
            throw new TicaPortalException("AUTH_MFA_TEXT");
    }

    static boolean allowedUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && HOSTS.contains(uri.getHost())
                    && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (IllegalArgumentException invalid) { return false; }
    }

    static boolean allowedDownload(String value) {
        // Livewire may synthesize the browser download as a same-origin blob URL.
        return allowedUrl(value) && "exposant.tica.nl".equals(URI.create(value).getHost())
                || value.startsWith("blob:" + PORTAL + "/");
    }

    private static boolean isDates(String value) {
        return allowedUrl(value) && "exposant.tica.nl".equals(URI.create(value).getHost())
                && "/dates".equals(URI.create(value).getPath());
    }

    private static void requireAuthenticationHost(String value) {
        if (!allowedUrl(value) || !"auth.pwmarkets.beeyond.nl".equals(URI.create(value).getHost()))
            throw new TicaPortalException("AUTH_FAILED");
    }

    static String queryValue(String url, String wanted) {
        try {
            String query = URI.create(url).getRawQuery();
            if (query == null) return null;
            String result = null;
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                if (URLDecoder.decode(parts[0], StandardCharsets.UTF_8).equals(wanted)) {
                    if (result != null) return null; // Ambiguous filters fail closed.
                    result = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                }
            }
            return result;
        } catch (IllegalArgumentException invalid) { return null; }
    }

    static void validateCsv(byte[] bytes) {
        if (bytes.length < 1 || bytes.length > TicaPortalClient.MAX_CSV_BYTES)
            throw new TicaPortalException("DOWNLOAD_TOO_LARGE");
        String text = new String(bytes, StandardCharsets.UTF_8);
        String first = text.startsWith("\uFEFF") ? text.substring(1) : text;
        if (!first.startsWith("\"Locatie\";\"Exposantennummer\";\"Datum\";\"Tijd\";\"Transactienummer\";\"Kassabonregel\";"))
            throw new TicaPortalException("DOWNLOAD_HEADER_INVALID");
        if (text.chars().filter(value -> value == '\n').limit(MAX_LINES + 1L).count() > MAX_LINES)
            throw new TicaPortalException("DOWNLOAD_TOO_LARGE");
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static double remaining(long deadline) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) throw new TicaPortalException("TIMEOUT");
        return remaining;
    }
}
