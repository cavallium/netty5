/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty5.handler.codec.compression;

import com.ning.compress.BufferRecycler;
import com.ning.compress.lzf.ChunkEncoder;
import com.ning.compress.lzf.LZFChunk;
import com.ning.compress.lzf.LZFEncoder;
import com.ning.compress.lzf.util.ChunkEncoderFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import java.util.function.Supplier;

import static com.ning.compress.lzf.LZFChunk.MAX_CHUNK_LEN;

/**
 * Compresses a {@link ByteBuf} using the LZF format.
 * <p>
 * See original <a href="http://oldhome.schmorp.de/marc/liblzf.html">LZF package</a>
 * and <a href="https://github.com/ning/compress/wiki/LZFFormat">LZF format</a> for full description.
 */
public final class LzfCompressor implements Compressor {

    /**
     * Minimum block size ready for compression. Blocks with length
     * less than {@link #MIN_BLOCK_TO_COMPRESS} will write as uncompressed.
     */
    private static final int MIN_BLOCK_TO_COMPRESS = 16;

    /**
     * Compress threshold for LZF format. When the amount of input data is less than compressThreshold,
     * we will construct an uncompressed output according to the LZF format.
     * <p>
     * When the value is less than {@see ChunkEncoder#MIN_BLOCK_TO_COMPRESS}, since LZF will not compress data
     * that is less than {@see ChunkEncoder#MIN_BLOCK_TO_COMPRESS}, compressThreshold will not work.
     */
    private final int compressThreshold;

    /**
     * Underlying decoder in use.
     */
    private final ChunkEncoder encoder;

    /**
     * Object that handles details of buffer recycling.
     */
    private final BufferRecycler recycler;

    private enum State {
        PROCESSING,
        FINISHED,
        CLOSED
    }

    private State state = State.PROCESSING;

    /**
     * Creates a new LZF compressor with specified settings.
     *
     * @param safeInstance          If {@code true} encoder will use {@link ChunkEncoder} that only uses standard
     *                              JDK access methods, and should work on all Java platforms and JVMs.
     *                              Otherwise encoder will try to use highly optimized {@link ChunkEncoder}
     *                              implementation that uses Sun JDK's {@link sun.misc.Unsafe}
     *                              class (which may be included by other JDK's as well).
     * @param totalLength           Expected total length of content to compress; only matters for outgoing messages
     *                              that is smaller than maximum chunk size (64k), to optimize encoding hash tables.
     * @param compressThreshold     Compress threshold for LZF format. When the amount of input data is less than
     *                              compressThreshold, we will construct an uncompressed output according
     *                              to the LZF format.
     */
    private LzfCompressor(boolean safeInstance, int totalLength, int compressThreshold) {
        this.compressThreshold = compressThreshold;

        this.encoder = safeInstance ?
                ChunkEncoderFactory.safeNonAllocatingInstance(totalLength)
                : ChunkEncoderFactory.optimalNonAllocatingInstance(totalLength);

        this.recycler = BufferRecycler.instance();
    }

    /**
     * Creates a new LZF compressor factory with the most optimal available methods for underlying data access.
     * It will "unsafe" instance if one can be used on current JVM.
     * It should be safe to call this constructor as implementations are dynamically loaded; however, on some
     * non-standard platforms it may be necessary to use {@link #newFactory(boolean)} with {@code true} param.
     * @return the factory.
     */
    public static Supplier<LzfCompressor> newFactory() {
        return newFactory(false);
    }

    /**
     * Creates a new LZF compressor factory with specified encoding instance.
     *
     * @param safeInstance If {@code true} encoder will use {@link ChunkEncoder} that only uses
     *                     standard JDK access methods, and should work on all Java platforms and JVMs.
     *                     Otherwise encoder will try to use highly optimized {@link ChunkEncoder}
     *                     implementation that uses Sun JDK's {@link sun.misc.Unsafe}
     *                     class (which may be included by other JDK's as well).
     * @return the factory.
     */
    public static Supplier<LzfCompressor> newFactory(boolean safeInstance) {
        return newFactory(safeInstance, MAX_CHUNK_LEN);
    }

    /**
     * Creates a new LZF compressor factory with specified encoding instance and compressThreshold.
     *
     * @param safeInstance      If {@code true} encoder will use {@link ChunkEncoder} that only uses standard
     *                          JDK access methods, and should work on all Java platforms and JVMs.
     *                          Otherwise encoder will try to use highly optimized {@link ChunkEncoder}
     *                          implementation that uses Sun JDK's {@link sun.misc.Unsafe}
     *                          class (which may be included by other JDK's as well).
     * @param totalLength       Expected total length of content to compress; only matters for outgoing messages
     *                          that is smaller than maximum chunk size (64k), to optimize encoding hash tables.
     * @return the factory.
     */
    public static Supplier<LzfCompressor> newFactory(boolean safeInstance, int totalLength) {
        return newFactory(safeInstance, totalLength, LzfCompressor.MIN_BLOCK_TO_COMPRESS);
    }

    /**
     * Creates a new LZF compressor factory with specified total length of encoded chunk. You can configure it to encode
     * your data flow more efficient if you know the average size of messages that you send.
     *
     * @param totalLength Expected total length of content to compress;
     *                    only matters for outgoing messages that is smaller than maximum chunk size (64k),
     *                    to optimize encoding hash tables.
     * @return the factory.
     */
    public static Supplier<LzfCompressor> newFactory(int totalLength) {
        return newFactory(false, totalLength);
    }

    /**
     * Creates a new LZF compressor factory with specified settings.
     *
     * @param safeInstance          If {@code true} encoder will use {@link ChunkEncoder} that only uses standard JDK
     *                              access methods, and should work on all Java platforms and JVMs.
     *                              Otherwise encoder will try to use highly optimized {@link ChunkEncoder}
     *                              implementation that uses Sun JDK's {@link sun.misc.Unsafe}
     *                              class (which may be included by other JDK's as well).
     * @param totalLength           Expected total length of content to compress; only matters for outgoing messages
     *                              that is smaller than maximum chunk size (64k), to optimize encoding hash tables.
     * @param compressThreshold     Compress threshold for LZF format. When the amount of input data is less than
     *                              compressThreshold, we will construct an uncompressed output according
     *                              to the LZF format.
     * @return the factory.
     */
    public static Supplier<LzfCompressor> newFactory(boolean safeInstance, int totalLength, int compressThreshold) {
        if (totalLength < MIN_BLOCK_TO_COMPRESS || totalLength > MAX_CHUNK_LEN) {
            throw new IllegalArgumentException("totalLength: " + totalLength +
                    " (expected: " + MIN_BLOCK_TO_COMPRESS + '-' + MAX_CHUNK_LEN + ')');
        }

        if (compressThreshold < MIN_BLOCK_TO_COMPRESS) {
            // not a suitable value.
            throw new IllegalArgumentException("compressThreshold:" + compressThreshold +
                    " expected >=" + MIN_BLOCK_TO_COMPRESS);
        }
        return () -> new LzfCompressor(safeInstance, totalLength, compressThreshold);
    }

    @Override
    public ByteBuf compress(ByteBuf in, ByteBufAllocator allocator) throws CompressionException {
        switch (state) {
            case CLOSED:
                throw new CompressionException("Compressor closed");
            case FINISHED:
                return Unpooled.EMPTY_BUFFER;
            case PROCESSING:
                final int length = in.readableBytes();
                final int idx = in.readerIndex();
                final byte[] input;
                final int inputPtr;
                if (in.hasArray()) {
                    input = in.array();
                    inputPtr = in.arrayOffset() + idx;
                } else {
                    input = recycler.allocInputBuffer(length);
                    in.getBytes(idx, input, 0, length);
                    inputPtr = 0;
                }

                // Estimate may apparently under-count by one in some cases.
                final int maxOutputLength = LZFEncoder.estimateMaxWorkspaceSize(length) + 1;
                ByteBuf out = allocator.heapBuffer(maxOutputLength);
                try {
                    out.ensureWritable(maxOutputLength);
                    final byte[] output;
                    final int outputPtr;
                    if (out.hasArray()) {
                        output = out.array();
                        outputPtr = out.arrayOffset() + out.writerIndex();
                    } else {
                        output = new byte[maxOutputLength];
                        outputPtr = 0;
                    }

                    final int outputLength;
                    if (length >= compressThreshold) {
                        // compress.
                        outputLength = encodeCompress(input, inputPtr, length, output, outputPtr);
                    } else {
                        // not compress.
                        outputLength = encodeNonCompress(input, inputPtr, length, output, outputPtr);
                    }

                    if (out.hasArray()) {
                        out.writerIndex(out.writerIndex() + outputLength);
                    } else {
                        out.writeBytes(output, 0, outputLength);
                    }

                    in.skipBytes(length);

                    if (!in.hasArray()) {
                        recycler.releaseInputBuffer(input);
                    }
                    return out;
                } catch (Throwable cause) {
                    out.release();
                    throw cause;
                }
            default:
                throw new IllegalStateException();
        }
    }

    private int encodeCompress(byte[] input, int inputPtr, int length, byte[] output, int outputPtr) {
        return LZFEncoder.appendEncoded(encoder,
                input, inputPtr, length, output, outputPtr) - outputPtr;
    }

    private static int lzfEncodeNonCompress(byte[] input, int inputPtr, int length, byte[] output, int outputPtr) {
        int left = length;
        int chunkLen = Math.min(LZFChunk.MAX_CHUNK_LEN, left);
        outputPtr = LZFChunk.appendNonCompressed(input, inputPtr, chunkLen, output, outputPtr);
        left -= chunkLen;
        if (left < 1) {
            return outputPtr;
        }
        inputPtr += chunkLen;
        do {
            chunkLen = Math.min(left, LZFChunk.MAX_CHUNK_LEN);
            outputPtr = LZFChunk.appendNonCompressed(input, inputPtr, chunkLen, output, outputPtr);
            inputPtr += chunkLen;
            left -= chunkLen;
        } while (left > 0);
        return outputPtr;
    }

    /**
     * Use lzf uncompressed format to encode a piece of input.
     */
    private static int encodeNonCompress(byte[] input, int inputPtr, int length, byte[] output, int outputPtr) {
        return lzfEncodeNonCompress(input, inputPtr, length, output, outputPtr) - outputPtr;
    }

    @Override
    public ByteBuf finish(ByteBufAllocator allocator) {
        switch (state) {
            case CLOSED:
                throw new CompressionException("Compressor closed");
            case FINISHED:
            case PROCESSING:
                state = State.FINISHED;
                return Unpooled.EMPTY_BUFFER;
            default:
                throw new IllegalStateException();
        }
    }

    @Override
    public boolean isFinished() {
        return state != State.PROCESSING;
    }

    @Override
    public boolean isClosed() {
        return state == State.CLOSED;
    }

    @Override
    public void close() {
        if (state != State.CLOSED) {
            state = State.CLOSED;
            encoder.close();
        }
    }
}
