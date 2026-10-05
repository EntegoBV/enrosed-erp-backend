package be.enrosed.catalog.adapter.out.document;

import com.openhtmltopdf.layout.Layer;
import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import com.openhtmltopdf.render.Box;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finds brochure family sheets whose content runs into the footer, from the finished layout.
 *
 * A family sheet is one fixed A4 page that clips whatever does not fit, so an overlong sheet
 * silently loses its last rows - with a long specification list even the whole SKU table.
 * Reading the real layout keeps fonts, languages and template changes accounted for.
 */
final class FamilySheetFit {

    /** Room kept between the last row and the footer rule. */
    static final double CLEARANCE_MM = 2;

    /** A sheet whose content ends {@code millimetres} too low for the clearance above the footer. */
    record Overflow(String anchor, boolean compact, double millimetres) {}

    private FamilySheetFit() {}

    static List<Overflow> overflowing(PdfBoxRenderer laidOut) {
        Box root = laidOut.getRootBox();
        double dotsPerMm = laidOut.getDotsPerPoint() * 72 / 25.4;
        NodeList sections = laidOut.getDocument().getElementsByTagName("section");
        List<Overflow> result = new ArrayList<>();
        for (int index = 0; index < sections.getLength(); index++) {
            Element section = (Element) sections.item(index);
            if (!hasClass(section, "family-page")) continue;
            List<Box> boxes = root.getElementBoxes(section);
            if (boxes.isEmpty() || boxes.getFirst().getLayer() == null) continue;
            Box sheet = boxes.getFirst();
            Integer footerTop = footerTop(sheet);
            if (footerTop == null) continue;
            int contentBottom = Integer.MIN_VALUE;
            for (Node node = section.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (!(node instanceof Element child) || hasClass(child, "footer")) continue;
                // Content pushed past the sheet lands on a later page, so its box sits lower still.
                for (Box box : sheet.getElementBoxes(child)) {
                    contentBottom = Math.max(contentBottom, box.getAbsY() + box.getHeight());
                }
            }
            double overflow = (contentBottom - footerTop) / dotsPerMm + CLEARANCE_MM;
            if (overflow > 0) {
                result.add(new Overflow(section.getAttribute("id"),
                        hasClass(section, "family-page--compact"), overflow));
            }
        }
        return List.copyOf(result);
    }

    /** The footer is absolutely positioned: it hangs off the sheet's layer, not its child boxes. */
    private static Integer footerTop(Box sheet) {
        for (Layer layer : sheet.getLayer().getChildren()) {
            Box master = layer.getMaster();
            if (master.getElement() != null && hasClass(master.getElement(), "footer")) {
                return master.getAbsY();
            }
        }
        return null;
    }

    private static boolean hasClass(Element element, String name) {
        return Arrays.asList(element.getAttribute("class").trim().split("\\s+")).contains(name);
    }
}
