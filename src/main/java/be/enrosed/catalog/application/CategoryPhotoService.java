package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CategoryEntity;
import be.enrosed.catalog.adapter.out.persistence.CategoryPhotoEntity;
import be.enrosed.catalog.application.port.out.CategoryRepository;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The photos a category opens with, on the website and in the printed
 * catalogue. Kept apart from the category's copy: a save of names and
 * texts never touches them, and the pictures on enrosed.com can be taken
 * over with one click instead of being uploaded twice.
 */
@ApplicationScoped
public class CategoryPhotoService {

    /** The only hosts a category photo may be fetched from: our own website. */
    private static final Set<String> IMPORT_HOSTS = Set.of("enrosed.com", "www.enrosed.com");

    private final CatalogDaos.Categories dao;
    private final CategoryRepository categories;
    private final PhotoStorage storage;

    @Inject
    Instance<WebsiteRebuildService> websiteRebuild;
    @Inject
    Instance<ProductFamilyWriteGuard> familyWrites;

    public CategoryPhotoService(CatalogDaos.Categories dao, CategoryRepository categories, PhotoStorage storage) {
        this.dao = dao;
        this.categories = categories;
        this.storage = storage;
    }

    @Transactional
    public Category add(long categoryId, String filename, InputStream data) {
        PhotoUploadPolicy.ValidatedPhoto upload = PhotoUploadPolicy.validate(filename, data);
        CategoryEntity entity = entity(categoryId);
        PhotoStorage.Stored stored = storage.store(upload.originalFilename(), upload.contentType(), upload.bytes());
        CategoryPhotoEntity photo = new CategoryPhotoEntity();
        photo.category = entity;
        photo.storageKey = stored.storageKey();
        photo.originalFilename = upload.originalFilename();
        photo.contentType = upload.contentType();
        photo.sizeBytes = stored.sizeBytes();
        photo.widthPx = stored.widthPx();
        photo.heightPx = stored.heightPx();
        photo.position = entity.photos.size();
        entity.photos.add(photo);
        dao.flush();
        queueWebsite();
        return reread(categoryId);
    }

    /**
     * Takes a picture over from our own website, so the collection photo
     * the visitor already knows becomes the category's photo here too.
     */
    @Transactional
    public Category importFromUrl(long categoryId, String url) {
        if (url == null || url.isBlank()) throw new BusinessRuleException("Geef het webadres van de foto");
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException exception) {
            throw new BusinessRuleException("Dat is geen geldig webadres");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !IMPORT_HOSTS.contains(host)) {
            throw new BusinessRuleException("Alleen foto's van enrosed.com kunnen worden overgenomen");
        }
        entity(categoryId);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", "Enrosed ERP")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception exception) {
            throw new BusinessRuleException("De foto kon niet van de website worden gehaald");
        }
        if (response.statusCode() != 200) {
            throw new BusinessRuleException("De website gaf antwoord " + response.statusCode() + " voor die foto");
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        String filename = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        try (InputStream body = response.body()) {
            return add(categoryId, filename.isBlank() ? "website-foto" : filename, body);
        } catch (java.io.IOException exception) {
            throw new BusinessRuleException("De foto kon niet worden ingelezen");
        }
    }

    @Transactional
    public Category remove(long categoryId, long photoId) {
        CategoryEntity entity = entity(categoryId);
        boolean removed = entity.photos.removeIf(photo -> photo.id != null && photo.id == photoId);
        if (!removed) throw new NotFoundException("Foto", photoId);
        renumber(entity);
        dao.flush();
        queueWebsite();
        return reread(categoryId);
    }

    /** The ids in their new order; the first becomes the photo the category opens with. */
    @Transactional
    public Category reorder(long categoryId, List<Long> photoIdsInOrder) {
        CategoryEntity entity = entity(categoryId);
        List<Long> wanted = photoIdsInOrder == null ? List.of() : photoIdsInOrder;
        if (wanted.size() != entity.photos.size() || new HashSet<>(wanted).size() != wanted.size()
                || entity.photos.stream().anyMatch(photo -> photo.id == null || !wanted.contains(photo.id))) {
            throw new BusinessRuleException("De fotovolgorde moet elke foto van de categorie exact één keer bevatten");
        }
        List<CategoryPhotoEntity> ordered = new ArrayList<>();
        for (Long id : wanted) {
            entity.photos.stream().filter(photo -> photo.id.equals(id)).findFirst().ifPresent(ordered::add);
        }
        entity.photos.clear();
        entity.photos.addAll(ordered);
        renumber(entity);
        dao.flush();
        queueWebsite();
        return reread(categoryId);
    }

    public Photo photo(long categoryId, long photoId) {
        return reread(categoryId).photos().stream()
                .filter(photo -> photo.id() != null && photo.id() == photoId)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Foto", photoId));
    }

    public InputStream data(String storageKey) {
        return storage.read(storageKey);
    }

    private CategoryEntity entity(long categoryId) {
        CategoryEntity entity = dao.findById(categoryId);
        if (entity == null) throw new NotFoundException("Categorie", categoryId);
        return entity;
    }

    private Category reread(long categoryId) {
        return categories.findById(categoryId).orElseThrow(() -> new NotFoundException("Categorie", categoryId));
    }

    private static void renumber(CategoryEntity entity) {
        for (int index = 0; index < entity.photos.size(); index++) entity.photos.get(index).position = index;
    }

    private void queueWebsite() {
        if (websiteRebuild == null || familyWrites == null) return;
        if (websiteRebuild.isResolvable() && familyWrites.isResolvable() && familyWrites.get().websiteBuildReady()) {
            websiteRebuild.get().queue();
        }
    }
}
