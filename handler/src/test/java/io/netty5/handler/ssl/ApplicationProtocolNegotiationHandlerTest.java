/*
 * Copyright 2020 The Netty Project
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
package io.netty5.handler.ssl;

import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.embedded.EmbeddedChannel;
import io.netty5.channel.socket.ChannelInputShutdownEvent;
import io.netty5.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.netty5.handler.ssl.CloseNotifyTest.assertCloseNotify;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class ApplicationProtocolNegotiationHandlerTest {

    @Test
    public void testRemoveItselfIfNoSslHandlerPresent() throws NoSuchAlgorithmException {
        ChannelHandler alpnHandler = new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
            @Override
            protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
                fail();
            }
        };

        SSLEngine engine = SSLContext.getDefault().createSSLEngine();
        // This test is mocked/simulated and doesn't go through full TLS handshake. Currently only JDK SSLEngineImpl
        // client mode will generate a close_notify.
        engine.setUseClientMode(true);

        EmbeddedChannel channel = new EmbeddedChannel(alpnHandler);
        String msg = "msg";
        String msg2 = "msg2";

        assertTrue(channel.writeInbound(msg));
        assertTrue(channel.writeInbound(msg2));
        assertNull(channel.pipeline().context(alpnHandler));
        assertEquals(msg, channel.readInbound());
        assertEquals(msg2, channel.readInbound());

        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    public void testHandshakeFailure() {
        ChannelHandler alpnHandler = new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
            @Override
            protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
                fail();
            }
        };

        EmbeddedChannel channel = new EmbeddedChannel(alpnHandler);
        SSLHandshakeException exception = new SSLHandshakeException("error");
        SslHandshakeCompletionEvent completionEvent = new SslHandshakeCompletionEvent(exception);
        channel.pipeline().fireUserEventTriggered(completionEvent);
        channel.pipeline().fireExceptionCaught(new DecoderException(exception));
        assertNull(channel.pipeline().context(alpnHandler));
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    public void testHandshakeSuccess() throws NoSuchAlgorithmException {
        testHandshakeSuccess0(false);
    }

    @Test
    public void testHandshakeSuccessWithSslHandlerAddedLater() throws NoSuchAlgorithmException {
        testHandshakeSuccess0(true);
    }

    private static void testHandshakeSuccess0(boolean addLater) throws NoSuchAlgorithmException {
        final AtomicBoolean configureCalled = new AtomicBoolean(false);
        ChannelHandler alpnHandler = new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
            @Override
            protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
                configureCalled.set(true);
                assertEquals(ApplicationProtocolNames.HTTP_1_1, protocol);
            }
        };

        SSLEngine engine = SSLContext.getDefault().createSSLEngine();
        // This test is mocked/simulated and doesn't go through full TLS handshake. Currently only JDK SSLEngineImpl
        // client mode will generate a close_notify.
        engine.setUseClientMode(true);

        EmbeddedChannel channel = new EmbeddedChannel();
        if (addLater) {
            channel.pipeline().addLast(alpnHandler);
            channel.pipeline().addFirst(new SslHandler(engine));
        } else {
            channel.pipeline().addLast(new SslHandler(engine));
            channel.pipeline().addLast(alpnHandler);
        }
        channel.pipeline().fireUserEventTriggered(SslHandshakeCompletionEvent.SUCCESS);
        assertNull(channel.pipeline().context(alpnHandler));
        // Should produce the close_notify messages
        channel.releaseOutbound();
        channel.close();
        assertCloseNotify(channel.readOutbound());
        channel.finishAndReleaseAll();
        assertTrue(configureCalled.get());
    }

    @Test
    public void testHandshakeSuccessButNoSslHandler() {
        ChannelHandler alpnHandler = new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
            @Override
            protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
                fail();
            }
        };
        final EmbeddedChannel channel = new EmbeddedChannel(alpnHandler);
        channel.pipeline().fireUserEventTriggered(SslHandshakeCompletionEvent.SUCCESS);
        assertNull(channel.pipeline().context(alpnHandler));
        assertThrows(IllegalStateException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                channel.finishAndReleaseAll();
            }
        });
    }

    @Test
    public void testBufferMessagesUntilHandshakeComplete() throws Exception {
        testBufferMessagesUntilHandshakeComplete(null);
    }

    @Test
    public void testBufferMessagesUntilHandshakeCompleteWithClose() throws Exception {
        testBufferMessagesUntilHandshakeComplete(ctx -> ctx.channel().close());
    }

    @Test
    public void testBufferMessagesUntilHandshakeCompleteWithInputShutdown() throws Exception {
        testBufferMessagesUntilHandshakeComplete(ctx -> ctx.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE));
    }

    private static void testBufferMessagesUntilHandshakeComplete(
            final Consumer<ChannelHandlerContext> pipelineConfigurator)
            throws Exception {
        final AtomicReference<byte[]> channelReadData = new AtomicReference<byte[]>();
        final AtomicBoolean channelReadCompleteCalled = new AtomicBoolean(false);
        ChannelHandler alpnHandler = new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
            @Override
            protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
                assertEquals(ApplicationProtocolNames.HTTP_1_1, protocol);
                ctx.pipeline().addLast(new ChannelHandler() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        channelReadData.set((byte[]) msg);
                    }

                    @Override
                    public void channelReadComplete(ChannelHandlerContext ctx) {
                        channelReadCompleteCalled.set(true);
                    }
                });
                if (pipelineConfigurator != null) {
                    pipelineConfigurator.accept(ctx);
                }
            }
        };

        SSLEngine engine = SSLContext.getDefault().createSSLEngine();
        // This test is mocked/simulated and doesn't go through full TLS handshake. Currently only JDK SSLEngineImpl
        // client mode will generate a close_notify.
        engine.setUseClientMode(true);

        final byte[] someBytes = new byte[1024];

        EmbeddedChannel channel = new EmbeddedChannel(new SslHandler(engine), new ChannelHandler() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                if (evt == SslHandshakeCompletionEvent.SUCCESS) {
                    ctx.fireChannelRead(someBytes);
                }
                ctx.fireUserEventTriggered(evt);
            }
        }, alpnHandler);
        channel.pipeline().fireUserEventTriggered(SslHandshakeCompletionEvent.SUCCESS);
        assertNull(channel.pipeline().context(alpnHandler));
        assertArrayEquals(someBytes, channelReadData.get());
        assertTrue(channelReadCompleteCalled.get());
        assertNull(channel.readInbound());
        assertTrue(channel.finishAndReleaseAll());
    }
}
