package be.enrosed.sourcing.tracking.bill;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The text of a PDF as a form reads: lines of cells, each cell with where it stands on the page. A bill of lading is
 * a grid of boxes ("Ocean Vessel/Voy. No." with the ship under it, the port of loading beside it), so a value is
 * found by its place under its label, not by the order the words come out in. Words that stand a normal space apart
 * are one cell; a wider gap starts the next cell.
 *
 * <p>Reads at most {@link #MAX_PAGES} pages and {@link #MAX_BYTES} bytes; a scan has no cells (there is no OCR), and
 * a file that needs a password or forbids reading its text gives none either.</p>
 */
public final class BillText {
    public static final int MAX_BYTES = 25 * 1024 * 1024;
    static final int MAX_PAGES = 8;
    /** A gap wider than this many space widths (and at least three quarters of the letter height) starts a new cell. */
    private static final float CELL_GAP = 2.6f;

    /** One run of words: its text and where it starts and ends across the page, in points. */
    public record Cell(float x, float xEnd, String text) {}

    /** One line of a page, top to bottom: its cells left to right. */
    public record Line(int page, float y, List<Cell> cells) {
        public String text() {
            StringBuilder out = new StringBuilder();
            for (Cell cell : cells) {
                if (out.length() > 0) out.append("  ");
                out.append(cell.text());
            }
            return out.toString();
        }
    }

    private BillText() {}

    /** The lines of the document; empty for a scan, an encrypted file or something that is no PDF. */
    public static List<Line> read(byte[] bytes) {
        if (bytes == null || bytes.length < 5 || bytes.length > MAX_BYTES || bytes[0] != '%' || bytes[1] != 'P'
                || bytes[2] != 'D' || bytes[3] != 'F' || bytes[4] != '-') return List.of();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            /* Carriers lock their PDFs against editing; one that opens without a password still reads. */
            if (document.getNumberOfPages() < 1) return List.of();
            Stripper stripper = new Stripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(MAX_PAGES, document.getNumberOfPages()));
            stripper.getText(document);
            stripper.endLine();
            return List.copyOf(stripper.lines);
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
    }

    /** The whole text, one line per line, cells two spaces apart: for words that stand anywhere ("DRAFT", "EGLV 1436…"). */
    public static String flat(List<Line> lines) {
        StringBuilder out = new StringBuilder();
        for (Line line : lines) out.append(line.text()).append('\n');
        return out.toString();
    }

    private static final class Stripper extends PDFTextStripper {
        final List<Line> lines = new ArrayList<>();
        private final List<Cell> cells = new ArrayList<>();
        private StringBuilder text = new StringBuilder();
        private float cellX;
        private float cellEnd;
        private float lineY;
        private boolean open;
        private boolean spaced;

        Stripper() throws IOException {}

        /**
         * Glyph by glyph: carriers pad their forms with runs of spaces and whole lines arrive as one string, so only
         * the place of each letter says where a cell ends.
         */
        @Override
        protected void writeString(String chunk, List<TextPosition> positions) {
            if (positions == null) return;
            for (TextPosition glyph : positions) {
                String letter = glyph.getUnicode();
                if (letter == null || letter.isEmpty()) continue;
                /* A space glyph says nothing by itself: carriers print one over the first letter of a box. The room
                   between two letters decides. */
                if (letter.isBlank()) continue;
                float x = glyph.getXDirAdj();
                float end = x + glyph.getWidthDirAdj();
                if (!open) {
                    lineY = glyph.getYDirAdj();
                    startCell(x);
                    open = true;
                } else if (x - cellEnd > gap(glyph) || x < cellEnd - gap(glyph)) {
                    closeCell();
                    startCell(x);
                } else if (text.length() > 0 && x - cellEnd > (spaced ? 0.08f : 0.22f) * size(glyph)) {
                    text.append(' ');
                }
                spaced = false;
                text.append(letter);
                cellEnd = Math.max(cellEnd, end);
            }
        }

        /** A normal space is about a quarter of the letter height; three of them apart is the next box. */
        private static float gap(TextPosition glyph) {
            return Math.max(CELL_GAP * Math.min(glyph.getWidthOfSpace(), 0.5f * size(glyph)), 0.75f * size(glyph));
        }

        private static float size(TextPosition glyph) {
            float size = glyph.getFontSizeInPt();
            if (!(size > 2f)) size = glyph.getHeightDir() * 1.4f;
            return size > 2f ? Math.min(size, 40f) : 8f;
        }

        @Override
        protected void writeWordSeparator() {
            spaced = true;
        }

        @Override
        protected void writeLineSeparator() {
            endLine();
        }

        @Override
        protected void writePageEnd() {
            endLine();
        }

        void endLine() {
            if (!open) return;
            closeCell();
            if (!cells.isEmpty()) lines.add(new Line(getCurrentPageNo(), lineY, List.copyOf(cells)));
            cells.clear();
            open = false;
            spaced = false;
        }

        private void startCell(float x) {
            text = new StringBuilder();
            cellX = x;
            cellEnd = x;
        }

        private void closeCell() {
            String value = text.toString().strip();
            if (!value.isEmpty()) cells.add(new Cell(cellX, cellEnd, value));
        }
    }
}
