package be.enrosed.catalog.application;

import be.enrosed.catalog.application.ProductPhotoExport.FileEntry;
import be.enrosed.catalog.application.ProductPhotoExport.PhotoSelection;
import be.enrosed.catalog.application.ProductPhotoExport.Plan;
import be.enrosed.catalog.application.ProductPhotoExport.ProductEntry;
import be.enrosed.catalog.application.ProductPhotoExport.Request;
import be.enrosed.catalog.application.ProductPhotoExport.Scope;
import be.enrosed.catalog.application.ProductPhotoExport.Source;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Packaging;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.SalesUnit;
import be.enrosed.shared.Language;
import be.enrosed.shared.PhotoExportText;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotoExportReadmeTest {

    private static final ZonedDateTime AT = ZonedDateTime.of(2026, 9, 22, 14, 5, 0, 0,
            ZoneId.of("Europe/Brussels"));

    @Test
    void dutchReadMeExplainsTheExportAndListsEveryProduct() {
        String text = PhotoExportReadme.render(plan(Language.NL), AT);

        assertTrue(text.startsWith("﻿Enrosed — productfoto’s\r\n"), "UTF-8 BOM, then the title");
        assertTrue(text.contains("\r\n") && !text.replace("\r\n", "").contains("\n"), "CRLF only");
        assertTrue(text.contains("Geëxporteerd op: 22/09/2026 14:05"), text);
        assertTrue(text.contains("Selectie: Producten op de website · Alleen websitefoto’s"));
        assertTrue(text.contains("Producten: 2"));
        assertTrue(text.contains("Foto’s: 2"));
        assertTrue(text.contains("01-hoofdfoto"));
        assertTrue(text.contains("‘Alle kleuren van de reeks’"), "the explanation quotes the real role label");

        assertTrue(text.contains("Map: ENR-1 - Zeeproos - Rood"));
        assertTrue(text.contains("Reeks: Zeeprozen"));
        assertTrue(text.contains("Categorie: Geschenken"));
        assertTrue(text.contains("Afmetingen product (B × D × H): 15 × 30 × 12,5 cm"));
        assertTrue(text.contains("Gewicht per stuk: 0,45 kg"));
        assertTrue(text.contains("Verpakking: Display met 8 stuks — 20 × 12 × 30 cm"));
        assertTrue(text.contains("EAN verpakking: 5400000000028"));
        assertTrue(text.contains("Inhoud omdoos: 6 displays per omdoos (48 stuks)"));
        assertTrue(text.contains("Afmetingen omdoos (B × D × H): 40 × 30 × 30 cm"));
        assertTrue(text.contains("Gewicht omdoos: 9,5 kg"));
        assertTrue(text.contains("EAN (stuk): 5400000000011"));
        assertTrue(text.contains("ITF-14 (omdoos): 15400000000018"));
        assertTrue(text.contains("  01-hoofdfoto.jpg — Hoofdfoto · Op de website · Alle kleuren van de reeks"
                + " — 4000 × 3000 px — origineel bestand: IMG_0001.JPG"), text);
        assertTrue(text.contains("  02.png — Alleen deze kleur — 900 × 600 px"));

        int second = text.indexOf("[2] ENR-2 — Losse roos");
        assertTrue(second > 0);
        assertTrue(text.indexOf("Bestanden: geen foto’s", second) > second);
        assertFalse(text.substring(second).contains("Map:"), "a product without photos gets no folder");
        assertFalse(text.substring(second).contains("Inhoud omdoos"), "the default empty carton is not a fact");
    }

    @Test
    void otherLanguagesUseTheirOwnFileNameAndLabels() {
        assertEquals("LEESMIJ.txt", PhotoExportReadme.fileName(Language.NL));
        for (Language language : Language.values()) {
            if (language != Language.NL) assertEquals("README.txt", PhotoExportReadme.fileName(language));
            String text = new String(PhotoExportReadme.bytes(plan(language), AT), StandardCharsets.UTF_8);
            assertTrue(text.contains(PhotoExportText.text(language, "title")), language.name());
            assertTrue(text.contains(PhotoExportText.text(language, "role.lead")), language.name());
            assertTrue(text.contains(PhotoExportText.text(language, "noPhotos")), language.name());
            assertFalse(text.contains("{"), language + " left a placeholder unfilled");
        }
        String english = PhotoExportReadme.render(plan(Language.EN), AT);
        assertTrue(english.contains("Exported on: 22 September 2026 14:05"), english);
        assertTrue(english.contains("Product dimensions (W × D × H): 15 × 30 × 12.5 cm"));
        assertTrue(english.contains("6 displays per carton (48 pieces)"));
    }

    private static Plan plan(Language language) {
        Dimensions product = new Dimensions(new BigDecimal("15"), new BigDecimal("30"), new BigDecimal("12.50"),
                new BigDecimal("0.450"));
        Packaging display = new Packaging(PackagingKind.DISPLAY,
                new Dimensions(new BigDecimal("20"), new BigDecimal("12"), new BigDecimal("30")),
                "5400000000028", 8, SalesUnit.DISPLAY);
        Carton carton = new Carton(new Dimensions(new BigDecimal("40"), new BigDecimal("30"), new BigDecimal("30")),
                6, new BigDecimal("9.5"));
        ProductEntry withPhotos = new ProductEntry("ENR-1 - Zeeproos - Rood", "ENR-1", "Zeeproos", "Rood", null,
                "Zeeprozen", "Geschenken", product, display, carton, "5400000000011", "15400000000018",
                List.of(
                        new FileEntry("01-hoofdfoto.jpg", "k1", "image/jpeg", 9_000_000, 4000, 3000,
                                "IMG_0001.JPG", true, true, Source.SERIES),
                        new FileEntry("02.png", "k2", "image/png", 500_000, 900, 600, null,
                                false, false, Source.VARIANT)));
        ProductEntry withoutPhotos = new ProductEntry(null, "ENR-2", "Losse roos", null, null, null, null,
                Dimensions.empty(), Packaging.none(), Carton.empty(), null, null, List.of());
        return new Plan(new Request(Scope.WEBSITE, PhotoSelection.WEBSITE, language),
                List.of(withPhotos, withoutPhotos), 2, 9_500_000);
    }
}
