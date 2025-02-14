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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import java.util.function.Supplier;

import static io.netty5.handler.codec.compression.Bzip2Constants.*;

/**
 * Uncompresses a {@link ByteBuf} encoded with the Bzip2 format.
 *
 * See <a href="https://en.wikipedia.org/wiki/Bzip2">Bzip2</a>.
 */
public final class Bzip2Decompressor implements Decompressor {
    /**
     * Current state of stream.
     */
    private enum State {
        INIT,
        INIT_BLOCK,
        INIT_BLOCK_PARAMS,
        RECEIVE_HUFFMAN_USED_MAP,
        RECEIVE_HUFFMAN_USED_BITMAPS,
        RECEIVE_SELECTORS_NUMBER,
        RECEIVE_SELECTORS,
        RECEIVE_HUFFMAN_LENGTH,
        DECODE_HUFFMAN_DATA,
        EOF,
        CLOSED
    }
    private State currentState = State.INIT;

    /**
     * A reader that provides bit-level reads.
     */
    private final Bzip2BitReader reader = new Bzip2BitReader();

    /**
     * The decompressor for the current block.
     */
    private Bzip2BlockDecompressor blockDecompressor;

    /**
     * Bzip2 Huffman coding stage.
     */
    private Bzip2HuffmanStageDecoder huffmanStageDecoder;

    /**
     * Always: in the range 0 .. 9. The current block size is 100000 * this number.
     */
    private int blockSize;

    /**
     * The CRC of the current block as read from the block header.
     */
    private int blockCRC;

    /**
     * The merged CRC of all blocks decompressed so far.
     */
    private int streamCRC;

    private Bzip2Decompressor() { }

    /**
     * Returns a factory for {@link Bzip2Decompressor}s.
     *
     * @return a factory.
     */
    public static Supplier<Bzip2Decompressor> newFactory() {
        return Bzip2Decompressor::new;
    }

    @Override
    public ByteBuf decompress(ByteBuf in, ByteBufAllocator allocator)
            throws DecompressionException {
        switch (currentState) {
            case CLOSED:
                throw new DecompressionException("Decompressor closed");
            case EOF:
                return Unpooled.EMPTY_BUFFER;
            default:

                if (!in.isReadable()) {
                    return null;
                }

                final Bzip2BitReader reader = this.reader;
                reader.setByteBuf(in);

                for (;;) {
                    switch (currentState) {
                        case INIT:
                            if (in.readableBytes() < 4) {
                                return null;
                            }
                            int magicNumber = in.readUnsignedMedium();
                            if (magicNumber != MAGIC_NUMBER) {
                                currentState = State.EOF;
                                throw new DecompressionException("Unexpected stream identifier contents. " +
                                        "Mismatched bzip2 protocol version?");
                            }
                            int blockSize = in.readByte() - '0';
                            if (blockSize < MIN_BLOCK_SIZE || blockSize > MAX_BLOCK_SIZE) {
                                currentState = State.EOF;
                                throw new DecompressionException("block size is invalid");
                            }
                            this.blockSize = blockSize * BASE_BLOCK_SIZE;

                            streamCRC = 0;
                            currentState = State.INIT_BLOCK;
                            // fall through
                        case INIT_BLOCK:
                            if (!reader.hasReadableBytes(10)) {
                                return null;
                            }
                            // Get the block magic bytes.
                            final int magic1 = reader.readBits(24);
                            final int magic2 = reader.readBits(24);
                            if (magic1 == END_OF_STREAM_MAGIC_1 && magic2 == END_OF_STREAM_MAGIC_2) {
                                // End of stream was reached. Check the combined CRC.
                                final int storedCombinedCRC = reader.readInt();
                                if (storedCombinedCRC != streamCRC) {
                                    currentState = State.EOF;
                                    throw new DecompressionException("stream CRC error");
                                }
                                currentState = State.EOF;
                                break;
                            }
                            if (magic1 != BLOCK_HEADER_MAGIC_1 || magic2 != BLOCK_HEADER_MAGIC_2) {
                                currentState = State.EOF;
                                throw new DecompressionException("bad block header");
                            }
                            blockCRC = reader.readInt();
                            currentState = State.INIT_BLOCK_PARAMS;
                            // fall through
                        case INIT_BLOCK_PARAMS:
                            if (!reader.hasReadableBits(25)) {
                                return null;
                            }
                            final boolean blockRandomised = reader.readBoolean();
                            final int bwtStartPointer = reader.readBits(24);

                            blockDecompressor = new Bzip2BlockDecompressor(this.blockSize, blockCRC,
                                    blockRandomised, bwtStartPointer, reader);
                            currentState = State.RECEIVE_HUFFMAN_USED_MAP;
                            // fall through
                        case RECEIVE_HUFFMAN_USED_MAP:
                            if (!reader.hasReadableBits(16)) {
                                return null;
                            }
                            blockDecompressor.huffmanInUse16 = reader.readBits(16);
                            currentState = State.RECEIVE_HUFFMAN_USED_BITMAPS;
                            // fall through
                        case RECEIVE_HUFFMAN_USED_BITMAPS:
                            Bzip2BlockDecompressor blockDecompressor = this.blockDecompressor;
                            final int inUse16 = blockDecompressor.huffmanInUse16;
                            final int bitNumber = Integer.bitCount(inUse16);
                            final byte[] huffmanSymbolMap = blockDecompressor.huffmanSymbolMap;

                            if (!reader.hasReadableBits(bitNumber * HUFFMAN_SYMBOL_RANGE_SIZE + 3)) {
                                return null;
                            }

                            int huffmanSymbolCount = 0;
                            if (bitNumber > 0) {
                                for (int i = 0; i < 16; i++) {
                                    if ((inUse16 & 1 << 15 >>> i) != 0) {
                                        for (int j = 0, k = i << 4; j < HUFFMAN_SYMBOL_RANGE_SIZE; j++, k++) {
                                            if (reader.readBoolean()) {
                                                huffmanSymbolMap[huffmanSymbolCount++] = (byte) k;
                                            }
                                        }
                                    }
                                }
                            }
                            blockDecompressor.huffmanEndOfBlockSymbol = huffmanSymbolCount + 1;

                            int totalTables = reader.readBits(3);
                            if (totalTables < HUFFMAN_MINIMUM_TABLES || totalTables > HUFFMAN_MAXIMUM_TABLES) {
                                throw new DecompressionException("incorrect huffman groups number");
                            }
                            int alphaSize = huffmanSymbolCount + 2;
                            if (alphaSize > HUFFMAN_MAX_ALPHABET_SIZE) {
                                throw new DecompressionException("incorrect alphabet size");
                            }
                            huffmanStageDecoder = new Bzip2HuffmanStageDecoder(reader, totalTables, alphaSize);
                            currentState = State.RECEIVE_SELECTORS_NUMBER;
                            // fall through
                        case RECEIVE_SELECTORS_NUMBER:
                            if (!reader.hasReadableBits(15)) {
                                return null;
                            }
                            int totalSelectors = reader.readBits(15);
                            if (totalSelectors < 1 || totalSelectors > MAX_SELECTORS) {
                                currentState = State.EOF;
                                throw new DecompressionException("incorrect selectors number");
                            }
                            huffmanStageDecoder.selectors = new byte[totalSelectors];

                            currentState = State.RECEIVE_SELECTORS;
                            // fall through
                        case RECEIVE_SELECTORS:
                            Bzip2HuffmanStageDecoder huffmanStageDecoder = this.huffmanStageDecoder;
                            byte[] selectors = huffmanStageDecoder.selectors;
                            totalSelectors = selectors.length;
                            final Bzip2MoveToFrontTable tableMtf = huffmanStageDecoder.tableMTF;

                            int currSelector;
                            // Get zero-terminated bit runs (0..62) of MTF'ed Huffman table. length = 1..6
                            for (currSelector = huffmanStageDecoder.currentSelector;
                                 currSelector < totalSelectors; currSelector++) {
                                if (!reader.hasReadableBits(HUFFMAN_SELECTOR_LIST_MAX_LENGTH)) {
                                    // Save state if end of current ByteBuf was reached
                                    huffmanStageDecoder.currentSelector = currSelector;
                                    return null;
                                }
                                int index = 0;
                                while (reader.readBoolean()) {
                                    index++;
                                }
                                selectors[currSelector] = tableMtf.indexToFront(index);
                            }

                            currentState = State.RECEIVE_HUFFMAN_LENGTH;
                            // fall through
                        case RECEIVE_HUFFMAN_LENGTH:
                            huffmanStageDecoder = this.huffmanStageDecoder;
                            totalTables = huffmanStageDecoder.totalTables;
                            final byte[][] codeLength = huffmanStageDecoder.tableCodeLengths;
                            alphaSize = huffmanStageDecoder.alphabetSize;

                            /* Now the coding tables */
                            int currGroup;
                            int currLength = huffmanStageDecoder.currentLength;
                            int currAlpha = 0;
                            boolean modifyLength = huffmanStageDecoder.modifyLength;
                            boolean saveStateAndReturn = false;
                            loop: for (currGroup = huffmanStageDecoder.currentGroup;
                                       currGroup < totalTables; currGroup++) {
                                // start_huffman_length
                                if (!reader.hasReadableBits(5)) {
                                    saveStateAndReturn = true;
                                    break;
                                }
                                if (currLength < 0) {
                                    currLength = reader.readBits(5);
                                }
                                for (currAlpha = huffmanStageDecoder.currentAlpha; currAlpha < alphaSize; currAlpha++) {
                                    // delta_bit_length: 1..40
                                    if (!reader.isReadable()) {
                                        saveStateAndReturn = true;
                                        break loop;
                                    }
                                    while (modifyLength || reader.readBoolean()) {  // 0=>next symbol; 1=>alter length
                                        if (!reader.isReadable()) {
                                            modifyLength = true;
                                            saveStateAndReturn = true;
                                            break loop;
                                        }
                                        // 1=>decrement length;  0=>increment length
                                        currLength += reader.readBoolean() ? -1 : 1;
                                        modifyLength = false;
                                        if (!reader.isReadable()) {
                                            saveStateAndReturn = true;
                                            break loop;
                                        }
                                    }
                                    codeLength[currGroup][currAlpha] = (byte) currLength;
                                }
                                currLength = -1;
                                currAlpha = huffmanStageDecoder.currentAlpha = 0;
                                modifyLength = false;
                            }
                            if (saveStateAndReturn) {
                                // Save state if end of current ByteBuf was reached
                                huffmanStageDecoder.currentGroup = currGroup;
                                huffmanStageDecoder.currentLength = currLength;
                                huffmanStageDecoder.currentAlpha = currAlpha;
                                huffmanStageDecoder.modifyLength = modifyLength;
                                return null;
                            }

                            // Finally create the Huffman tables
                            huffmanStageDecoder.createHuffmanDecodingTables();
                            currentState = State.DECODE_HUFFMAN_DATA;
                            // fall through
                        case DECODE_HUFFMAN_DATA:
                            blockDecompressor = this.blockDecompressor;
                            final int oldReaderIndex = in.readerIndex();
                            final boolean decoded = blockDecompressor.decodeHuffmanData(this.huffmanStageDecoder);
                            if (!decoded) {
                                return null;
                            }
                            // It used to avoid "Bzip2Decoder.decode() did not read anything but decoded a message"
                            // exception. Because previous operation may read only a few bits from
                            // Bzip2BitReader.bitBuffer and don't read incoming ByteBuf.
                            if (in.readerIndex() == oldReaderIndex && in.isReadable()) {
                                reader.refill();
                            }

                            final int blockLength = blockDecompressor.blockLength();
                            ByteBuf uncompressed = allocator.buffer(blockLength);
                            try {
                                int uncByte;
                                while ((uncByte = blockDecompressor.read()) >= 0) {
                                    uncompressed.writeByte(uncByte);
                                }
                                // We did read all the data, lets reset the state and do the CRC check.
                                currentState = State.INIT_BLOCK;
                                int currentBlockCRC = blockDecompressor.checkCRC();
                                streamCRC = (streamCRC << 1 | streamCRC >>> 31) ^ currentBlockCRC;

                                // Return here so the ByteBuf that was put in the List will be forwarded to the user
                                // and so can be released as soon as possible.
                                ByteBuf data = uncompressed;
                                uncompressed = null;
                                return data;
                            } finally {
                                if (uncompressed != null) {
                                    uncompressed.release();
                                }
                            }
                        case EOF:
                            return Unpooled.EMPTY_BUFFER;
                        default:
                            throw new IllegalStateException();
                    }
                }
        }
    }

    /**
     * Returns {@code true} if and only if the end of the compressed stream
     * has been reached.
     */
    @Override
    public boolean isFinished() {
        return currentState == State.EOF || currentState == State.CLOSED;
    }

    @Override
    public void close() {
        currentState = State.CLOSED;
    }

    @Override
    public boolean isClosed() {
        return currentState == State.CLOSED;
    }
}
