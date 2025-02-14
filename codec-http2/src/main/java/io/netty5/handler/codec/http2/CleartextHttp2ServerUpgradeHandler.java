/*
 * Copyright 2017 The Netty Project
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
package io.netty5.handler.codec.http2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.ByteToMessageDecoder;
import io.netty5.handler.codec.http.HttpServerCodec;
import io.netty5.handler.codec.http.HttpServerUpgradeHandler;
import io.netty5.util.internal.UnstableApi;

import static io.netty.buffer.Unpooled.unreleasableBuffer;
import static io.netty5.handler.codec.ByteBufToBufferHandler.BYTEBUF_TO_BUFFER_HANDLER;
import static io.netty5.handler.codec.http2.Http2CodecUtil.connectionPrefaceBuf;
import static java.util.Objects.requireNonNull;

/**
 * Performing cleartext upgrade, by h2c HTTP upgrade or Prior Knowledge.
 * This handler config pipeline for h2c upgrade when handler added.
 * And will update pipeline once it detect the connection is starting HTTP/2 by
 * prior knowledge or not.
 */
@UnstableApi
public final class CleartextHttp2ServerUpgradeHandler extends ByteToMessageDecoder {
    private static final ByteBuf CONNECTION_PREFACE = unreleasableBuffer(connectionPrefaceBuf()).asReadOnly();

    private final HttpServerCodec httpServerCodec;
    private final HttpServerUpgradeHandler<?> httpServerUpgradeHandler;
    private final ChannelHandler http2ServerHandler;

    /**
     * Creates the channel handler provide cleartext HTTP/2 upgrade from HTTP
     * upgrade or prior knowledge
     *
     * @param httpServerCodec the http server codec
     * @param httpServerUpgradeHandler the http server upgrade handler for HTTP/2
     * @param http2ServerHandler the http2 server handler, will be added into pipeline
     *                           when starting HTTP/2 by prior knowledge
     */
    public CleartextHttp2ServerUpgradeHandler(HttpServerCodec httpServerCodec,
                                              HttpServerUpgradeHandler<?> httpServerUpgradeHandler,
                                              ChannelHandler http2ServerHandler) {
        this.httpServerCodec = requireNonNull(httpServerCodec, "httpServerCodec");
        this.httpServerUpgradeHandler = requireNonNull(httpServerUpgradeHandler, "httpServerUpgradeHandler");
        this.http2ServerHandler = requireNonNull(http2ServerHandler, "http2ServerHandler");
    }

    @Override
    public void handlerAdded0(ChannelHandlerContext ctx) throws Exception {
        ctx.pipeline()
                .addAfter(ctx.name(), null, httpServerUpgradeHandler)
                .addAfter(ctx.name(), null, httpServerCodec)
                .addAfter(ctx.name(), null, BYTEBUF_TO_BUFFER_HANDLER);
    }

    /**
     * Peek inbound message to determine current connection wants to start HTTP/2
     * by HTTP upgrade or prior knowledge
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        int prefaceLength = CONNECTION_PREFACE.readableBytes();
        int bytesRead = Math.min(in.readableBytes(), prefaceLength);

        if (!ByteBufUtil.equals(CONNECTION_PREFACE, CONNECTION_PREFACE.readerIndex(),
                in, in.readerIndex(), bytesRead)) {
            ctx.pipeline().remove(this);
        } else if (bytesRead == prefaceLength) {
            // Full h2 preface match, removed source codec, using http2 codec to handle
            // following network traffic
            ctx.pipeline()
                    .remove(httpServerCodec)
                    .remove(httpServerUpgradeHandler);

            ctx.pipeline().addAfter(ctx.name(), null, http2ServerHandler);
            ctx.fireUserEventTriggered(PriorKnowledgeUpgradeEvent.INSTANCE);

            ctx.pipeline().remove(this);
        }
    }

    /**
     * User event that is fired to notify about HTTP/2 protocol is started.
     */
    public static final class PriorKnowledgeUpgradeEvent {
        private static final PriorKnowledgeUpgradeEvent INSTANCE = new PriorKnowledgeUpgradeEvent();

        private PriorKnowledgeUpgradeEvent() {
        }
    }
}
