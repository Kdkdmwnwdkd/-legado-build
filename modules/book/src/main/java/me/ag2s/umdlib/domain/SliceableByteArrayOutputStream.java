package me.ag2s.umdlib.domain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * A {@link ByteArrayOutputStream} that can copy or write a bounded range
 * without first cloning the complete accumulated content.
 */
class SliceableByteArrayOutputStream extends ByteArrayOutputStream {

    synchronized byte[] copyRange(int fromIndex, int toIndex) {
        checkRange(fromIndex, toIndex - fromIndex);
        return Arrays.copyOfRange(buf, fromIndex, toIndex);
    }

    synchronized void writeRangeTo(OutputStream output, int offset, int length) throws IOException {
        checkRange(offset, length);
        output.write(buf, offset, length);
    }

    synchronized String decodeRange(int offset, int length, Charset charset) {
        checkRange(offset, length);
        return new String(buf, offset, length, charset);
    }

    private void checkRange(int offset, int length) {
        if (offset < 0 || length < 0 || offset > count - length) {
            throw new IndexOutOfBoundsException(
                    "Range [" + offset + ", " + (offset + length) + ") exceeds content size " + count
            );
        }
    }
}
