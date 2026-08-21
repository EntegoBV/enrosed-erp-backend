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
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

/**
 * Stores photo files in the database.
 *
 * The bytes go in unchanged and come out unchanged: no rescaling, no
 * recompression. A photo the supplier delivers at 4000 px comes back out
 * that way, usable for print.
 *
 * Keeping everything in the database means one backup and one place to
 * secure, which keeps operations simple. The flip side is that the database
 * grows fast and blobs are not cached by a CDN. When that bites, write an
 * S3 variant next to this class: the {@link PhotoStorage} port stays the
 * same, only this implementation changes.
 */
@ApplicationScoped
public class DatabasePhotoStorage implements PhotoStorage {
    private static final int MAX_WEBP_DIMENSION = 16_777_216;

    @ApplicationScoped
    public static class Blobs implements PanacheRepositoryBase<PhotoBlobEntity, String> {}

    private final Blobs blobs;

    public DatabasePhotoStorage(Blobs blobs) {
        this.blobs = blobs;
    }

    @Override
    @Transactional
    public Stored store(String originalFilename, String contentType, byte[] bytes) {
        return storeNew(UUID.randomUUID() + extensionOf(originalFilename),
                originalFilename, contentType, bytes);
    }

    @Override
    @Transactional
    public Stored storeKnown(String storageKey, String originalFilename,
                             String contentType, byte[] bytes) {
        if (storageKey == null || !storageKey.matches("sha256-[a-f0-9]{64}\\.[a-z0-9]{1,5}")) {
            throw new IllegalArgumentException("Ongeldige deterministische fotosleutel");
        }
        if (blobs.findById(storageKey) != null) {
            int[] size = readDimensions(bytes);
            return new Stored(storageKey, bytes.length,
                    size[0] == 0 ? null : size[0], size[1] == 0 ? null : size[1]);
        }
        return storeNew(storageKey, originalFilename, contentType, bytes);
    }

    private Stored storeNew(String storageKey, String originalFilename,
                            String contentType, byte[] bytes) {
        PhotoBlobEntity entity = new PhotoBlobEntity();
        entity.storageKey = storageKey;
        entity.data = bytes;
        entity.sizeBytes = bytes.length;
        entity.contentType = contentType;
        entity.originalFilename = originalFilename;
        blobs.persist(entity);

        int[] size = readDimensions(bytes);
        return new Stored(storageKey, bytes.length,
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

    /** Reads width and height from the header, without decoding the photo. */
    static int[] readDimensions(byte[] bytes) {
        if (isWebp(bytes)) return readWebpDimensions(bytes);
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

    /**
     * Reads all standardized WebP canvas/frame headers without decoding pixels. The JDK does not
     * ship a WebP ImageIO reader, so relying on ImageIO alone silently produced null dimensions.
     */
    static int[] readWebpDimensions(byte[] bytes) {
        if (!isWebp(bytes)) return emptyDimensions();
        long declaredEnd = littleEndian32(bytes, 4) + 8L;
        if (declaredEnd < 12 || declaredEnd > bytes.length) return emptyDimensions();
        int limit = (int) declaredEnd;
        int chunk = 12;
        while (chunk <= limit - 8) {
            long payloadSize = littleEndian32(bytes, chunk + 4);
            int payload = chunk + 8;
            long payloadEnd = payload + payloadSize;
            if (payloadEnd > limit) return emptyDimensions();
            int[] dimensions = switch (ascii(bytes, chunk)) {
                case "VP8X" -> vp8xDimensions(bytes, payload, payloadSize);
                case "VP8L" -> vp8lDimensions(bytes, payload, payloadSize);
                case "VP8 " -> vp8Dimensions(bytes, payload, payloadSize);
                default -> emptyDimensions();
            };
            if (dimensions[0] > 0) return dimensions;
            long next = payloadEnd + (payloadSize & 1L);
            if (next <= chunk || next > limit) return emptyDimensions();
            chunk = (int) next;
        }
        return emptyDimensions();
    }

    private static int[] vp8xDimensions(byte[] bytes, int payload, long size) {
        if (size < 10) return emptyDimensions();
        return dimensions(
                littleEndian24(bytes, payload + 4) + 1L,
                littleEndian24(bytes, payload + 7) + 1L);
    }

    private static int[] vp8lDimensions(byte[] bytes, int payload, long size) {
        if (size < 5 || unsigned(bytes[payload]) != 0x2f) return emptyDimensions();
        int b1 = unsigned(bytes[payload + 1]);
        int b2 = unsigned(bytes[payload + 2]);
        int b3 = unsigned(bytes[payload + 3]);
        int b4 = unsigned(bytes[payload + 4]);
        long width = 1L + b1 + ((long) (b2 & 0x3f) << 8);
        long height = 1L + ((b2 & 0xc0) >> 6) + ((long) b3 << 2)
                + ((long) (b4 & 0x0f) << 10);
        return dimensions(width, height);
    }

    private static int[] vp8Dimensions(byte[] bytes, int payload, long size) {
        if (size < 10
                || unsigned(bytes[payload + 3]) != 0x9d
                || unsigned(bytes[payload + 4]) != 0x01
                || unsigned(bytes[payload + 5]) != 0x2a) {
            return emptyDimensions();
        }
        long width = littleEndian16(bytes, payload + 6) & 0x3fff;
        long height = littleEndian16(bytes, payload + 8) & 0x3fff;
        return dimensions(width, height);
    }

    private static int[] dimensions(long width, long height) {
        if (width <= 0 || height <= 0
                || width > MAX_WEBP_DIMENSION || height > MAX_WEBP_DIMENSION) {
            return emptyDimensions();
        }
        return new int[] { (int) width, (int) height };
    }

    private static boolean isWebp(byte[] bytes) {
        return bytes != null && bytes.length >= 12
                && "RIFF".equals(ascii(bytes, 0))
                && "WEBP".equals(ascii(bytes, 8));
    }

    private static String ascii(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) return "";
        return new String(bytes, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static long littleEndian32(byte[] bytes, int offset) {
        return (long) unsigned(bytes[offset])
                | (long) unsigned(bytes[offset + 1]) << 8
                | (long) unsigned(bytes[offset + 2]) << 16
                | (long) unsigned(bytes[offset + 3]) << 24;
    }

    private static long littleEndian24(byte[] bytes, int offset) {
        return (long) unsigned(bytes[offset])
                | (long) unsigned(bytes[offset + 1]) << 8
                | (long) unsigned(bytes[offset + 2]) << 16;
    }

    private static int littleEndian16(byte[] bytes, int offset) {
        return unsigned(bytes[offset]) | unsigned(bytes[offset + 1]) << 8;
    }

    private static int unsigned(byte value) { return value & 0xff; }
    private static int[] emptyDimensions() { return new int[] { 0, 0 }; }

    private static String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        String extension = filename.substring(dot).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,5}") ? extension : "";
    }
}
