package be.enrosed.catalog.domain;

/**
 * Een productfoto. Het bestand blijft in volle kwaliteit op de opslag staan -
 * er wordt niets herschaald of hercomprimeerd, zodat de foto ook weer
 * bruikbaar uit het systeem komt voor drukwerk of een webshop.
 */
public record Photo(
        Long id,
        String storageKey,
        String originalFilename,
        String contentType,
        long sizeBytes,
        Integer widthPx,
        Integer heightPx,
        int position
) {
}
