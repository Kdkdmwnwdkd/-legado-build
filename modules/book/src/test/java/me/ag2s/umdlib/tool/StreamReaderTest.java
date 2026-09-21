package me.ag2s.umdlib.tool;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class StreamReaderTest {

    @Test
    public void fixedWidthReadsHandleFragmentedInput() throws Exception {
        byte[] data = new byte[]{
                (byte) 0xFF, (byte) 0x80,
                0x12, 0x34,
                0x78, 0x56,
                0x01, 0x02, 0x03, 0x04,
                0x78, 0x56, 0x34, 0x12,
                0x41, 0x42
        };
        StreamReader reader = new StreamReader(new FragmentedInputStream(data, 1));

        assertEquals(255, reader.readUint8());
        assertEquals((byte) 0x80, reader.readByte());
        assertEquals((short) 0x1234, reader.readShort());
        assertEquals((short) 0x5678, reader.readShortLe());
        assertEquals(0x01020304, reader.readInt());
        assertEquals(0x12345678, reader.readIntLe());
        assertEquals("4142", reader.readHex(2));
        assertEquals(data.length, reader.getOffset());
    }

    @Test
    public void arrayReadsAndSkipHandleFragmentedInput() throws Exception {
        StreamReader reader = new StreamReader(
                new FragmentedInputStream(new byte[]{1, 2, 3, 4, 5, 6}, 2)
        );
        byte[] target = new byte[4];

        reader.skip(1);
        reader.read(target, 1, 2);
        assertArrayEquals(new byte[]{0, 2, 3, 0}, target);
        assertArrayEquals(new byte[]{4, 5, 6}, reader.read(new byte[3]));
        assertEquals(6, reader.getOffset());
    }

    @Test
    public void zeroLengthBulkReadDoesNotLoop() throws Exception {
        StreamReader reader = new StreamReader(new ZeroThenDataInputStream(new byte[]{1, 2}));

        assertArrayEquals(new byte[]{1, 2}, reader.readBytes(2));
        assertEquals(2, reader.getOffset());
    }

    @Test
    public void eofReportsActuallyConsumedOffset() throws Exception {
        StreamReader reader = new StreamReader(new ByteArrayInputStream(new byte[]{1, 2}));

        try {
            reader.readInt();
        } catch (EOFException expected) {
            assertEquals(2, reader.getOffset());
            return;
        }
        throw new AssertionError("Expected EOFException");
    }

    private static class FragmentedInputStream extends ByteArrayInputStream {
        private final int maxRead;

        FragmentedInputStream(byte[] data, int maxRead) {
            super(data);
            this.maxRead = maxRead;
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            return super.read(buffer, offset, Math.min(length, maxRead));
        }
    }

    private static final class ZeroThenDataInputStream extends InputStream {
        private final ByteArrayInputStream delegate;
        private boolean returnedZero;

        ZeroThenDataInputStream(byte[] data) {
            delegate = new ByteArrayInputStream(data);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (!returnedZero) {
                returnedZero = true;
                return 0;
            }
            return delegate.read(buffer, offset, length);
        }

        @Override
        public int read() {
            return delegate.read();
        }
    }
}
