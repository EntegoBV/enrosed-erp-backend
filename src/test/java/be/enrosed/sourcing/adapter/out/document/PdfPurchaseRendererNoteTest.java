package be.enrosed.sourcing.adapter.out.document;

import be.enrosed.sourcing.adapter.out.document.PdfPurchaseRenderer.NoteLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The supplier note reaches the PDF as paragraphs, points and sub-points. */
class PdfPurchaseRendererNoteTest {

    @Test
    void pointsAndSubPointsKeepTheirLevel() {
        List<NoteLine> lines = PdfPurchaseRenderer.noteLines("""
                Match the approved colour sample.

                - Centre the logo
                  - Pantone 186C only
                \t- No gloss finish
                * Use cardboard corner protection
                """);

        assertEquals(List.of(
                new NoteLine(0, "Match the approved colour sample."),
                new NoteLine(1, "Centre the logo"),
                new NoteLine(2, "Pantone 186C only"),
                new NoteLine(2, "No gloss finish"),
                new NoteLine(1, "Use cardboard corner protection")), lines);
    }

    @Test
    void aDashInsideASentenceIsNotAPoint() {
        List<NoteLine> lines = PdfPurchaseRenderer.noteLines("Glass dome - 3 roses, red-white");

        assertEquals(List.of(new NoteLine(0, "Glass dome - 3 roses, red-white")), lines);
    }

    @Test
    void longPointsStayOneLineEach() {
        String point = "- " + "word ".repeat(80).strip();
        List<NoteLine> lines = PdfPurchaseRenderer.noteLines(String.join("\n", point, point, point));

        assertEquals(3, lines.size(), "a 400-character point is still one point");
        lines.forEach(line -> assertEquals(1, line.level()));
    }

    @Test
    void anEndlessParagraphIsCutAtWhitespace() {
        List<NoteLine> lines = PdfPurchaseRenderer.noteLines("word ".repeat(600).strip());

        assertTrue(lines.size() >= 2);
        lines.forEach(line -> assertTrue(line.text().length() <= 1500));
        assertEquals("word ".repeat(600).strip(),
                String.join(" ", lines.stream().map(NoteLine::text).toList()));
    }

    @Test
    void blankNotesGiveNoLines() {
        assertEquals(List.of(), PdfPurchaseRenderer.noteLines(null));
        assertEquals(List.of(), PdfPurchaseRenderer.noteLines("  \n "));
    }
}
