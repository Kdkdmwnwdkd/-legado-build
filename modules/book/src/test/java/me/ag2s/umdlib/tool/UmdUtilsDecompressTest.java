package me.ag2s.umdlib.tool;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.junit.Assert.assertArrayEquals;

public class UmdUtilsDecompressTest {

    @Test
    public void emptyDataMayUseZeroLimit() throws Exception {
        assertArrayEquals(new byte[0], UmdUtils.decompress(compress(new byte[0]), 0));
    }

    @Test(expected = IOException.class)
    public void zeroLimitRejectsNonEmptyData() throws Exception {
        UmdUtils.decompress(compress(new byte[]{1}), 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeLimitIsRejected() throws Exception {
        UmdUtils.decompress(compress(new byte[0]), -1);
    }

    @Test
    public void legacyEntryPointRemainsCompatible() throws Exception {
        byte[] original = "legacy".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertArrayEquals(original, UmdUtils.decompress(compress(original)));
    }

    @Test
    public void validDataMayExactlyFillLimit() throws Exception {
        byte[] original = new byte[16_384];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) i;
        }

        assertArrayEquals(original, UmdUtils.decompress(compress(original), original.length));
    }

    @Test(expected = IOException.class)
    public void outputBeyondLimitIsRejected() throws Exception {
        byte[] original = new byte[1024 * 1024];

        UmdUtils.decompress(compress(original), 64 * 1024);
    }

    @Test(timeout = 1000, expected = IOException.class)
    public void truncatedInputFailsWithoutLooping() throws Exception {
        byte[] compressed = compress("content".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        UmdUtils.decompress(Arrays.copyOf(compressed, compressed.length - 1), 1024);
    }

    @Test(timeout = 1000, expected = IOException.class)
    public void presetDictionaryFailsWithoutLooping() throws Exception {
        byte[] dictionary = "shared dictionary".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] data = "shared dictionary content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Deflater deflater = new Deflater();
        try {
            deflater.setDictionary(dictionary);
            deflater.setInput(data);
            deflater.finish();
            byte[] compressed = new byte[128];
            int length = deflater.deflate(compressed);

            UmdUtils.decompress(Arrays.copyOf(compressed, length), 1024);
        } finally {
            deflater.end();
        }
    }

    @Test(expected = IOException.class)
    public void corruptInputIsReportedAsIoFailure() throws Exception {
        UmdUtils.decompress(new byte[]{1, 2, 3, 4}, 1024);
    }

    private byte[] compress(byte[] data) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(output)) {
            deflater.write(data);
        }
        return output.toByteArray();
    }
}
