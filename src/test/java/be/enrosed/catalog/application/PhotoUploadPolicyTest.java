package be.enrosed.catalog.application;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhotoUploadPolicyTest {

    @Test
    void detectsAllowedSignaturesAndCanonicalMimeTypes() {
        List<Example> examples = List.of(
                new Example(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01}, "image/jpeg"),
                new Example(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a},
                        "image/png"),
                new Example("GIF87a".getBytes(StandardCharsets.US_ASCII), "image/gif"),
                new Example(new byte[] {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P'},
                        "image/webp"));

        for (Example example : examples) {
            PhotoUploadPolicy.ValidatedPhoto validated = PhotoUploadPolicy.validate(
                    "supplier.bin", new ByteArrayInputStream(example.bytes()));
            assertEquals(example.contentType(), validated.contentType());
            assertArrayEquals(example.bytes(), validated.bytes());
        }
    }

    @Test
    void rejectsEmptyAndNonImageFiles() {
        assertThrows(PhotoUploadPolicy.InvalidPhotoException.class,
                () -> PhotoUploadPolicy.validate("empty.jpg", new ByteArrayInputStream(new byte[0])));
        assertThrows(PhotoUploadPolicy.InvalidPhotoException.class,
                () -> PhotoUploadPolicy.validate("attack.svg", new ByteArrayInputStream(
                        "<svg><script/></svg>".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void rejectsFilesLargerThanTwentyFiveMegabytes() {
        InputStream oversized = new RepeatingJpeg(PhotoUploadPolicy.MAX_BYTES + 1L);

        PhotoUploadPolicy.InvalidPhotoException error = assertThrows(
                PhotoUploadPolicy.InvalidPhotoException.class,
                () -> PhotoUploadPolicy.validate("large.jpg", oversized));

        assertEquals("Een foto mag maximaal 25 MB groot zijn", error.getMessage());
    }

    @Test
    void stripsPathsAndUnsafeCharactersAndCorrectsTheExtension() {
        PhotoUploadPolicy.ValidatedPhoto validated = PhotoUploadPolicy.validate(
                "../supplier\\bad\r\n\"name.html",
                new ByteArrayInputStream("GIF89a".getBytes(StandardCharsets.US_ASCII)));

        assertEquals("badname.gif", validated.originalFilename());
    }

    private record Example(byte[] bytes, String contentType) {}

    private static final class RepeatingJpeg extends InputStream {
        private static final byte[] SIGNATURE = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01};
        private long remaining;
        private long position;

        private RepeatingJpeg(long size) {
            remaining = size;
        }

        @Override
        public int read() {
            if (remaining == 0) return -1;
            int value = position < SIGNATURE.length ? SIGNATURE[(int) position] & 0xff : 0;
            position++;
            remaining--;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (remaining == 0) return -1;
            int count = (int) Math.min(length, remaining);
            for (int i = 0; i < count; i++) buffer[offset + i] = (byte) read();
            return count;
        }
    }
}
