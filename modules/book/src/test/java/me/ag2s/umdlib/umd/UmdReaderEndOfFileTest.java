package me.ag2s.umdlib.umd;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.util.Arrays;

import me.ag2s.umdlib.domain.UmdBook;

import static org.junit.Assert.assertNotNull;

public class UmdReaderEndOfFileTest {

    @Test
    public void endSectionMayBeThePhysicalEndOfFile() throws Exception {
        byte[] minimalUmd = minimalUmd();

        UmdBook book = new UmdReader().read(new ByteArrayInputStream(minimalUmd));

        assertNotNull(book);
    }

    @Test(expected = EOFException.class)
    public void truncatedEndSectionIsRejected() throws Exception {
        byte[] minimalUmd = minimalUmd();
        byte[] truncated = Arrays.copyOf(minimalUmd, minimalUmd.length - 1);

        new UmdReader().read(new ByteArrayInputStream(truncated));
    }

    @Test
    public void readerCanBeReusedAfterACompleteBook() throws Exception {
        UmdReader reader = new UmdReader();

        assertNotNull(reader.read(new ByteArrayInputStream(minimalUmd())));
        assertNotNull(reader.read(new ByteArrayInputStream(minimalUmd())));
    }

    private byte[] minimalUmd() {
        return new byte[]{
                (byte) 0x89, (byte) 0x9B, (byte) 0x9A, (byte) 0xDE,
                '#', 0x0C, 0x00, 0x01, 0x09,
                0x0D, 0x00, 0x00, 0x00
        };
    }
}
