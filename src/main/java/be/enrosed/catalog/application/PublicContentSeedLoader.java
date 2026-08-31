package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.*;
import be.enrosed.catalog.domain.ContentScope;
import be.enrosed.shared.Csv;
import be.enrosed.shared.Language;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * One-time-safe bootstrap for dashboard-owned public copy.
 *
 * Existing groups and translation rows are never overwritten. Clearing a dashboard value
 * therefore remains a deliberate, persistent edit; newly introduced seed keys are still added.
 */
@ApplicationScoped
public class PublicContentSeedLoader {
    private static final Logger LOG = Logger.getLogger(PublicContentSeedLoader.class);
    private static final String CATALOG_RESOURCE = "/i18n/public-content.csv";
    private static final String WEBSITE_RESOURCE = "/i18n/website-content.csv";
    private static final Set<String> PROTECTED_TERMS = Set.of(
            "Royal FloraHolland", "TICA", "SKU", "EAN", "B2B", "EXW", "DDP");
    private static final Set<String> RETIRED_WEBSITE_KEYS = Set.of(
            "site.nav.products", "site.product.choosevariant", "site.product.color",
            "site.product.size", "site.product.productdimensions",
            "site.product.cartondimensions", "site.product.pack", "site.product.ean",
            "site.product.priceonrequest", "site.product.imageunavailable",
            "site.catalog.emptytitle", "site.catalog.emptybody",
            "site.common.viewproduct", "site.common.back");
    /** Exact former seed values; only these migrate, never dashboard-authored privacy copy. */
    private static final Map<Language, String> LEGACY_PRIVACY_NO_QUOTE_FORM = Map.ofEntries(
            Map.entry(Language.NL, "Deze website maakt gebruik van e-mail- en telefoonlinks in plaats van een offerteformulier op de website. Informatie die u in een e-mail opneemt, wordt verwerkt via de e-mailservice die wordt gebruikt door Enrosed."),
            Map.entry(Language.FR, "Ce site Web utilise des liens électroniques et téléphoniques plutôt qu'un formulaire de devis sur site. Les informations que vous incluez dans un e-mail sont traitées via le service de messagerie utilisé par Enrosed."),
            Map.entry(Language.EN, "This website uses email and telephone links rather than an onsite quotation form. Information you include in an email is processed through the email service used by Enrosed."),
            Map.entry(Language.DE, "Diese Website verwendet E-Mail- und Telefonlinks anstelle eines Angebotsformulars auf der Website. Die von Ihnen in eine E-Mail eingegebenen Informationen werden über den von Enrosed verwendeten E-Mail-Dienst verarbeitet."),
            Map.entry(Language.ES, "Este sitio web utiliza enlaces telefónicos y de correo electrónico en lugar de un formulario de presupuesto in situ. La información que incluye en un correo electrónico se procesa a través del servicio de correo electrónico utilizado por Enrosed."),
            Map.entry(Language.PL, "Ta witryna korzysta z łączy e-mailowych i telefonicznych, a nie z formularza wyceny dostępnego na stronie. Informacje zawarte w wiadomości e-mail są przetwarzane za pośrednictwem usługi poczty elektronicznej, z której korzysta Enrosed."),
            Map.entry(Language.PT, "Este website utiliza ligações de e-mail e telefone em vez de um formulário de proposta no próprio website. As informações incluídas num e-mail são tratadas através do serviço de e-mail utilizado pela Enrosed."),
            Map.entry(Language.TR, "Bu web sitesi, yerinde fiyat teklifi formu yerine e-posta ve telefon bağlantılarını kullanır. Bir e-postaya eklediğiniz bilgiler, Enrosed tarafından kullanılan e-posta hizmeti aracılığıyla işlenir."));
    /** Exact quote-only defaults superseded when the general contact form was introduced. */
    private static final Map<Language, String> LEGACY_PRIVACY_QUOTE_ONLY_FORM = Map.ofEntries(
            Map.entry(Language.NL, "Deze website bevat een offerteformulier voor groothandels. Wanneer u het verstuurt, verwerkt Enrosed uw bedrijfs- en contactgegevens, geselecteerde producten en aantallen, leveringsbestemming, BTW-nummer en eventuele opmerkingen om uw offerte op te stellen en op te volgen."),
            Map.entry(Language.FR, "Ce site propose un formulaire de demande de devis grossiste. Lorsque vous l’envoyez, Enrosed traite les coordonnées de votre entreprise et de votre contact, les produits et quantités sélectionnés, la destination de livraison, le numéro de TVA et vos éventuelles remarques afin de préparer et de suivre votre devis."),
            Map.entry(Language.EN, "This website provides general contact and wholesale quotation forms. A contact form submission includes the details and message you enter. A quotation request also includes your selected products and quantities, delivery destination, VAT number and any notes needed to prepare and follow up your quotation."),
            Map.entry(Language.DE, "Diese Website stellt ein Formular für Großhandelsangebote bereit. Wenn Sie es absenden, verarbeitet Enrosed Ihre Unternehmens- und Kontaktdaten, die ausgewählten Produkte und Mengen, den Lieferort, die Umsatzsteuer-Identifikationsnummer und etwaige Anmerkungen, um Ihr Angebot zu erstellen und nachzuverfolgen."),
            Map.entry(Language.ES, "Este sitio web ofrece un formulario de solicitud de presupuesto mayorista. Al enviarlo, Enrosed trata los datos de su empresa y de contacto, los productos y las cantidades seleccionados, el destino del envío, el número de IVA y cualquier nota para preparar y gestionar su presupuesto."),
            Map.entry(Language.PL, "Ta strona zawiera formularz zapytania o ofertę hurtową. Po jego wysłaniu Enrosed przetwarza dane firmy i dane kontaktowe, wybrane produkty i ilości, miejsce dostawy, numer VAT oraz ewentualne uwagi w celu przygotowania i obsługi oferty."),
            Map.entry(Language.PT, "Este website disponibiliza um formulário de pedido de orçamento para grossistas. Ao enviá-lo, a Enrosed trata os dados da empresa e de contacto, os produtos e quantidades selecionados, o destino da entrega, o número de IVA e eventuais observações para preparar e acompanhar o orçamento."),
            Map.entry(Language.TR, "Bu web sitesinde toptan satış teklif formu sunulur. Formu gönderdiğinizde Enrosed; teklifinizi hazırlamak ve takip etmek için şirket ve iletişim bilgilerinizi, seçilen ürünleri ve miktarları, teslimat yerini, KDV numarasını ve notlarınızı işler."));
    private static final String LEGACY_PRIVACY_EN_ONSITE_QUOTE_FORM =
            "This website provides an onsite wholesale quotation form. When you submit it, Enrosed processes your company and contact details, selected products and quantities, delivery destination, VAT number and any notes to prepare and follow up your quotation.";
    private static final Map<Language, String> LEGACY_PRIVACY_QUOTE_ONLY_PURPOSE = Map.ofEntries(
            Map.entry(Language.NL, "het opstellen en opvolgen van een aangevraagde offerte;"),
            Map.entry(Language.FR, "préparer et suivre un devis demandé ;"),
            Map.entry(Language.EN, "answering general contact messages and preparing or following up a requested quotation;"),
            Map.entry(Language.DE, "Vorbereiten und Nachbereiten eines angeforderten Angebots;"),
            Map.entry(Language.ES, "preparar y hacer el seguimiento de un presupuesto solicitado;"),
            Map.entry(Language.PL, "przygotowanie i realizacja żądanej wyceny;"),
            Map.entry(Language.PT, "elaborar e acompanhar o orçamento pedido;"),
            Map.entry(Language.TR, "talep edilen bir teklifin hazırlanması ve takibi;"));
    private static final String LEGACY_PRIVACY_EN_QUOTE_ONLY_PURPOSE =
            "preparing and following up a requested quotation;";
    /** Exact defaults before the privacy text named the current anti-spam implementation. */
    private static final Map<Language, String> LEGACY_PRIVACY_GENERIC_FORM_SECURITY = Map.ofEntries(
            Map.entry(Language.NL, "Deze groothandelswebsite gebruikt momenteel geen analyse- of advertentiecookies. Alleen de technische functionaliteit die nodig is om de website weer te geven en de gekozen links te openen, wordt gebruikt. De knop Cookievoorkeuren in de footer toont de huidige status."),
            Map.entry(Language.FR, "Ce site de vente en gros n’utilise pas de cookies d’analyse ou publicitaires. Seules les fonctions techniques nécessaires sont utilisées. Lorsque la protection des formulaires est active, le service de sécurité peut traiter des données techniques et utiliser un stockage strictement nécessaire pour détecter les envois automatisés. Le contrôle des préférences dans le pied de page indique l’état actuel."),
            Map.entry(Language.EN, "This wholesale website currently uses no analytics or advertising cookies. Technical functionality is used to deliver the website and protect its forms against spam. This includes short-lived form tokens and, when enabled, an explicit Cloudflare Turnstile security check. The Cookie preferences control in the footer shows the current status."),
            Map.entry(Language.DE, "Diese Großhandelswebsite verwendet keine Analyse- oder Werbecookies. Es werden nur notwendige technische Funktionen genutzt. Bei aktiver Formularsicherung kann der Sicherheitsanbieter technische Daten verarbeiten und unbedingt erforderlichen Speicher verwenden, um automatisierte Übermittlungen zu erkennen. Die Cookie-Einstellungen in der Fußzeile zeigen den aktuellen Status."),
            Map.entry(Language.ES, "Este sitio mayorista no utiliza cookies analíticas ni publicitarias. Solo se usa funcionalidad técnica necesaria. Cuando la protección de formularios está activa, el proveedor de seguridad puede tratar datos técnicos y usar almacenamiento estrictamente necesario para detectar envíos automatizados. Las preferencias de cookies del pie muestran el estado actual."),
            Map.entry(Language.PL, "Ta strona hurtowa nie używa analitycznych ani reklamowych plików cookie. Stosowane są wyłącznie niezbędne funkcje techniczne. Gdy ochrona formularzy jest aktywna, dostawca zabezpieczeń może przetwarzać dane techniczne i używać niezbędnej pamięci do wykrywania automatycznych zgłoszeń. Ustawienia cookie w stopce pokazują aktualny stan."),
            Map.entry(Language.PT, "Este website grossista não utiliza cookies analíticos ou publicitários. Apenas são usadas funções técnicas necessárias. Quando a proteção dos formulários está ativa, o fornecedor de segurança pode tratar dados técnicos e usar armazenamento estritamente necessário para detetar envios automáticos. As preferências de cookies no rodapé mostram o estado atual."),
            Map.entry(Language.TR, "Bu toptan satış sitesi analiz veya reklam çerezi kullanmaz. Yalnızca gerekli teknik işlevler kullanılır. Form koruması etkin olduğunda güvenlik sağlayıcısı teknik verileri işleyebilir ve otomatik gönderimleri saptamak için kesinlikle gerekli depolamayı kullanabilir. Alt bilgideki çerez tercihleri güncel durumu gösterir."));
    /** Exact link-only defaults still present in production before form protection was documented. */
    private static final Map<Language, String> LEGACY_PRIVACY_LINK_ONLY_COOKIES = Map.ofEntries(
            Map.entry(Language.FR, "Ce site Web de vente en gros n'utilise actuellement aucun cookie d'analyse ou publicitaire. Seules les fonctionnalités techniques nécessaires à la fourniture du site Web et des liens que vous choisissez sont utilisées. Le contrôle des préférences des cookies dans le pied de page indique l'état actuel."),
            Map.entry(Language.EN, "This wholesale website currently uses no analytics or advertising cookies. Only technical functionality required to deliver the website and the links you choose is used. The Cookie preferences control in the footer shows the current status."),
            Map.entry(Language.DE, "Diese Großhandelswebsite verwendet derzeit keine Analyse- oder Werbecookies. Es werden ausschließlich technische Funktionen genutzt, die zur Bereitstellung der Website und der von Ihnen ausgewählten Links erforderlich sind. Das Steuerelement „Cookie-Einstellungen“ in der Fußzeile zeigt den aktuellen Status an."),
            Map.entry(Language.ES, "Este sitio web mayorista actualmente no utiliza cookies analíticas ni publicitarias. Solo se utiliza la funcionalidad técnica necesaria para ofrecer el sitio web y los enlaces que elija. El control de preferencias de cookies en el pie de página muestra el estado actual."),
            Map.entry(Language.PL, "Ta witryna hurtowni nie wykorzystuje obecnie żadnych plików cookie do celów analitycznych ani reklamowych. Wykorzystywane są wyłącznie funkcje techniczne wymagane do dostarczenia witryny internetowej i wybranych linków. Kontrolka preferencji plików cookie w stopce pokazuje aktualny stan."),
            Map.entry(Language.PT, "Este website de venda por grosso não utiliza atualmente cookies analíticos ou publicitários. Apenas são utilizadas as funcionalidades técnicas necessárias para fornecer o site e os links que escolher. O controlo de preferências de cookies no rodapé mostra o estado atual."),
            Map.entry(Language.TR, "Bu toptan satış web sitesi şu anda hiçbir analiz veya reklam çerezi kullanmamaktadır. Yalnızca web sitesini ve seçtiğiniz bağlantıları sunmak için gereken teknik işlevler kullanılır. Alt bilgideki Çerez tercihleri kontrolü mevcut durumu gösterir."));
    private static final Map<Language, String> LEGACY_LEGAL_REVIEW_DATE = Map.ofEntries(
            Map.entry(Language.NL, "20 augustus 2026"),
            Map.entry(Language.FR, "20 août 2026"),
            Map.entry(Language.EN, "20 August 2026"),
            Map.entry(Language.DE, "20. August 2026"),
            Map.entry(Language.ES, "20 de agosto de 2026"),
            Map.entry(Language.PL, "20 sierpnia 2026 r"),
            Map.entry(Language.PT, "20 de agosto de 2026"),
            Map.entry(Language.TR, "20 Ağustos 2026"));
    private static final Map<Language, String> LEGACY_TRADE_SECTION_EIGHT_TITLE = Map.ofEntries(
            Map.entry(Language.NL, "8. Toepasselijk recht en contact"),
            Map.entry(Language.FR, "8. Loi applicable et contact"),
            Map.entry(Language.EN, "8. Applicable law and contact"),
            Map.entry(Language.DE, "8. Anwendbares Recht und Kontakt"),
            Map.entry(Language.ES, "8. Ley aplicable y contacto"),
            Map.entry(Language.PL, "8. Obowiązujące prawo i kontakt"),
            Map.entry(Language.PT, "8. Lei aplicável e contacto"),
            Map.entry(Language.TR, "8. Geçerli yasa ve iletişim"));
    private static final Map<Language, String> LEGACY_TRADE_LIMBURG_CLAUSE = Map.ofEntries(
            Map.entry(Language.NL, "Op de zakelijke relatie is het Belgisch recht van toepassing. Tenzij dwingend recht anders bepaalt, vallen geschillen onder de jurisdictie van de bevoegde rechtbanken in Limburg, België."),
            Map.entry(Language.FR, "Le droit belge s'applique à la relation commerciale. Sauf disposition contraire de la loi impérative, les litiges relèvent de la compétence des tribunaux compétents du Limbourg, Belgique."),
            Map.entry(Language.EN, "Belgian law applies to the business relationship. Unless mandatory law provides otherwise, disputes fall within the jurisdiction of the competent courts in Limburg, Belgium."),
            Map.entry(Language.DE, "Für die Geschäftsbeziehung gilt belgisches Recht. Sofern nicht zwingendes Recht etwas anderes vorsieht, fallen Streitigkeiten in die Zuständigkeit der zuständigen Gerichte in Limburg, Belgien."),
            Map.entry(Language.ES, "La ley belga se aplica a la relación comercial. A menos que la ley imperativa disponga lo contrario, las disputas son competencia de los tribunales competentes de Limburgo, Bélgica."),
            Map.entry(Language.PL, "W stosunkach biznesowych obowiązuje prawo belgijskie. O ile obowiązujące prawo nie stanowi inaczej, spory podlegają jurysdykcji właściwych sądów w Limburgii w Belgii."),
            Map.entry(Language.PT, "A lei belga aplica-se à relação comercial. Salvo disposição em contrário da lei imperativa, os litígios são da competência dos tribunais competentes de Limburgo, na Bélgica."),
            Map.entry(Language.TR, "İş ilişkisine Belçika hukuku uygulanır. Emredici hukuk kuralları aksini öngörmedikçe, uyuşmazlıklar Belçika’nın Limburg bölgesindeki yetkili mahkemelerin yargı yetkisine tabidir."));

    private final CanonicalCatalogDaos.ContentTranslations content;
    private final CanonicalCatalogDaos.Families families;
    private final CatalogDaos.Products products;
    private final CatalogDaos.Categories categories;
    private final CatalogFoamPhotoBackfillService foamPhotoBackfill;
    private final CatalogContentBackfillService catalogBackfill;
    private final WebsiteCatalogRevisionService websiteRevision;
    private final WebsiteRebuildService websiteRebuild;
    private final EntityManager entityManager;
    private final CatalogMutationLock mutationLock;

    public PublicContentSeedLoader(
            CanonicalCatalogDaos.ContentTranslations content,
            CanonicalCatalogDaos.Families families,
            CatalogDaos.Products products,
            CatalogDaos.Categories categories,
            CatalogFoamPhotoBackfillService foamPhotoBackfill,
            CatalogContentBackfillService catalogBackfill,
            WebsiteCatalogRevisionService websiteRevision,
            WebsiteRebuildService websiteRebuild,
            EntityManager entityManager,
            CatalogMutationLock mutationLock) {
        this.content = content;
        this.families = families;
        this.products = products;
        this.categories = categories;
        this.foamPhotoBackfill = foamPhotoBackfill;
        this.catalogBackfill = catalogBackfill;
        this.websiteRevision = websiteRevision;
        this.websiteRebuild = websiteRebuild;
        this.entityManager = entityManager;
        this.mutationLock = mutationLock;
    }

    /* Not transactional itself: the seeding runs in its own transaction, so
       a failure rolls that back and lands here instead of poisoning the
       transaction this method would otherwise own. */
    void onStart(@Observes StartupEvent ignored) {
        SeedResult result;
        try {
            result = ensureSeededAndQueueWebsiteChange();
        } catch (RuntimeException failure) {
            /* Website copy is not worth a dead ERP: say what was skipped and
               start anyway; the next save of that family runs the backfill
               again. A warning, not an error: nothing is broken for a user,
               a text was left as it was. ERROR is kept for what actually
               breaks - a dead database, a failed payment, a lost file. */
            LOG.warnf("Websiteteksten niet bijgewerkt bij het opstarten (%s); de app start zonder, "
                    + "de teksten worden bij de volgende opslag van dat product opnieuw geprobeerd",
                    failure.getMessage());
            return;
        }
        LOG.infof("Publieke copy gecontroleerd: %d key(s)/taalwaarden toegevoegd",
                result.seededValues());
        if (result.retiredKeys() > 0) LOG.infof("%d verouderde website-copy-key(s) verwijderd",
                result.retiredKeys());
        LOG.infof("Catalogusvertalingen %s: %d categorieën, %d families, %d varianten, %d beelden; "
                        + "%d rijen toegevoegd, %d bekende importwaarden gecorrigeerd",
                result.backfill().version(), result.backfill().matchedCategories(),
                result.backfill().matchedFamilies(), result.backfill().matchedVariants(),
                result.backfill().matchedImages(), result.backfill().insertedRows(),
                result.backfill().correctedKnownFields());
    }

    /** Compares the complete public digest because individual seed counters are not exhaustive. */
    @Transactional
    SeedResult ensureSeededAndQueueWebsiteChange() {
        mutationLock.acquire();
        String before = websiteRevision.currentRevision();
        SeedResult result = ensureSeeded();
        entityManager.flush();
        String after = websiteRevision.currentRevision();
        if (!Objects.equals(before, after)) websiteRebuild.queue();
        return result;
    }

    /** Reusable by a same-transaction product replacement after it cleared seeded rows. */
    @Transactional
    public SeedResult ensureSeeded() {
        mutationLock.acquire();
        int retired = deleteRetiredWebsiteKeys();
        int seeded = seedPublicCopy();
        foamPhotoBackfill.apply();
        return new SeedResult(retired, seeded, catalogBackfill.apply());
    }

    public record SeedResult(
            int retiredKeys, int seededValues, CatalogContentBackfillService.Result backfill) {}

    private int seedPublicCopy() {
        int inserted = 0;
        for (Seed seed : seeds()) {
            ContentTranslationEntity existing = content.find(
                    "scope = ?1 and key = ?2", seed.scope(), seed.key())
                    .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
            if (existing != null) {
                entityManager.refresh(existing, LockModeType.PESSIMISTIC_WRITE);
                boolean changed = false;
                if (!existing.system) {
                    existing.system = true;
                    changed = true;
                }
                if (!Objects.equals(existing.label, seed.label())) {
                    existing.label = seed.label();
                    changed = true;
                }
                if (existing.required != seed.required()) {
                    existing.required = seed.required();
                    changed = true;
                }
                Map<Language, ContentTranslationTextEntity> present = new EnumMap<>(Language.class);
                existing.texts.forEach(text -> present.put(text.language, text));
                for (Map.Entry<Language, String> value : seed.values().entrySet()) {
                    ContentTranslationTextEntity presentText = present.get(value.getKey());
                    if (presentText != null) {
                        if (!Objects.equals(presentText.value, value.getValue())
                                && isKnownStaleSeedValue(seed.scope(), seed.key(), value.getKey(),
                                presentText.value)) {
                            presentText.value = value.getValue();
                            changed = true;
                        }
                        continue;
                    }
                    ContentTranslationTextEntity text = new ContentTranslationTextEntity();
                    text.owner = existing;
                    text.language = value.getKey();
                    text.value = value.getValue();
                    existing.texts.add(text);
                    changed = true;
                    inserted++;
                }
                if (changed) existing.updatedAt = Instant.now();
                ContentTranslationService.validatePlaceholderParity(existing);
                continue;
            }
            ContentTranslationEntity entity = new ContentTranslationEntity();
            entity.scope = seed.scope();
            entity.key = seed.key();
            entity.label = seed.label();
            entity.required = seed.required();
            entity.system = true;
            entity.updatedAt = Instant.now();
            for (Map.Entry<Language, String> value : seed.values().entrySet()) {
                if (value.getValue() == null || value.getValue().isBlank()) continue;
                ContentTranslationTextEntity text = new ContentTranslationTextEntity();
                text.owner = entity;
                text.language = value.getKey();
                text.value = value.getValue();
                entity.texts.add(text);
            }
            ContentTranslationService.validatePlaceholderParity(entity);
            content.persist(entity);
            inserted += entity.texts.size();
        }
        return inserted;
    }

    private int deleteRetiredWebsiteKeys() {
        int removed = 0;
        for (String key : RETIRED_WEBSITE_KEYS) {
            ContentTranslationEntity existing = content.find(
                    "scope = ?1 and key = ?2", ContentScope.WEBSITE, key)
                    .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
            if (existing == null) continue;
            entityManager.refresh(existing, LockModeType.PESSIMISTIC_WRITE);
            content.delete(existing);
            removed++;
        }
        return removed;
    }

    private static List<Seed> seeds() {
        List<Seed> result = new ArrayList<>();
        result.addAll(readSeeds(CATALOG_RESOURCE, true));
        result.addAll(readSeeds(WEBSITE_RESOURCE, false));
        Set<String> identities = new HashSet<>();
        for (Seed seed : result) {
            String identity = seed.scope() + ":" + seed.key();
            if (!identities.add(identity)) {
                throw new IllegalStateException("Dubbele public copy key " + identity);
            }
            validateSeed(seed);
        }
        return List.copyOf(result);
    }

    private static List<Seed> readSeeds(String resource, boolean hasScope) {
        try (InputStream input = PublicContentSeedLoader.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Public copy seed ontbreekt: " + resource);
            List<List<String>> rows = Csv.parseRows(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            if (rows.isEmpty()) throw new IllegalStateException("Public copy seed is leeg: " + resource);
            List<String> header = rows.getFirst();
            int fixedColumns = hasScope ? 4 : 3;
            if (header.size() != fixedColumns + Language.values().length) {
                throw new IllegalStateException(resource + " heeft een ongeldige header");
            }
            Language[] columns = new Language[header.size()];
            EnumSet<Language> seen = EnumSet.noneOf(Language.class);
            for (int index = fixedColumns; index < header.size(); index++) {
                columns[index] = Language.valueOf(header.get(index).trim().toUpperCase(Locale.ROOT));
                if (!seen.add(columns[index])) {
                    throw new IllegalStateException("Dubbele taal in public copy seed");
                }
            }
            if (seen.size() != Language.values().length) {
                throw new IllegalStateException("Public copy seed mist een ondersteunde taal");
            }
            List<Seed> result = new ArrayList<>();
            for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
                List<String> cells = rows.get(rowIndex);
                int lineNumber = rowIndex + 1;
                if (cells.size() != header.size()) {
                    throw new IllegalStateException(resource + " record " + lineNumber
                            + " heeft " + cells.size() + " in plaats van " + header.size()
                            + " kolommen");
                }
                ContentScope scope = hasScope
                        ? ContentScope.valueOf(cells.get(0).trim().toUpperCase(Locale.ROOT))
                        : ContentScope.WEBSITE;
                /* The old compact WEBSITE seed used a retired `site.*` contract. Keeping it in
                   source history is harmless, but it must never re-enter the dashboard store. */
                if (hasScope && scope != ContentScope.CATALOG) continue;
                int keyIndex = hasScope ? 1 : 0;
                EnumMap<Language, String> values = new EnumMap<>(Language.class);
                for (int index = fixedColumns; index < cells.size(); index++) {
                    values.put(columns[index], cells.get(index));
                }
                result.add(new Seed(scope, cells.get(keyIndex).trim(),
                        cells.get(keyIndex + 1).trim(),
                        Boolean.parseBoolean(cells.get(keyIndex + 2).trim()), values));
            }
            return List.copyOf(result);
        } catch (java.io.IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Public copy seed kon niet gelezen worden: " + resource,
                    exception);
        }
    }

    private static void validateSeed(Seed seed) {
        String english = seed.values().get(Language.EN);
        if (seed.values().size() != Language.values().length
                || seed.values().values().stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalStateException("Public copy key " + seed.key()
                    + " moet alle acht niet-lege talen bevatten");
        }
        for (Map.Entry<Language, String> localized : seed.values().entrySet()) {
            String value = localized.getValue();
            if (containsEnrosed(english) && !containsEnrosed(value)) {
                throw new IllegalStateException("Merknaam Enrosed ontbreekt in " + seed.key()
                        + " taal " + localized.getKey().code());
            }
            for (String term : PROTECTED_TERMS) {
                if (english.contains(term) && !value.contains(term)) {
                    throw new IllegalStateException("Beschermde term " + term + " ontbreekt in "
                            + seed.key() + " taal " + localized.getKey().code());
                }
            }
            if (java.util.regex.Pattern.compile("[.!?]\\p{Lu}\\p{Ll}{2}")
                    .matcher(value.replace("B.V.", "BV")).find()) {
                throw new IllegalStateException("Waarschijnlijke ontbrekende zinspatie in "
                        + seed.key() + " taal " + localized.getKey().code());
            }
        }
    }

    private static boolean containsEnrosed(String value) {
        return value != null && java.util.regex.Pattern.compile("(?i)\\bENROSED\\b")
                .matcher(value).find();
    }

    /** Corrects only exact values shipped by an older system seed; dashboard edits survive. */
    static boolean isKnownStaleSeedValue(
            ContentScope scope, String key, Language language, String current) {
        if (scope == ContentScope.WEBSITE) {
            if ("home.counter.item2.title".equals(key)) {
                String previousSeed = switch (language) {
                    case NL -> "De kom XL";
                    case FR -> "Le Bol XL";
                    case DE -> "Die Schüssel XL";
                    default -> null;
                };
                return Objects.equals(previousSeed, current);
            }
            if ("legal.privacy.data.p2".equals(key)) {
                return Objects.equals(LEGACY_PRIVACY_NO_QUOTE_FORM.get(language), current)
                        || Objects.equals(LEGACY_PRIVACY_QUOTE_ONLY_FORM.get(language), current)
                        || (language == Language.EN
                            && LEGACY_PRIVACY_EN_ONSITE_QUOTE_FORM.equals(current));
            }
            if ("legal.privacy.purposes.item1".equals(key)) {
                return Objects.equals(LEGACY_PRIVACY_QUOTE_ONLY_PURPOSE.get(language), current)
                        || (language == Language.EN
                            && LEGACY_PRIVACY_EN_QUOTE_ONLY_PURPOSE.equals(current));
            }
            if ("legal.privacy.cookies.p1".equals(key)) {
                return Objects.equals(LEGACY_PRIVACY_GENERIC_FORM_SECURITY.get(language), current)
                        || Objects.equals(LEGACY_PRIVACY_LINK_ONLY_COOKIES.get(language), current);
            }
            if ("legal.shipping.updated".equals(key) || "legal.trade.updated".equals(key)) {
                return Objects.equals(LEGACY_LEGAL_REVIEW_DATE.get(language), current);
            }
            if ("legal.trade.lawContact.title".equals(key)) {
                return Objects.equals(LEGACY_TRADE_SECTION_EIGHT_TITLE.get(language), current);
            }
            if ("legal.trade.lawContact.p1".equals(key)) {
                return Objects.equals(LEGACY_TRADE_LIMBURG_CLAUSE.get(language), current);
            }
        }
        if (scope == ContentScope.WEBSITE && "home.counter.item3.title".equals(key)) {
            return "12 Steel Roses".equals(current);
        }
        if (scope == ContentScope.CATALOG && "catalog.common.family.plural".equals(key)
                && language == Language.TR) {
            return "AİLE".equals(current);
        }
        return scope == ContentScope.CATALOG && "catalog.brand.wholesale".equals(key)
                && language == Language.PT && "ATACADO".equals(current);
    }

    private record Seed(ContentScope scope, String key, String label, boolean required,
                        Map<Language, String> values) {}
}
