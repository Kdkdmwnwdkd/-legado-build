package me.ag2s.umdlib.domain;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

import me.ag2s.umdlib.tool.WrapOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UmdChaptersMemoryTest {

    @Test
    public void getContentCopiesOnlyRequestedRangeWithoutFullSnapshot() throws Exception {
        UmdChapters chapters = new UmdChapters();
        SnapshotRejectingBuffer contents = new SnapshotRejectingBuffer();
        contents.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        chapters.contents = contents;
        chapters.addContentLength(0);
        chapters.addContentLength(4);
        chapters.setTotalContentLen(8);

        assertArrayEquals(new byte[]{5, 6, 7, 8}, chapters.getContent(1));
    }

    @Test
    public void getContentStringDecodesRequestedRangeWithoutIntermediateByteArray() throws Exception {
        UmdChapters chapters = new UmdChapters();
        SnapshotRejectingBuffer contents = new SnapshotRejectingBuffer();
        byte[] first = "first".getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        byte[] second = "line1\u2029line2".getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        contents.write(first);
        contents.write(second);
        chapters.contents = contents;
        chapters.addContentLength(0);
        chapters.addContentLength(first.length);
        chapters.setTotalContentLen(first.length + second.length);

        assertEquals("line1\nline2", chapters.getContentString(1));
    }

    @Test
    public void buildChaptersCompressesRangesWithoutFullSnapshot() throws Exception {
        UmdChapters chapters = new UmdChapters();
        SnapshotRejectingBuffer contents = new SnapshotRejectingBuffer();
        byte[] data = new byte[70_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        contents.write(data);
        chapters.contents = contents;
        chapters.addTitle("one");
        chapters.addTitle("two");
        chapters.addContentLength(35_000);
        chapters.addContentLength(35_000);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        chapters.buildChapters(new WrapOutputStream(output));

        assertTrue(output.size() > 0);
    }

    @Test
    public void buildChaptersSnapshotsReplacementBufferOnlyOnce() throws Exception {
        UmdChapters chapters = new UmdChapters();
        CountingSnapshotBuffer contents = new CountingSnapshotBuffer();
        contents.write(new byte[70_000]);
        chapters.contents = contents;
        chapters.addTitle("one");
        chapters.addContentLength(70_000);

        chapters.buildChapters(new WrapOutputStream(new ByteArrayOutputStream()));

        assertEquals(1, contents.snapshotCount);
    }

    @Test(expected = ArrayIndexOutOfBoundsException.class)
    public void replacementBufferDoesNotSilentlyPadInvalidContentRange() throws Exception {
        UmdChapters chapters = new UmdChapters();
        chapters.contents = new ByteArrayOutputStream();
        chapters.contents.write(new byte[]{1, 2});
        chapters.addContentLength(0);
        chapters.setTotalContentLen(4);

        chapters.getContent(0);
    }

    private static final class SnapshotRejectingBuffer extends SliceableByteArrayOutputStream {
        @Override
        public synchronized byte[] toByteArray() {
            throw new AssertionError("A complete content snapshot must not be created");
        }
    }

    private static final class CountingSnapshotBuffer extends ByteArrayOutputStream {
        private int snapshotCount;

        @Override
        public synchronized byte[] toByteArray() {
            snapshotCount++;
            return super.toByteArray();
        }
    }
}
