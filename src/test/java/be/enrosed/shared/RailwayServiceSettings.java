package be.enrosed.shared;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The service settings written in {@code .railway/railway.ts}. The file is TypeScript, so this
 * reads its text with the comments removed: a setting that survives only as a comment does not
 * count. Comparing the file with a live environment is the job of {@code railway config plan}.
 */
final class RailwayServiceSettings {

    static final Path FILE = Path.of(".railway/railway.ts");

    private static final Pattern STRING_OR_COMMENT = Pattern.compile(
            "\"(?:\\\\.|[^\"\\\\])*\"|`(?:\\\\.|[^`\\\\])*`|//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    private final String source;

    private RailwayServiceSettings(String source) {
        this.source = source;
    }

    static RailwayServiceSettings read() throws IOException {
        return new RailwayServiceSettings(STRING_OR_COMMENT.matcher(Files.readString(FILE))
                .replaceAll(match -> match.group().startsWith("/")
                        ? ""
                        : Matcher.quoteReplacement(match.group())));
    }

    boolean has(String block, String key) {
        return Pattern.compile("\\b" + key + "\\s*:").matcher(block(block)).find();
    }

    String text(String block, String key) {
        return value(block, key, "\"([^\"]*)\"");
    }

    int number(String block, String key) {
        return Integer.parseInt(value(block, key, "(\\d+)\\b"));
    }

    List<String> list(String block, String key) {
        return Pattern.compile("\"([^\"]*)\"").matcher(value(block, key, "\\[([^\\]]*)\\]"))
                .results().map(MatchResult::group).map(item -> item.substring(1, item.length() - 1))
                .toList();
    }

    private String value(String block, String key, String shape) {
        Matcher matcher = Pattern.compile("\\b" + key + "\\s*:\\s*" + shape).matcher(block(block));
        if (!matcher.find()) {
            throw new AssertionError(FILE + " sets no " + block + "." + key);
        }
        return matcher.group(1);
    }

    /** The object literal assigned to {@code name}, braces included. */
    private String block(String name) {
        Matcher start = Pattern.compile("\\b" + name + "\\s*:\\s*\\{").matcher(source);
        if (start.find()) {
            int depth = 0;
            for (int i = start.end() - 1; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}' && --depth == 0) {
                    return source.substring(start.end() - 1, i + 1);
                }
            }
        }
        throw new AssertionError(FILE + " has no " + name + " block");
    }
}
