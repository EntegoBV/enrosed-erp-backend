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
 * Reading the real layout keeps fonts, languages and template changes accounted for. The
 * overview pages are fixed A4 pages too: their range table must end above the footnote.
 */
final class FamilySheetFit {

    /** Room kept between the last row and the footer rule. */
    static final double CLEARANCE_MM = 2;

    /** A sheet whose content ends {@code millimetres} too low for the clearance above the footer. */
    record Overflow(String anchor, boolean compact, double millimetres) {}

    /**
     * The {@code page}-th overview page and the room left between its range table and the clearance
     * above its footnote; a negative room is how far the last row runs into it.
     */
    record OverviewRoom(int page, double millimetres) {
        boolean overflowing() {
            return millimetres < 0;
        }
    }

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
            Integer footerTop = layerTop(sheet, "footer");
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

    /** Every overview page with the room its range table leaves above the footnote's clearance. */
    static List<OverviewRoom> overviewRoom(PdfBoxRenderer laidOut) {
        Box root = laidOut.getRootBox();
        double dotsPerMm = laidOut.getDotsPerPoint() * 72 / 25.4;
        NodeList sections = laidOut.getDocument().getElementsByTagName("section");
        List<OverviewRoom> result = new ArrayList<>();
        int page = 0;
        for (int index = 0; index < sections.getLength(); index++) {
            Element section = (Element) sections.item(index);
            if (!hasClass(section, "overview-page")) continue;
            page++;
            List<Box> boxes = root.getElementBoxes(section);
            if (boxes.isEmpty() || boxes.getFirst().getLayer() == null) continue;
            Box sheet = boxes.getFirst();
            Integer footnoteTop = layerTop(sheet, "overview-footnote");
            if (footnoteTop == null) continue;
            int contentBottom = Integer.MIN_VALUE;
            for (Node node = section.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (!(node instanceof Element child) || hasClass(child, "overview-footnote")) continue;
                for (Box box : sheet.getElementBoxes(child)) {
                    contentBottom = Math.max(contentBottom, box.getAbsY() + box.getHeight());
                }
            }
            result.add(new OverviewRoom(page, (footnoteTop - contentBottom) / dotsPerMm - CLEARANCE_MM));
        }
        return List.copyOf(result);
    }

    /** Footer and footnote are absolutely positioned: they hang off the sheet's layer, not its child boxes. */
    private static Integer layerTop(Box sheet, String className) {
        for (Layer layer : sheet.getLayer().getChildren()) {
            Box master = layer.getMaster();
            if (master.getElement() != null && hasClass(master.getElement(), className)) {
                return master.getAbsY();
            }
        }
        return null;
    }

    private static boolean hasClass(Element element, String name) {
        return Arrays.asList(element.getAttribute("class").trim().split("\\s+")).contains(name);
    }
}
