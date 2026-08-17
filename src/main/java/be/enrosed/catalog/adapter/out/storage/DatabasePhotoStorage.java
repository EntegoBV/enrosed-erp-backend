package be.enrosed.catalog.adapter.out.storage;

import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.shared.NotFoundException;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

/**
 * Bewaart fotobestanden in de database.
 *
 * De bytes gaan er ongewijzigd in en ongewijzigd weer uit: geen herschaling,
 * geen hercompressie. Een foto die de leverancier op 4000 px aanlevert komt er
 * ook zo weer uit, bruikbaar voor drukwerk.
 *
 * Alles in de database houden betekent één back-up en één plek om te
 * beveiligen, wat het beheer eenvoudig houdt. De keerzijde is dat de database
 * hard groeit en dat blobs niet door een CDN gecachet worden. Loopt dat op,
 * dan schrijf je een S3-variant naast deze klasse: de poort {@link PhotoStorage}
 * verandert dan niet, alleen deze implementatie.
 */
@ApplicationScoped
public class DatabasePhotoStorage implements PhotoStorage {

    @ApplicationScoped
    public static class Blobs implements PanacheRepositoryBase<PhotoBlobEntity, String> {}

    private final Blobs blobs;

    public DatabasePhotoStorage(Blobs blobs) {
        this.blobs = blobs;
    }

    @Override
    @Transactional
    public Stored store(String originalFilename, String contentType, InputStream data) {
        byte[] bytes;
        try {
            bytes = data.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Kan de foto niet inlezen", e);
        }

        PhotoBlobEntity entity = new PhotoBlobEntity();
        entity.storageKey = UUID.randomUUID() + extensionOf(originalFilename);
        entity.data = bytes;
        entity.sizeBytes = bytes.length;
        entity.contentType = contentType;
        entity.originalFilename = originalFilename;
        blobs.persist(entity);

        int[] size = readDimensions(bytes);
        return new Stored(entity.storageKey, bytes.length,
                size[0] == 0 ? null : size[0], size[1] == 0 ? null : size[1]);
    }

    @Override
    @Transactional
    public InputStream read(String storageKey) {
        PhotoBlobEntity entity = blobs.findById(storageKey);
        if (entity == null) throw new NotFoundException("Foto", storageKey);
        return new ByteArrayInputStream(entity.data);
    }

    @Override
    @Transactional
    public void delete(String storageKey) {
        blobs.deleteById(storageKey);
    }

    @Override
    @Transactional
    public boolean exists(String storageKey) {
        return blobs.findById(storageKey) != null;
    }

    /** Leest breedte en hoogte uit de header, zonder de foto te decoderen. */
    private int[] readDimensions(byte[] bytes) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (stream == null) return new int[] { 0, 0 };
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return new int[] { 0, 0 };
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                return new int[] { reader.getWidth(0), reader.getHeight(0) };
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            return new int[] { 0, 0 };
        }
    }

    private static String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        String extension = filename.substring(dot).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,5}") ? extension : "";
    }
}
