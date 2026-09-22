package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.CategoryEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductPhotoEntity;
import be.enrosed.catalog.application.FamilyPhotoCompatibilityService;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.catalog.domain.SalesUnit;
import be.enrosed.shared.Currency;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The photo export end to end: authenticated preparation, token download
 * without credentials, ZIP layout, original bytes and a read-me that never
 * leaks prices, costs or supplier data.
 */
@QuarkusTest
class ProductPhotoExportHttpTest {

    private static final String PASSWORD = "named-auth-test-password";

    @Inject EntityManager entityManager;
    @Inject PhotoStorage storage;
    @Inject FamilyPhotoCompatibilityService familyPhotos;

    private Seed seed;

    @BeforeEach
    void seed() throws Exception {
        seed = seedCatalogue();
    }

    @AfterEach
    void cleanup() {
        if (seed != null) cleanup(seed);
    }

    @Test
    void allPhotosOfAllProductsArriveAsOriginalsWithAReadMeWithoutPrices() throws Exception {
        ExtractableResponse<Response> prepared = prepare("{\"scope\":\"ALL\",\"photos\":\"ALL\",\"language\":\"NL\"}");
        String downloadUrl = prepared.path("downloadUrl");
        String fileName = prepared.path("fileName");
        assertTrue(downloadUrl.matches("/api/products/photo-export/[A-Za-z0-9_-]{43}"), downloadUrl);
        assertTrue(fileName.matches("enrosed-productfotos-\\d{4}-\\d{2}-\\d{2}\\.zip"), fileName);
        Instant expiresAt = Instant.parse(prepared.path("expiresAt"));
        assertTrue(Duration.between(Instant.now(), expiresAt).toMinutes() >= 14, "valid for 15 minutes");
        assertTrue(prepared.<Integer>path("productCount") >= 4, "A, B, C and the inactive D at least");
        int ownPhotos = 3 + 1; // A: variant, series, own; D: own (the copy on A is a duplicate)
        assertTrue(prepared.<Integer>path("photoCount") >= ownPhotos);
        long seeded = seed.variantLarge.length + seed.seriesLarge.length * 2L + seed.own.length
                + seed.inactiveOwn.length;
        assertTrue(((Number) prepared.path("totalBytes")).longValue() >= seeded);

        /* No Authorization header: the token is the authorization. */
        ExtractableResponse<Response> download = given().when().get(downloadUrl)
                .then().statusCode(200).contentType("application/zip").extract();
        assertTrue(download.header("Content-Disposition").contains("attachment; filename=\"" + fileName + "\""));
        Map<String, Entry> zip = unzip(download.asByteArray());
        String root = fileName.substring(0, fileName.length() - ".zip".length()) + "/";

        String folderA = root + seed.skuA + " - Zeeproos doos - Rood - Groot/";
        assertEquals(List.of(folderA + "01-hoofdfoto.jpg", folderA + "02.jpg", folderA + "03.jpg"),
                files(zip, folderA), "own copy of the series photo is exported once");
        assertArrayEquals(seed.variantLarge, zip.get(folderA + "01-hoofdfoto.jpg").bytes,
                "the Hoofdfoto is the variant's canonical photo, as uploaded");
        assertArrayEquals(seed.seriesLarge, zip.get(folderA + "02.jpg").bytes,
                "the series photo is the untouched large upload, never the small rendition");
        assertArrayEquals(seed.own, zip.get(folderA + "03.jpg").bytes);

        String folderB = root + seed.skuB + " - Zeeproos doos - Blauw/";
        assertEquals(List.of(folderB + "01-hoofdfoto.jpg"), files(zip, folderB));
        assertArrayEquals(seed.seriesLarge, zip.get(folderB + "01-hoofdfoto.jpg").bytes);

        String folderD = root + seed.skuD + " - Oude roos/";
        assertArrayEquals(seed.inactiveOwn, zip.get(folderD + "01-hoofdfoto.jpg").bytes, "ALL includes inactive");
        assertTrue(zip.keySet().stream().noneMatch(name -> name.startsWith(root + seed.skuC + " ")),
                "no photos, no folder");
        assertTrue(zip.keySet().stream().noneMatch(name -> name.startsWith(root + seed.skuE + " ")),
                "demo pieces never");
        assertFalse(readme(zip, root + "LEESMIJ.txt").contains("] " + seed.skuE + " — "));
        zip.forEach((name, entry) -> assertEquals(ZipEntry.STORED, entry.method, name + " is not recompressed"));

        String readme = readme(zip, root + "LEESMIJ.txt");
        assertTrue(readme.contains("Selectie: Alle producten · Alle foto’s"));
        String blockA = block(readme, seed.skuA);
        assertTrue(blockA.contains("Map: " + seed.skuA + " - Zeeproos doos - Rood - Groot"), blockA);
        assertTrue(blockA.contains("Reeks: Zeeprozen " + seed.prefix));
        assertTrue(blockA.contains("Categorie: Geschenken " + seed.prefix));
        assertTrue(blockA.contains("Afmetingen product (B × D × H): 15 × 30 × 12 cm"));
        assertTrue(blockA.contains("Gewicht per stuk: 0,45 kg"));
        assertTrue(blockA.contains("Verpakking: Display met 8 stuks"));
        assertTrue(blockA.contains("Inhoud omdoos: 6 displays per omdoos (48 stuks)"));
        assertTrue(blockA.contains("EAN (stuk): " + seed.prefix + "-EAN"));
        assertTrue(blockA.contains("ITF-14 (omdoos): " + seed.prefix + "-ITF"));
        assertTrue(blockA.contains("01-hoofdfoto.jpg — Hoofdfoto · Alleen deze kleur — 900 × 600 px"), blockA);
        assertTrue(blockA.contains("02.jpg — Op de website · Alle kleuren van de reeks — 1200 × 800 px"), blockA);
        assertTrue(blockA.contains("03.jpg — Losse productfoto — 640 × 480 px"), blockA);
        assertTrue(block(readme, seed.skuC).contains("Bestanden: geen foto’s"));
        assertFalse(block(readme, seed.skuC).contains("Map:"));

        String header = readme.substring(0, readme.indexOf("Overzicht per product"));
        for (String secret : List.of("123,45", "123.45", "67,89", "67.89", "12,34", "12.34", "3,21", "3.21",
                "55,5", "55.5", "7771", "GEHEIM", "0603199000", "EXW", "CNY", "PO-GEHEIM")) {
            assertFalse(blockA.contains(secret), "read-me leaks " + secret + ":\n" + blockA);
            assertFalse(header.contains(secret), "read-me header leaks " + secret);
        }

        /* A retry within the window downloads the same export again. */
        byte[] again = given().when().get(downloadUrl).then().statusCode(200).extract().asByteArray();
        assertArrayEquals(seed.variantLarge, unzip(again).get(folderA + "01-hoofdfoto.jpg").bytes);
    }

    @Test
    void websitePhotosOnlyFollowsThePublicGalleryAndActiveSkipsInactiveProducts() throws Exception {
        ExtractableResponse<Response> prepared =
                prepare("{\"scope\":\"ACTIVE\",\"photos\":\"WEBSITE\",\"language\":\"en\"}");
        String fileName = prepared.path("fileName");
        Map<String, Entry> zip = unzip(given().when().get(prepared.<String>path("downloadUrl"))
                .then().statusCode(200).extract().asByteArray());
        String root = fileName.substring(0, fileName.length() - ".zip".length()) + "/";

        String folderA = root + seed.skuA + " - Zeeproos doos - Red - Groot/";
        assertEquals(List.of(folderA + "01-hoofdfoto.jpg"), files(zip, folderA),
                "the internal variant photo and the own photos are not on the website; "
                        + "the folder name follows the export language (colour dictionary)");
        assertArrayEquals(seed.seriesLarge, zip.get(folderA + "01-hoofdfoto.jpg").bytes);
        assertTrue(zip.keySet().stream().noneMatch(name -> name.startsWith(root + seed.skuD + " ")),
                "inactive is skipped");
        assertTrue(zip.keySet().stream().noneMatch(name -> name.startsWith(root + seed.skuE + " ")));

        String readme = readme(zip, root + "README.txt");
        assertTrue(readme.contains("Selection: Active products · Website photos only"));
        assertTrue(block(readme, seed.skuA).contains("01-hoofdfoto.jpg — Main photo · On the website · "
                + "All colours in the series — 1200 × 800 px"), block(readme, seed.skuA));
        assertTrue(block(readme, seed.skuC).contains("Files: no photos"));
    }

    @Test
    void websiteScopeKeepsOnlyProductsOfPublishedSeries() throws Exception {
        ExtractableResponse<Response> prepared = prepare("{\"scope\":\"WEBSITE\"}");
        Map<String, Entry> zip = unzip(given().when().get(prepared.<String>path("downloadUrl"))
                .then().statusCode(200).extract().asByteArray());
        String readme = zip.entrySet().stream().filter(entry -> entry.getKey().endsWith("/LEESMIJ.txt"))
                .map(entry -> new String(entry.getValue().bytes, StandardCharsets.UTF_8)).findFirst().orElseThrow();
        assertTrue(readme.contains("] " + seed.skuA + " — "));
        assertTrue(readme.contains("] " + seed.skuB + " — "));
        assertFalse(readme.contains("] " + seed.skuC + " — "), "a flat SKU that is not published itself");
        assertFalse(readme.contains("] " + seed.skuD + " — "));
        assertFalse(readme.contains("] " + seed.skuE + " — "));
        assertTrue(readme.contains("Selectie: Producten op de website · Alle foto’s"), "defaults: all photos, Dutch");
    }

    @Test
    void preparingRequiresAnAdministratorAndValidOptions() {
        given().contentType("application/json").body("{\"scope\":\"ALL\"}")
                .when().post("/api/products/photo-export")
                .then().statusCode(401);

        String message = given().auth().preemptive().basic("emre", PASSWORD)
                .contentType("application/json").body("{\"scope\":\"EVERYTHING\"}")
                .when().post("/api/products/photo-export")
                .then().statusCode(422).extract().path("message");
        assertTrue(message.contains("EVERYTHING"), message);

        given().auth().preemptive().basic("emre", PASSWORD)
                .contentType("application/json").body("{\"language\":\"xx\"}")
                .when().post("/api/products/photo-export")
                .then().statusCode(422);
    }

    @Test
    void anUnknownOrMalformedTokenIsA404WithADutchMessage() {
        String body = given().when().get("/api/products/photo-export/" + "A".repeat(43))
                .then().statusCode(404).extract().asString();
        assertTrue(body.contains("verlopen of ongeldig"), body);
        given().when().get("/api/products/photo-export/not-a-token").then().statusCode(404);
    }

    /* ------------------------------------------------------------ helpers */

    private ExtractableResponse<Response> prepare(String body) {
        return given().auth().preemptive().basic("emre", PASSWORD)
                .contentType("application/json").body(body)
                .when().post("/api/products/photo-export")
                .then().statusCode(200).extract();
    }

    private record Entry(int method, byte[] bytes) {}

    private static Map<String, Entry> unzip(byte[] zip) throws Exception {
        Map<String, Entry> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                entries.put(entry.getName(), new Entry(entry.getMethod(), in.readAllBytes()));
            }
        }
        return entries;
    }

    private static List<String> files(Map<String, Entry> zip, String folder) {
        assertNotNull(zip.get(folder), "folder entry " + folder);
        return zip.keySet().stream().filter(name -> name.startsWith(folder) && !name.equals(folder)).toList();
    }

    private static String readme(Map<String, Entry> zip, String path) {
        Entry entry = zip.get(path);
        assertNotNull(entry, "read-me at " + path + " in " + zip.keySet().stream().filter(n -> n.endsWith(".txt")).toList());
        String text = new String(entry.bytes, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("﻿"));
        return text.substring(1);
    }

    /** One product's section of the read-me, from its "[n] SKU — name" line to the blank line after it. */
    private static String block(String readme, String sku) {
        int start = readme.indexOf("] " + sku + " — ");
        assertTrue(start > 0, sku + " is listed");
        int end = readme.indexOf("\r\n\r\n", start);
        return readme.substring(start, end < 0 ? readme.length() : end);
    }

    private record Seed(String prefix, String skuA, String skuB, String skuC, String skuD, String skuE,
                        long familyId, long categoryId, List<Long> productIds,
                        byte[] seriesLarge, byte[] variantLarge, byte[] own, byte[] inactiveOwn) {}

    private Seed seedCatalogue() throws Exception {
        String prefix = "PX" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        long seed = prefix.hashCode();
        byte[] seriesLarge = jpeg(1200, 800, seed + 1);
        byte[] seriesSmall = jpeg(480, 320, seed + 2);
        byte[] variantLarge = jpeg(900, 600, seed + 3);
        byte[] variantSmall = jpeg(480, 320, seed + 4);
        byte[] own = jpeg(640, 480, seed + 5);
        byte[] inactiveOwn = jpeg(300, 200, seed + 6);
        byte[] demoOwn = jpeg(200, 100, seed + 7);
        return QuarkusTransaction.requiringNew().call(() -> {
            CategoryEntity category = new CategoryEntity();
            category.code = prefix + "-CAT";
            category.name = "Geschenken " + prefix;
            category.position = 99;
            entityManager.persist(category);

            ProductFamilyEntity family = new ProductFamilyEntity();
            family.familyKey = prefix.toLowerCase(Locale.ROOT) + "-family";
            family.publicHandle = family.familyKey;
            family.name = "Zeeprozen " + prefix;
            family.active = true;
            family.categoryId = category.id;
            family.websiteStatus = PublicationState.PUBLISHED;
            family.orderAppStatus = PublicationState.DRAFT;
            family.catalogueStatus = PublicationState.DRAFT;
            entityManager.persist(family);
            entityManager.flush();

            ProductEntity a = product(prefix + "-A", "Zeeproos doos", "Rood", "Groot", family, 0, category);
            a.productLengthCm = new BigDecimal("15");
            a.productWidthCm = new BigDecimal("30");
            a.productHeightCm = new BigDecimal("12");
            a.productWeightKg = new BigDecimal("0.45");
            a.packagingKind = PackagingKind.DISPLAY;
            a.packagingPiecesPerUnit = 8;
            a.packagingSalesUnit = SalesUnit.DISPLAY;
            a.cartonLengthCm = new BigDecimal("40");
            a.cartonWidthCm = new BigDecimal("30");
            a.cartonHeightCm = new BigDecimal("30");
            a.piecesPerCarton = 6;
            a.canonicalBarcode = prefix + "-EAN";
            a.barcodeOuter = prefix + "-ITF";
            /* Everything below must stay out of the read-me. */
            a.fixedSalesPriceEur = new BigDecimal("123.45");
            a.landedCostEur = new BigDecimal("67.89");
            a.landedCostSource = "PO-GEHEIM-2026";
            a.exwPrice = new BigDecimal("12.34");
            a.exwCurrency = Currency.CNY;
            a.extraUnitCost = new BigDecimal("3.21");
            a.markupPct = new BigDecimal("55.5");
            a.supplierNote = "GEHEIM-LEVERANCIER";
            a.hsCode = "0603199000";
            a.stockQuantity = 7771;
            ProductEntity b = product(prefix + "-B", "Zeeproos doos", "Blauw", null, family, 1, category);
            ProductEntity c = product(prefix + "-C", "Losse roos", null, null, null, 0, category);
            ProductEntity d = product(prefix + "-D", "Oude roos", null, null, null, 0, category);
            d.active = false;
            ProductEntity e = product(prefix + "-E", "Demo roos", null, null, null, 0, category);
            e.demo = true;
            for (ProductEntity product : List.of(a, b, c, d, e)) entityManager.persist(product);
            entityManager.flush();

            ownPhoto(a, prefix + "-own.jpg", own, 0);
            ownPhoto(a, prefix + "-copy-of-series.jpg", seriesLarge, 1);
            ownPhoto(d, prefix + "-inactive.jpg", inactiveOwn, 0);
            ownPhoto(e, prefix + "-demo.jpg", demoOwn, 0);
            familyPhoto(family, prefix + "-series", seriesLarge, seriesSmall, null, "[\"WEBSITE\"]", 0);
            familyPhoto(family, prefix + "-variant", variantLarge, variantSmall, a, "[]", 1);
            entityManager.flush();
            familyPhotos.sync(family);

            return new Seed(prefix, a.sku, b.sku, c.sku, d.sku, e.sku, family.id, category.id,
                    List.of(a.id, b.id, c.id, d.id, e.id), seriesLarge, variantLarge, own, inactiveOwn);
        });
    }

    private static ProductEntity product(String sku, String name, String colour, String size,
                                         ProductFamilyEntity family, int position, CategoryEntity category) {
        ProductEntity product = new ProductEntity();
        product.sku = sku;
        product.name = name;
        product.colour = colour;
        product.variantSize = size;
        product.active = true;
        product.inventoryKnown = true;
        product.piecesPerCarton = 1;
        product.categoryId = category.id;
        product.variantPosition = position;
        if (family != null) {
            product.familyId = family.id;
            product.familyKey = family.familyKey;
            product.canonicalVariantKey = sku.toLowerCase(Locale.ROOT);
        }
        return product;
    }

    private void ownPhoto(ProductEntity product, String filename, byte[] bytes, int position) {
        PhotoStorage.Stored stored = storage.store(filename, "image/jpeg", bytes);
        ProductPhotoEntity photo = new ProductPhotoEntity();
        photo.product = product;
        photo.storageKey = stored.storageKey();
        photo.originalFilename = filename;
        photo.contentType = "image/jpeg";
        photo.sizeBytes = stored.sizeBytes();
        photo.widthPx = stored.widthPx();
        photo.heightPx = stored.heightPx();
        photo.position = position;
        product.photos.add(photo);
        entityManager.persist(photo);
    }

    private void familyPhoto(ProductFamilyEntity family, String name, byte[] large, byte[] small,
                             ProductEntity variant, String channels, int position) throws Exception {
        String largeSha = sha256(large);
        String smallSha = sha256(small);
        PhotoStorage.Stored largeStored = storage.storeKnown(
                "sha256-" + largeSha + ".jpg", name + "-large.jpg", "image/jpeg", large);
        PhotoStorage.Stored smallStored = storage.storeKnown(
                "sha256-" + smallSha + ".jpg", name + "-small.jpg", "image/jpeg", small);
        ProductFamilyPhotoEntity photo = new ProductFamilyPhotoEntity();
        photo.family = family;
        photo.sourceKey = "admin-" + largeSha;
        photo.originalFilename = name + "-upload.jpg";
        photo.originalWidthPx = largeStored.widthPx();
        photo.originalHeightPx = largeStored.heightPx();
        photo.smallStorageKey = smallStored.storageKey();
        photo.smallContentType = "image/jpeg";
        photo.smallSha256 = smallSha;
        photo.smallSizeBytes = smallStored.sizeBytes();
        photo.smallWidthPx = smallStored.widthPx();
        photo.smallHeightPx = smallStored.heightPx();
        photo.largeStorageKey = largeStored.storageKey();
        photo.largeContentType = "image/jpeg";
        photo.largeSha256 = largeSha;
        photo.largeSizeBytes = largeStored.sizeBytes();
        photo.largeWidthPx = largeStored.widthPx();
        photo.largeHeightPx = largeStored.heightPx();
        photo.position = position;
        photo.variantProduct = variant;
        photo.altTextSource = "ADMIN";
        photo.altTextsJson = "[{\"language\":\"NL\",\"alt\":\"Zeeprozen\"}]";
        photo.publishedChannelsJson = channels;
        family.photos.add(photo);
        entityManager.persist(photo);
    }

    private void cleanup(Seed seed) {
        QuarkusTransaction.requiringNew().run(() -> {
            ProductFamilyEntity family = entityManager.find(ProductFamilyEntity.class, seed.familyId);
            if (family != null) entityManager.remove(family);
            entityManager.flush();
            for (Long id : seed.productIds) {
                ProductEntity product = entityManager.find(ProductEntity.class, id);
                if (product != null) entityManager.remove(product);
            }
            CategoryEntity category = entityManager.find(CategoryEntity.class, seed.categoryId);
            if (category != null) entityManager.remove(category);
            entityManager.flush();
            entityManager.createQuery("delete from PhotoBlobEntity blob where blob.originalFilename like :prefix")
                    .setParameter("prefix", seed.prefix + "%")
                    .executeUpdate();
        });
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] jpeg(int width, int height, long seed) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(0.9f);
            writer.write(null, new IIOImage(image, null, null), parameters);
            imageOutput.flush();
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
