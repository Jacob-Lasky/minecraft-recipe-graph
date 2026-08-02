package io.github.jacoblasky.recipedump.plan;

import java.nio.charset.StandardCharsets;

/**
 * BLAKE2b (RFC 7693), unkeyed, for {@link Pins#fingerprint}.
 *
 * WRITTEN OUT RATHER THAN TAKEN FROM A LIBRARY BECAUSE THERE IS NO LIBRARY HERE. Java 8's
 * `MessageDigest` offers MD5, SHA-1 and the SHA-2 family and nothing else, and neither
 * Minecraft 1.12.2 nor Forge 14.23.5 puts Bouncy Castle on the classpath -- verified against
 * the dev classpath, which has no `org.bouncycastle` at all. Shipping a crypto library inside
 * a Minecraft mod to hash a recipe is the worse trade.
 *
 * DO NOT SWAP THIS FOR SHA-256 ON THE GROUNDS THAT THE JDK HAS ONE. The hash is not a
 * security boundary and any decent digest would do the job in isolation -- but
 * `recipegraph/pins.py` writes `pins.json` with blake2b, a user's pin file is the one piece
 * of hand-authored state in this project, and a Java side computing a different digest would
 * lapse every pin the player ever set while reporting it as the pack having changed the
 * recipe. That is the exact failure `pins.py` exists to prevent, arriving through the fix.
 *
 * `Blake2bTest` pins this against `hashlib.blake2b` output and against RFC 7693's own
 * vectors, so a transcription slip fails there rather than in a plan six months later.
 *
 * Not thread-safe and not meant to be: one instance hashes one message. Every caller here is
 * on the client thread.
 */
final class Blake2b {

    /** RFC 7693 section 2.6: the first 64 bits of the fractional parts of sqrt of 2..19. */
    private static final long[] IV = {
        0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
        0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L,
    };

    /** RFC 7693 section 2.7. Twelve rounds for BLAKE2b, so the first four repeat. */
    private static final byte[][] SIGMA = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
        {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
        {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
        {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
        {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
        {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
        {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
        {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
        {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
        {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0},
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
        {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
    };

    private static final int BLOCK_BYTES = 128;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Blake2b() {
    }

    /**
     * `message` hashed to `digestBytes` bytes, rendered as lower-case hex.
     *
     * @param digestBytes 1 to 64, matching `hashlib.blake2b(digest_size=...)`. The digest
     *                    length is mixed into the initial state, so a 6-byte digest is NOT a
     *                    truncated 64-byte one and truncating would silently disagree with
     *                    Python.
     */
    static String hex(String message, int digestBytes) {
        return hex(message.getBytes(StandardCharsets.UTF_8), digestBytes);
    }

    static String hex(byte[] message, int digestBytes) {
        byte[] digest = digest(message, digestBytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(HEX[(b >>> 4) & 0xf]).append(HEX[b & 0xf]);
        }
        return sb.toString();
    }

    static byte[] digest(byte[] message, int digestBytes) {
        if (digestBytes < 1 || digestBytes > 64) {
            throw new IllegalArgumentException("blake2b digest size must be 1..64, got "
                    + digestBytes);
        }
        long[] h = IV.clone();
        // The parameter block, unkeyed and unfanned: digest length in byte 0, key length 0
        // in byte 1, fanout 1 in byte 2, depth 1 in byte 3. Only h[0] is touched because
        // every other parameter field is zero for this configuration.
        h[0] ^= 0x01010000L ^ digestBytes;

        long[] m = new long[16];
        int offset = 0;
        // Every FULL block that is not the last. The final block is handled outside the
        // loop because it is the one that sets the finalisation flag, and a message whose
        // length is an exact multiple of 128 must take its last full block down that path
        // rather than this one -- `>` and not `>=` is what makes that true, and getting it
        // backwards produces a hash that is correct for every length except the multiples.
        while (message.length - offset > BLOCK_BYTES) {
            loadBlock(message, offset, m);
            offset += BLOCK_BYTES;
            compress(h, m, offset, false);
        }

        // The last block, zero-padded. The counter carries the REAL message length, not the
        // padded one, which is what makes "abc" and "abc\0...\0" hash differently.
        byte[] last = new byte[BLOCK_BYTES];
        System.arraycopy(message, offset, last, 0, message.length - offset);
        loadBlock(last, 0, m);
        compress(h, m, message.length, true);

        byte[] out = new byte[digestBytes];
        for (int i = 0; i < digestBytes; i++) {
            out[i] = (byte) (h[i >>> 3] >>> (8 * (i & 7)));
        }
        return out;
    }

    /** Sixteen little-endian 64-bit words out of one 128-byte block. */
    private static void loadBlock(byte[] src, int offset, long[] m) {
        for (int i = 0; i < 16; i++) {
            int p = offset + i * 8;
            m[i] = (src[p] & 0xffL)
                    | (src[p + 1] & 0xffL) << 8
                    | (src[p + 2] & 0xffL) << 16
                    | (src[p + 3] & 0xffL) << 24
                    | (src[p + 4] & 0xffL) << 32
                    | (src[p + 5] & 0xffL) << 40
                    | (src[p + 6] & 0xffL) << 48
                    | (src[p + 7] & 0xffL) << 56;
        }
    }

    /**
     * RFC 7693 section 3.2, compression function F.
     *
     * `counter` is the total bytes hashed SO FAR INCLUDING this block. BLAKE2b's counter is
     * 128 bits and this only fills the low half; the high half stays zero, which is correct
     * for any message a JVM can hold in a `byte[]`.
     */
    private static void compress(long[] h, long[] m, long counter, boolean last) {
        long[] v = new long[16];
        System.arraycopy(h, 0, v, 0, 8);
        System.arraycopy(IV, 0, v, 8, 8);
        v[12] ^= counter;
        // v[13] ^= counter >>> 64, which is always zero here. See above.
        if (last) {
            v[14] = ~v[14];
        }
        for (int round = 0; round < 12; round++) {
            byte[] s = SIGMA[round];
            mix(v, 0, 4, 8, 12, m[s[0]], m[s[1]]);
            mix(v, 1, 5, 9, 13, m[s[2]], m[s[3]]);
            mix(v, 2, 6, 10, 14, m[s[4]], m[s[5]]);
            mix(v, 3, 7, 11, 15, m[s[6]], m[s[7]]);
            mix(v, 0, 5, 10, 15, m[s[8]], m[s[9]]);
            mix(v, 1, 6, 11, 12, m[s[10]], m[s[11]]);
            mix(v, 2, 7, 8, 13, m[s[12]], m[s[13]]);
            mix(v, 3, 4, 9, 14, m[s[14]], m[s[15]]);
        }
        for (int i = 0; i < 8; i++) {
            h[i] ^= v[i] ^ v[i + 8];
        }
    }

    /** RFC 7693 section 3.1, mixing function G. Rotations are 32, 24, 16, 63. */
    private static void mix(long[] v, int a, int b, int c, int d, long x, long y) {
        v[a] = v[a] + v[b] + x;
        v[d] = Long.rotateRight(v[d] ^ v[a], 32);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 24);
        v[a] = v[a] + v[b] + y;
        v[d] = Long.rotateRight(v[d] ^ v[a], 16);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 63);
    }
}
