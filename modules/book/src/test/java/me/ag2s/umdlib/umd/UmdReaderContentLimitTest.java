package me.ag2s.umdlib.umd;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DeflaterOutputStream;

import me.ag2s.umdlib.domain.UmdBook;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class UmdReaderContentLimitTest {

    @Test
    public void writerRoundTripSupportsMultipleCompressedChunks() throws Exception {
        UmdBook original = new UmdBook();
        String first = repeat('a', 40_000);
        String second = repeat('中', 10_000);
        original.getChapters().addChapter("first", first);
        original.getChapters().addChapter("second", second);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        original.buildUmd(encoded);

        UmdBook parsed = new UmdReader(new UmdReaderLimits(256_000, 1024 * 1024, 1024 * 1024))
                .read(new ByteArrayInputStream(encoded.toByteArray()));

        assertEquals(first, parsed.getChapters().getContentString(0));
        assertEquals(second, parsed.getChapters().getContentString(1));
    }

    @Test
    public void contentWithinDeclaredAndConfiguredLimitsIsAccepted() throws Exception {
        byte[] content = new byte[]{1, 2, 3, 4};
        UmdBook book = new UmdReader(new UmdReaderLimits(16, 8, 1024))
                .read(new ByteArrayInputStream(umdWithContent(content.length, content)));

        assertArrayEquals(content, book.getChapters().contents.toByteArray());
    }

    @Test(expected = IOException.class)
    public void declaredContentBeyondConfiguredLimitIsRejected() throws Exception {
        new UmdReader(new UmdReaderLimits(3, 3, 1024))
                .read(new ByteArrayInputStream(umdWithContent(4, new byte[]{1, 2, 3, 4})));
    }

    @Test(expected = IOException.class)
    public void decompressedBlockBeyondConfiguredLimitIsRejected() throws Exception {
        new UmdReader(new UmdReaderLimits(16, 3, 1024))
                .read(new ByteArrayInputStream(umdWithContent(4, new byte[]{1, 2, 3, 4})));
    }

    @Test(expected = IOException.class)
    public void shorterActualContentIsRejectedAtEndSection() throws Exception {
        new UmdReader(new UmdReaderLimits(16, 8, 1024))
                .read(new ByteArrayInputStream(umdWithContent(5, new byte[]{1, 2, 3, 4})));
    }

    @Test(expected = IOException.class)
    public void invalidAdditionalSectionLengthIsRejectedBeforeAllocation() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeMagic(output);
        writeContentLengthSection(output, 0);
        writeSectionHeader(output, 132, 4);
        writeIntLe(output, 1);
        output.write('$');
        writeIntLe(output, 2);
        writeIntLe(output, 8);

        new UmdReader(new UmdReaderLimits(16, 8, 1024))
                .read(new ByteArrayInputStream(output.toByteArray()));
    }

    @Test(expected = IOException.class)
    public void missingEndSectionCannotBypassContentLengthValidation() throws Exception {
        byte[] complete = umdWithContent(5, new byte[]{1, 2, 3, 4});
        byte[] withoutEnd = java.util.Arrays.copyOf(complete, complete.length - 9);
        ByteArrayOutputStream malformed = new ByteArrayOutputStream();
        malformed.write(withoutEnd);
        malformed.write(0);

        new UmdReader(new UmdReaderLimits(16, 8, 1024))
                .read(new ByteArrayInputStream(malformed.toByteArray()));
    }

    @Test(expected = IOException.class)
    public void chapterCountBeyondConfiguredLimitIsRejected() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeMagic(output);
        writeAdditionalSection(output, 131, 1, 2, new byte[16]);

        new UmdReader(new UmdReaderLimits(16, 8, 1024, 3, 16))
                .read(new ByteArrayInputStream(output.toByteArray()));
    }

    @Test(expected = IOException.class)
    public void titleCannotReadPastItsAdditionalPayload() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeMagic(output);
        writeContentLengthSection(output, 0);
        writeAdditionalSection(output, 131, 1, 2, new byte[4]);
        writeAdditionalSection(output, 132, 3, 3, new byte[]{10});

        new UmdReader(new UmdReaderLimits(16, 8, 1024, 3, 16))
                .read(new ByteArrayInputStream(output.toByteArray()));
    }

    @Test(expected = IOException.class)
    public void decreasingChapterOffsetsAreRejected() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeMagic(output);
        writeContentLengthSection(output, 4);
        ByteArrayOutputStream offsets = new ByteArrayOutputStream();
        writeIntLe(offsets, 0);
        writeIntLe(offsets, 3);
        writeIntLe(offsets, 2);
        writeAdditionalSection(output, 131, 1, 2, offsets.toByteArray());

        new UmdReader(new UmdReaderLimits(16, 8, 1024, 3, 16))
                .read(new ByteArrayInputStream(output.toByteArray()));
    }

    private byte[] umdWithContent(int declaredLength, byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeMagic(output);
        writeContentLengthSection(output, declaredLength);

        writeSectionHeader(output, 132, 4);
        writeIntLe(output, 1);
        byte[] compressed = compress(content);
        output.write('$');
        writeIntLe(output, 2);
        writeIntLe(output, compressed.length + 9);
        output.write(compressed);

        writeSectionHeader(output, 12, 4);
        writeIntLe(output, output.size() + 4);
        return output.toByteArray();
    }

    private void writeMagic(ByteArrayOutputStream output) {
        output.write(0x89);
        output.write(0x9B);
        output.write(0x9A);
        output.write(0xDE);
    }

    private void writeContentLengthSection(ByteArrayOutputStream output, int length) {
        writeSectionHeader(output, 11, 4);
        writeIntLe(output, length);
    }

    private void writeAdditionalSection(
            ByteArrayOutputStream output,
            int type,
            int sectionCheck,
            int additionalCheck,
            byte[] payload
    ) throws IOException {
        writeSectionHeader(output, type, 4);
        writeIntLe(output, sectionCheck);
        output.write('$');
        writeIntLe(output, additionalCheck);
        writeIntLe(output, payload.length + 9);
        output.write(payload);
    }

    private void writeSectionHeader(ByteArrayOutputStream output, int type, int payloadLength) {
        output.write('#');
        output.write(type & 0xFF);
        output.write((type >>> 8) & 0xFF);
        output.write(0);
        output.write(payloadLength + 5);
    }

    private void writeIntLe(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 24) & 0xFF);
    }

    private byte[] compress(byte[] data) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(output)) {
            deflater.write(data);
        }
        return output.toByteArray();
    }

    private String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}
