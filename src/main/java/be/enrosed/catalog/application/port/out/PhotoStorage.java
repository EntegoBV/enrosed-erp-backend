package be.enrosed.catalog.application.port.out;

import java.io.InputStream;

/**
 * Uitgaande poort voor de fotobestanden.
 *
 * De bytes gaan er ongewijzigd in en ongewijzigd weer uit: geen herschaling,
 * geen hercompressie. Wat de leverancier stuurt is wat je terugkrijgt.
 */
public interface PhotoStorage {

    record Stored(String storageKey, long sizeBytes, Integer widthPx, Integer heightPx) {}

    Stored store(String originalFilename, String contentType, InputStream data);

    InputStream read(String storageKey);

    void delete(String storageKey);

    boolean exists(String storageKey);
}
