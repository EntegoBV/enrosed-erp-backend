package be.enrosed.catalog.adapter.out.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class DatabasePhotoStorageTest {

    @Test
    void readsDimensionsFromARealWebpWithoutAnImageIoPlugin() throws Exception {
        byte[] webp;
        try (var input = getClass().getResourceAsStream(
                "/images/soap-roos-in-box-480.webp")) {
            webp = java.util.Objects.requireNonNull(input).readAllBytes();
        }

        assertArrayEquals(new int[] { 480, 320 },
                DatabasePhotoStorage.readDimensions(webp));
    }

    @Test
    void supportsExtendedLosslessAndLossyHeaders() {
        assertArrayEquals(new int[] { 640, 480 }, DatabasePhotoStorage.readWebpDimensions(
                webpChunk("VP8X", new byte[] {
                        0, 0, 0, 0, 0x7f, 0x02, 0, (byte) 0xdf, 0x01, 0
                })));
        assertArrayEquals(new int[] { 2, 3 }, DatabasePhotoStorage.readWebpDimensions(
                webpChunk("VP8L", new byte[] { 0x2f, 1, (byte) 0x80, 0, 0 })));
        assertArrayEquals(new int[] { 320, 240 }, DatabasePhotoStorage.readWebpDimensions(
                webpChunk("VP8 ", new byte[] {
                        0, 0, 0, (byte) 0x9d, 0x01, 0x2a,
                        0x40, 0x01, (byte) 0xf0, 0
                })));
    }

    @Test
    void malformedWebpHeadersFailClosedWithoutReadingPastTheBuffer() {
        byte[] truncated = webpChunk("VP8X", new byte[] { 0, 0, 0 });
        truncated[4] = (byte) 0xff;
        truncated[5] = (byte) 0xff;
        truncated[6] = (byte) 0xff;
        truncated[7] = 0x7f;

        assertArrayEquals(new int[] { 0, 0 },
                DatabasePhotoStorage.readDimensions(truncated));
        assertArrayEquals(new int[] { 0, 0 },
                DatabasePhotoStorage.readDimensions(new byte[] {
                        'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P'
                }));
    }

    private static byte[] webpChunk(String type, byte[] payload) {
        int paddedPayload = payload.length + (payload.length & 1);
        byte[] bytes = new byte[20 + paddedPayload];
        ascii(bytes, 0, "RIFF");
        littleEndian32(bytes, 4, bytes.length - 8);
        ascii(bytes, 8, "WEBP");
        ascii(bytes, 12, type);
        littleEndian32(bytes, 16, payload.length);
        System.arraycopy(payload, 0, bytes, 20, payload.length);
        return bytes;
    }

    private static void ascii(byte[] target, int offset, String value) {
        byte[] source = value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(source, 0, target, offset, 4);
    }

    private static void littleEndian32(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }
}
