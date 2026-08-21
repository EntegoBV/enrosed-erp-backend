package be.enrosed.shared;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/** One explicit fallback policy shared by public JSON and catalogue documents. */
public final class LanguageFallback {
    private LanguageFallback() {}

    /** Requested language, then English, then Dutch; duplicates are removed. */
    public static List<Language> chain(Language requested) {
        LinkedHashSet<Language> result = new LinkedHashSet<>();
        if (requested != null) result.add(requested);
        result.add(Language.EN);
        result.add(Language.NL);
        return List.copyOf(result);
    }

    public static <R> Resolved<String> text(
            Iterable<R> rows,
            Language requested,
            Function<R, Language> language,
            Function<R, String> value,
            String base) {
        return resolve(rows, requested, language, value,
                candidate -> candidate != null && !candidate.isBlank(), base);
    }

    public static <R, T> Resolved<T> resolve(
            Iterable<R> rows,
            Language requested,
            Function<R, Language> language,
            Function<R, T> value,
            Predicate<T> usable,
            T base) {
        List<R> materialized = new ArrayList<>();
        rows.forEach(materialized::add);
        for (Language candidate : chain(requested)) {
            for (R row : materialized) {
                if (language.apply(row) != candidate) continue;
                T translated = value.apply(row);
                if (usable.test(translated)) return new Resolved<>(translated, candidate);
            }
        }
        return new Resolved<>(base, null);
    }

    /** Null sourceLanguage means the legacy/base field was used. */
    public record Resolved<T>(T value, Language sourceLanguage) {
        public boolean fallbackFrom(Language requested) {
            return sourceLanguage == null || sourceLanguage != requested;
        }
    }
}
