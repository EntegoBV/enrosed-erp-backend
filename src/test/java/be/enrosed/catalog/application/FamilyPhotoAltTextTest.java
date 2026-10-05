package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ProductFamilyDto;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyTextEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductTextEntity;
import be.enrosed.shared.Language;
import be.enrosed.shared.LanguageFallback;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FamilyPhotoAltTextTest {

    @Test
    void explicitAltInTheRequestedLanguageWins() {
        Fixture fixture = fixture();
        LanguageFallback.Resolved<String> alt = FamilyPhotoAltText.resolve(
                fixture.family, fixture.colourImage, List.of(fixture.red),
                List.of(new ProductFamilyDto.AltTextDto(Language.FR, "Bol de roses rouges")),
                Language.FR);

        assertEquals("Bol de roses rouges", alt.value());
        assertEquals(Language.FR, alt.sourceLanguage());
    }

    @Test
    void generatedAltUsesTheExactFamilyNameAndTheVariantColour() {
        Fixture fixture = fixture();

        LanguageFallback.Resolved<String> colour = FamilyPhotoAltText.resolve(
                fixture.family, fixture.colourImage, List.of(fixture.red), List.of(), Language.NL);
        LanguageFallback.Resolved<String> familyWide = FamilyPhotoAltText.resolve(
                fixture.family, fixture.familyWideImage, List.of(fixture.red), List.of(), Language.NL);

        assertEquals("Bowl rozen met display — Rood", colour.value());
        assertEquals(Language.NL, colour.sourceLanguage());
        assertEquals("Bowl rozen met display", familyWide.value());
        assertEquals(Language.NL, familyWide.sourceLanguage());
    }

    @Test
    void generatedAltBeatsAnExplicitAltInAnotherLanguage() {
        Fixture fixture = fixture();
        LanguageFallback.Resolved<String> alt = FamilyPhotoAltText.resolve(
                fixture.family, fixture.familyWideImage, List.of(fixture.red),
                List.of(new ProductFamilyDto.AltTextDto(Language.EN, "Rose bowl")), Language.NL);

        assertEquals("Bowl rozen met display", alt.value());
        assertEquals(Language.NL, alt.sourceLanguage());
    }

    @Test
    void aBorrowedColourNeverJoinsAnExactName() {
        Fixture fixture = fixture();
        fixture.red.texts.removeIf(text -> text.language == Language.NL);

        LanguageFallback.Resolved<String> alt = FamilyPhotoAltText.resolve(
                fixture.family, fixture.colourImage, List.of(fixture.red), List.of(), Language.NL);

        assertEquals("Bowl rozen met display", alt.value());
        assertEquals(Language.NL, alt.sourceLanguage());
    }

    @Test
    void withoutAnExactFamilyNameTheOldFallbackChainStaysHonestAboutItsSource() {
        Fixture fixture = fixture();

        LanguageFallback.Resolved<String> explicitFallback = FamilyPhotoAltText.resolve(
                fixture.family, fixture.familyWideImage, List.of(fixture.red),
                List.of(new ProductFamilyDto.AltTextDto(Language.EN, "Rose bowl")), Language.DE);
        LanguageFallback.Resolved<String> generatedFallback = FamilyPhotoAltText.resolve(
                fixture.family, fixture.colourImage, List.of(fixture.red), List.of(), Language.DE);

        assertEquals("Rose bowl", explicitFallback.value());
        assertEquals(Language.EN, explicitFallback.sourceLanguage());
        assertEquals("Bowl roses with display — Red", generatedFallback.value());
        assertEquals(Language.EN, generatedFallback.sourceLanguage());
    }

    @Test
    void nothingToSayStaysEmptyWithoutASource() {
        ProductFamilyEntity family = new ProductFamilyEntity();
        LanguageFallback.Resolved<String> alt = FamilyPhotoAltText.resolve(
                family, new ProductFamilyPhotoEntity(), List.of(), null, Language.PL);

        assertEquals("", alt.value());
        assertNull(alt.sourceLanguage());
    }

    private record Fixture(ProductFamilyEntity family, ProductEntity red,
                           ProductFamilyPhotoEntity colourImage,
                           ProductFamilyPhotoEntity familyWideImage) {}

    private static Fixture fixture() {
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.id = 13L;
        family.name = "Bowl rozen met display";
        familyText(family, Language.NL, "Bowl rozen met display");
        familyText(family, Language.EN, "Bowl roses with display");
        familyText(family, Language.FR, "Bol de roses avec présentoir");

        ProductEntity red = new ProductEntity();
        red.id = 53L;
        red.familyId = family.id;
        red.colour = "Rood";
        productColour(red, Language.NL, "Rood");
        productColour(red, Language.EN, "Red");

        ProductFamilyPhotoEntity colourImage = new ProductFamilyPhotoEntity();
        colourImage.family = family;
        colourImage.variantProduct = red;
        ProductFamilyPhotoEntity familyWideImage = new ProductFamilyPhotoEntity();
        familyWideImage.family = family;
        return new Fixture(family, red, colourImage, familyWideImage);
    }

    private static void familyText(ProductFamilyEntity family, Language language, String name) {
        ProductFamilyTextEntity text = new ProductFamilyTextEntity();
        text.family = family;
        text.language = language;
        text.name = name;
        family.texts.add(text);
    }

    private static void productColour(ProductEntity product, Language language, String colour) {
        ProductTextEntity text = new ProductTextEntity();
        text.product = product;
        text.language = language;
        text.colour = colour;
        product.texts.add(text);
    }
}
