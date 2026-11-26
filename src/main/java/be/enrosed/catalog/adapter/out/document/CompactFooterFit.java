package be.enrosed.catalog.adapter.out.document;

import com.openhtmltopdf.layout.Layer;
import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import com.openhtmltopdf.render.Box;
import com.openhtmltopdf.render.LineBox;
import org.w3c.dom.Element;

import java.util.Arrays;

/**
 * How many lines the compact catalogue's footer note takes, from the finished layout.
 *
 * The note stands in the bottom page margin, which is as deep as the template says and does not
 * grow with its text: 20 mm holds three note lines above the brand line and the page number. With
 * a spill both basis sentences gained a clause (2026-11-26), and a price list for a named
 * recipient with a validity then runs to a fourth line in Greek, and with a long recipient name
 * in other languages (Portuguese, Turkish and French were measured), which pushes the brand line
 * to 2 mm from the paper edge, where a printer no longer prints.
 *
 * A footer in a page margin is laid out per page and is not part of the document's box tree, so
 * the template carries a hidden copy of the note ({@code .footer-probe}: as wide and set as the
 * footer, absolutely positioned, never painted). Reading its lines keeps fonts, languages,
 * recipient names and copy edited in the dashboard accounted for.
 */
final class CompactFooterFit {

    private CompactFooterFit() {}

    /** The lines of the footer note; 0 when the document has no probe (nothing is known, nothing changes). */
    static int noteLines(PdfBoxRenderer laidOut) {
        /* An absolutely positioned box hangs off the root's layer, not its child boxes. */
        for (Layer layer : laidOut.getRootBox().getLayer().getChildren()) {
            Box probe = layer.getMaster();
            if (probe.getElement() == null || !hasClass(probe.getElement(), "footer-probe")) continue;
            for (int index = 0; index < probe.getChildCount(); index++) {
                Box note = probe.getChild(index);
                if (note.getElement() != null && hasClass(note.getElement(), "footer-note")) return lines(note);
            }
        }
        return 0;
    }

    /* The note is plain text: every child of its box is one line of it. */
    private static int lines(Box note) {
        int lines = 0;
        for (int index = 0; index < note.getChildCount(); index++) {
            if (note.getChild(index) instanceof LineBox) lines++;
        }
        return lines;
    }

    private static boolean hasClass(Element element, String name) {
        return Arrays.asList(element.getAttribute("class").trim().split("\\s+")).contains(name);
    }
}
