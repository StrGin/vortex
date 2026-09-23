package com.velasim.app;

import java.io.IOException;
import java.io.InputStream;

/**
 * Block-reading helper for {@link VelaToolchain#extractTar}: hands out exact 512-byte
 * header blocks and exact-size payload reads, transparently unwrapping gzip.
 */
final class TarInput {

    static final int BLOCK = 512;

    private final InputStream in;
    private final byte[] one = new byte[1];
    private long consumed;

    TarInput(InputStream raw) throws IOException {
        this.in = looksGzipped(raw) ? new java.util.zip.GZIPInputStream(raw, 1 << 16) : raw;
    }

    /** Total payload bytes handed out so far — used to keep the stream block-aligned. */
    long consumed() {
        return consumed;
    }

    /** Reads exactly {@code buf.length} bytes; false on a clean EOF before any byte. */
    boolean readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                return off > 0;
            }
            off += n;
        }
        consumed += off;
        return true;
    }

    /** Reads {@code n} bytes into a fresh array (n is rounded up by the caller). */
    byte[] readBytes(int n) throws IOException {
        byte[] out = new byte[Math.max(n, 0)];
        int off = 0;
        while (off < out.length) {
            int r = in.read(out, off, out.length - off);
            if (r < 0) {
                break;
            }
            off += r;
        }
        consumed += off;
        return out;
    }

    /** Skips to the next 512-byte boundary after a variable-length payload. */
    void skipPadding(long payloadLen) throws IOException {
        long pad = (BLOCK - (payloadLen % BLOCK)) % BLOCK;
        long left = pad;
        while (left > 0) {
            if (in.read(one) < 0) {
                return;
            }
            left--;
            consumed++;
        }
    }

    /** Stream-style read for large payloads (mirrors {@link InputStream#read}). */
    int read(byte[] buf, int off, int len) throws IOException {
        int n = in.read(buf, off, len);
        if (n > 0) {
            consumed += n;
        }
        return n;
    }

    void close() {
        VelaUtil.closeQuietly(in);
    }

    private static boolean looksGzipped(InputStream in) throws IOException {
        if (!in.markSupported()) {
            return false;
        }
        in.mark(2);
        int a = in.read();
        int b = in.read();
        in.reset();
        return a == 0x1f && b == 0x8b;
    }
}
