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
package io.netty5.channel.epoll;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty5.buffer.api.Buffer;
import io.netty5.buffer.api.BufferAllocator;
import io.netty5.buffer.api.DefaultBufferAllocators;
import io.netty5.buffer.api.Resource;
import io.netty5.buffer.api.StandardAllocationTypes;
import io.netty5.channel.AbstractChannel;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelConfig;
import io.netty5.channel.ChannelException;
import io.netty5.channel.ChannelMetadata;
import io.netty5.channel.ChannelOutboundBuffer;
import io.netty5.channel.ConnectTimeoutException;
import io.netty5.channel.EventLoop;
import io.netty5.channel.RecvBufferAllocator.Handle;
import io.netty5.channel.socket.ChannelInputShutdownEvent;
import io.netty5.channel.socket.ChannelInputShutdownReadComplete;
import io.netty5.channel.socket.SocketChannelConfig;
import io.netty5.channel.unix.FileDescriptor;
import io.netty5.channel.unix.IovArray;
import io.netty5.channel.unix.Socket;
import io.netty5.channel.unix.UnixChannel;
import io.netty5.util.ReferenceCountUtil;
import io.netty5.util.concurrent.Future;
import io.netty5.util.concurrent.Promise;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.TimeUnit;

import static io.netty5.channel.internal.ChannelUtils.WRITE_STATUS_SNDBUF_FULL;
import static io.netty5.channel.unix.UnixChannelUtil.computeRemoteAddr;
import static java.util.Objects.requireNonNull;

abstract class AbstractEpollChannel extends AbstractChannel implements UnixChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false);
    final LinuxSocket socket;
    /**
     * The future of the current connection attempt.  If not null, subsequent
     * connection attempts will fail.
     */
    private Promise<Void> connectPromise;
    private Future<?> connectTimeoutFuture;
    private SocketAddress requestedRemoteAddress;
    protected EpollRegistration registration;

    private volatile SocketAddress local;
    private volatile SocketAddress remote;

    protected int flags = Native.EPOLLET;
    boolean inputClosedSeenErrorOnRead;
    boolean epollInReadyRunnablePending;

    protected volatile boolean active;

    AbstractEpollChannel(EventLoop eventLoop, LinuxSocket fd) {
        this(null, eventLoop, fd, false);
    }

    AbstractEpollChannel(Channel parent, EventLoop eventLoop, LinuxSocket fd, boolean active) {
        super(parent, eventLoop);
        socket = requireNonNull(fd, "fd");
        this.active = active;
        if (active) {
            // Directly cache the remote and local addresses
            // See https://github.com/netty/netty/issues/2359
            local = fd.localAddress();
            remote = fd.remoteAddress();
        }
    }

    AbstractEpollChannel(Channel parent, EventLoop eventLoop, LinuxSocket fd, SocketAddress remote) {
        super(parent, eventLoop);
        socket = requireNonNull(fd, "fd");
        active = true;
        // Directly cache the remote and local addresses
        // See https://github.com/netty/netty/issues/2359
        this.remote = remote;
        local = fd.localAddress();
    }

    static boolean isSoErrorZero(Socket fd) {
        try {
            return fd.getSoError() == 0;
        } catch (IOException e) {
            throw new ChannelException(e);
        }
    }

    void setFlag(int flag) throws IOException {
        if (!isFlagSet(flag)) {
            flags |= flag;
            modifyEvents();
        }
    }

    void clearFlag(int flag) throws IOException {
        if (isFlagSet(flag)) {
            flags &= ~flag;
            modifyEvents();
        }
    }

    EpollRegistration registration() {
        assert registration != null;
        return registration;
    }

    boolean isFlagSet(int flag) {
        return (flags & flag) != 0;
    }

    @Override
    public final FileDescriptor fd() {
        return socket;
    }

    @Override
    public abstract EpollChannelConfig config();

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    protected void doClose() throws Exception {
        active = false;
        // Even if we allow half closed sockets we should give up on reading. Otherwise we may allow a read attempt on a
        // socket which has not even been connected yet. This has been observed to block during unit tests.
        inputClosedSeenErrorOnRead = true;
        try {
            Promise<Void> promise = connectPromise;
            if (promise != null) {
                // Use tryFailure() instead of setFailure() to avoid the race against cancel().
                promise.tryFailure(new ClosedChannelException());
                connectPromise = null;
            }

            Future<?> future = connectTimeoutFuture;
            if (future != null) {
                future.cancel();
                connectTimeoutFuture = null;
            }

            if (isRegistered()) {
                // Need to check if we are on the EventLoop as doClose() may be triggered by the GlobalEventExecutor
                // if SO_LINGER is used.
                //
                // See https://github.com/netty/netty/issues/7159
                EventLoop loop = executor();
                if (loop.inEventLoop()) {
                    doDeregister();
                } else {
                    loop.execute(() -> {
                        try {
                            doDeregister();
                        } catch (Throwable cause) {
                            pipeline().fireExceptionCaught(cause);
                        }
                    });
                }
            }
        } finally {
            socket.close();
        }
    }

    void resetCachedAddresses() {
        local = socket.localAddress();
        remote = socket.remoteAddress();
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    public boolean isOpen() {
        return socket.isOpen();
    }

    void register0(EpollRegistration registration) throws Exception {
        // Just in case the previous EventLoop was shutdown abruptly, or an event is still pending on the old EventLoop
        // make sure the epollInReadyRunnablePending variable is reset so we will be able to execute the Runnable on the
        // new EventLoop.
        epollInReadyRunnablePending = false;
        this.registration = registration;
    }

    void deregister0() throws Exception {
        if (registration != null) {
            registration.remove();
        }
    }

    @Override
    protected final void doBeginRead() throws Exception {
        // Channel.read() or ChannelHandlerContext.read() was called
        final AbstractEpollUnsafe unsafe = (AbstractEpollUnsafe) unsafe();
        unsafe.readPending = true;

        // We must set the read flag here as it is possible the user didn't read in the last read loop, the
        // executeEpollInReadyRunnable could read nothing, and if the user doesn't explicitly call read they will
        // never get data after this.
        setFlag(Native.EPOLLIN);

        // If EPOLL ET mode is enabled and auto read was toggled off on the last read loop then we may not be notified
        // again if we didn't consume all the data. So we force a read operation here if there maybe more data.
        if (unsafe.maybeMoreDataToRead) {
            unsafe.executeEpollInReadyRunnable(config());
        }
    }

    final boolean shouldBreakEpollInReady(ChannelConfig config) {
        return socket.isInputShutdown() && (inputClosedSeenErrorOnRead || !isAllowHalfClosure(config));
    }

    private static boolean isAllowHalfClosure(ChannelConfig config) {
        if (config instanceof EpollDomainSocketChannelConfig) {
            return ((EpollDomainSocketChannelConfig) config).isAllowHalfClosure();
        }
        return config instanceof SocketChannelConfig &&
                ((SocketChannelConfig) config).isAllowHalfClosure();
    }

    final void clearEpollIn() {
        // Only clear if registered with an EventLoop as otherwise
        if (isRegistered()) {
            final EventLoop loop = executor();
            final AbstractEpollUnsafe unsafe = (AbstractEpollUnsafe) unsafe();
            if (loop.inEventLoop()) {
                unsafe.clearEpollIn0();
            } else {
                // schedule a task to clear the EPOLLIN as it is not safe to modify it directly
                loop.execute(() -> {
                    if (!unsafe.readPending && !config().isAutoRead()) {
                        // Still no read triggered so clear it now
                        unsafe.clearEpollIn0();
                    }
                });
            }
        } else  {
            // The EventLoop is not registered atm so just update the flags so the correct value
            // will be used once the channel is registered
            flags &= ~Native.EPOLLIN;
        }
    }

    private void modifyEvents() throws IOException {
        if (isOpen() && isRegistered() && registration != null) {
            registration.update();
        }
    }

    @Override
    protected abstract AbstractEpollUnsafe newUnsafe();

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, and releases the original one.
     */
    protected final ByteBuf newDirectBuffer(ByteBuf buf) {
        return newDirectBuffer(buf, buf);
    }

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, and releases the specified holder.
     * The caller must ensure that the holder releases the original {@link ByteBuf} when the holder is released by
     * this method.
     */
    protected final ByteBuf newDirectBuffer(Object holder, ByteBuf buf) {
        final int readableBytes = buf.readableBytes();
        if (readableBytes == 0) {
            ReferenceCountUtil.release(holder);
            return Unpooled.EMPTY_BUFFER;
        }

        final ByteBufAllocator alloc = alloc();
        if (alloc.isDirectBufferPooled()) {
            return newDirectBuffer0(holder, buf, alloc, readableBytes);
        }

        final ByteBuf directBuf = ByteBufUtil.threadLocalDirectBuffer();
        if (directBuf == null) {
            return newDirectBuffer0(holder, buf, alloc, readableBytes);
        }

        directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
        ReferenceCountUtil.safeRelease(holder);
        return directBuf;
    }

    private static ByteBuf newDirectBuffer0(Object holder, ByteBuf buf, ByteBufAllocator alloc, int capacity) {
        final ByteBuf directBuf = alloc.directBuffer(capacity);
        directBuf.writeBytes(buf, buf.readerIndex(), capacity);
        ReferenceCountUtil.safeRelease(holder);
        return directBuf;
    }

    /**
     * Returns an off-heap copy of, and then closes, the given {@link Buffer}.
     */
    protected final Buffer newDirectBuffer(Buffer buf) {
        return newDirectBuffer(buf, buf);
    }

    /**
     * Returns an off-heap copy of the given {@link Buffer}, and then closes the {@code holder} under the assumption
     * that it owned (or was itself) the buffer.
     */
    protected final Buffer newDirectBuffer(Resource<?> holder, Buffer buf) {
        BufferAllocator allocator = bufferAllocator();
        if (allocator.getAllocationType() != StandardAllocationTypes.OFF_HEAP) {
            allocator = DefaultBufferAllocators.offHeapAllocator();
        }
        try (holder) {
            int readableBytes = buf.readableBytes();
            Buffer directCopy = allocator.allocate(readableBytes);
            if (readableBytes > 0) {
                directCopy.writeBytes(buf);
            }
            return directCopy;
        }
    }

    protected static void checkResolvable(InetSocketAddress addr) {
        if (addr.isUnresolved()) {
            throw new UnresolvedAddressException();
        }
    }

    /**
     * Read bytes into the given {@link ByteBuf} and return the amount.
     */
    protected final int doReadBytes(ByteBuf byteBuf) throws Exception {
        int writerIndex = byteBuf.writerIndex();
        int localReadAmount;
        unsafe().recvBufAllocHandle().attemptedBytesRead(byteBuf.writableBytes());
        if (byteBuf.hasMemoryAddress()) {
            localReadAmount = socket.readAddress(byteBuf.memoryAddress(), writerIndex, byteBuf.capacity());
        } else {
            ByteBuffer buf = byteBuf.internalNioBuffer(writerIndex, byteBuf.writableBytes());
            localReadAmount = socket.read(buf, buf.position(), buf.limit());
        }
        if (localReadAmount > 0) {
            byteBuf.writerIndex(writerIndex + localReadAmount);
        }
        return localReadAmount;
    }

    /**
     * Read bytes into the given {@link Buffer} and return the amount.
     */
    protected final void doReadBytes(Buffer buffer) throws Exception {
        unsafe().recvBufAllocHandle().attemptedBytesRead(buffer.writableBytes());
        buffer.forEachWritable(0, (index, component) -> {
            long address = component.writableNativeAddress();
            assert address != 0;
            int localReadAmount = socket.readAddress(address, 0, component.writableBytes());
            unsafe().recvBufAllocHandle().lastBytesRead(localReadAmount);
            if (localReadAmount > 0) {
                component.skipWritable(localReadAmount);
            }
            return false;
        });
    }

    protected final int doWriteBytes(ChannelOutboundBuffer in, ByteBuf buf) throws Exception {
        if (buf.hasMemoryAddress()) {
            int localFlushedAmount = socket.writeAddress(buf.memoryAddress(), buf.readerIndex(), buf.writerIndex());
            if (localFlushedAmount > 0) {
                in.removeBytes(localFlushedAmount);
                return 1;
            }
        } else {
            final ByteBuffer nioBuf = buf.nioBufferCount() == 1 ?
                    buf.internalNioBuffer(buf.readerIndex(), buf.readableBytes()) : buf.nioBuffer();
            int localFlushedAmount = socket.write(nioBuf, nioBuf.position(), nioBuf.limit());
            if (localFlushedAmount > 0) {
                nioBuf.position(nioBuf.position() + localFlushedAmount);
                in.removeBytes(localFlushedAmount);
                return 1;
            }
        }
        return WRITE_STATUS_SNDBUF_FULL;
    }

    protected final int doWriteBytes(ChannelOutboundBuffer in, Buffer buf) throws Exception {
        int initialReadableBytes = buf.readableBytes();
        buf.forEachReadable(0, (index, component) -> {
            long address = component.readableNativeAddress();
            assert address != 0;
            int written = socket.writeAddress(address, 0, component.readableBytes());
            if (written > 0) {
                component.skipReadable(written);
            }
            return false;
        });
        int readableBytesLeft = buf.readableBytes();
        if (readableBytesLeft < initialReadableBytes) {
            int bytesWritten = initialReadableBytes - readableBytesLeft;
            buf.skipReadable(-bytesWritten); // Restore read offset for ChannelOutboundBuffer.
            in.removeBytes(bytesWritten);
            return 1; // Some data was written to the socket.
        }
        return WRITE_STATUS_SNDBUF_FULL;
    }

    /**
     * Write bytes to the socket, with or without a remote address.
     * Used for datagram and TCP client fast open writes.
     */
    final long doWriteOrSendBytes(ByteBuf data, InetSocketAddress remoteAddress, boolean fastOpen)
            throws IOException {
        assert !(fastOpen && remoteAddress == null) : "fastOpen requires a remote address";
        if (data.hasMemoryAddress()) {
            long memoryAddress = data.memoryAddress();
            if (remoteAddress == null) {
                return socket.writeAddress(memoryAddress, data.readerIndex(), data.writerIndex());
            }
            return socket.sendToAddress(memoryAddress, data.readerIndex(), data.writerIndex(),
                    remoteAddress.getAddress(), remoteAddress.getPort(), fastOpen);
        }

        if (data.nioBufferCount() > 1) {
            IovArray array = registration().cleanIovArray();
            array.add(data, data.readerIndex(), data.readableBytes());
            int cnt = array.count();
            assert cnt != 0;

            if (remoteAddress == null) {
                return socket.writevAddresses(array.memoryAddress(0), cnt);
            }
            return socket.sendToAddresses(array.memoryAddress(0), cnt,
                    remoteAddress.getAddress(), remoteAddress.getPort(), fastOpen);
        }

        ByteBuffer nioData = data.internalNioBuffer(data.readerIndex(), data.readableBytes());
        if (remoteAddress == null) {
            return socket.write(nioData, nioData.position(), nioData.limit());
        }
        return socket.sendTo(nioData, nioData.position(), nioData.limit(),
                remoteAddress.getAddress(), remoteAddress.getPort(), fastOpen);
    }

    /**
     * Write bytes to the socket, with or without a remote address.
     * Used for datagram and TCP client fast open writes.
     */
    final long doWriteOrSendBytes(Buffer data, InetSocketAddress remoteAddress, boolean fastOpen)
            throws IOException {
        assert !(fastOpen && remoteAddress == null) : "fastOpen requires a remote address";

        IovArray array = registration().cleanIovArray();
        data.forEachReadable(0, array);
        int count = array.count();
        assert count != 0;
        if (remoteAddress == null) {
            return socket.writevAddresses(array.memoryAddress(0), count);
        }
        return socket.sendToAddresses(array.memoryAddress(0), count,
                                      remoteAddress.getAddress(), remoteAddress.getPort(), fastOpen);
    }

    protected abstract class AbstractEpollUnsafe extends AbstractUnsafe {
        boolean readPending;
        boolean maybeMoreDataToRead;
        private EpollRecvBufferAllocatorHandle allocHandle;
        private final Runnable epollInReadyRunnable = new Runnable() {
            @Override
            public void run() {
                epollInReadyRunnablePending = false;
                epollInReady();
            }
        };

        /**
         * Called once EPOLLIN event is ready to be processed
         */
        abstract void epollInReady();

        final void epollInBefore() {
            maybeMoreDataToRead = false;
        }

        final void epollInFinally(ChannelConfig config) {
            maybeMoreDataToRead = allocHandle.maybeMoreDataToRead();

            if (allocHandle.isReceivedRdHup() || readPending && maybeMoreDataToRead) {
                // trigger a read again as there may be something left to read and because of epoll ET we
                // will not get notified again until we read everything from the socket
                //
                // It is possible the last fireChannelRead call could cause the user to call read() again, or if
                // autoRead is true the call to channelReadComplete would also call read, but maybeMoreDataToRead is set
                // to false before every read operation to prevent re-entry into epollInReady() we will not read from
                // the underlying OS again unless the user happens to call read again.
                executeEpollInReadyRunnable(config);
            } else if (!readPending && !config.isAutoRead()) {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                clearEpollIn();
            }
        }

        final void executeEpollInReadyRunnable(ChannelConfig config) {
            if (epollInReadyRunnablePending || !isActive() || shouldBreakEpollInReady(config)) {
                return;
            }
            epollInReadyRunnablePending = true;
            executor().execute(epollInReadyRunnable);
        }

        /**
         * Called once EPOLLRDHUP event is ready to be processed
         */
        final void epollRdHupReady() {
            // This must happen before we attempt to read. This will ensure reading continues until an error occurs.
            recvBufAllocHandle().receivedRdHup();

            if (isActive()) {
                // If it is still active, we need to call epollInReady as otherwise we may miss to
                // read pending data from the underlying file descriptor.
                // See https://github.com/netty/netty/issues/3709
                epollInReady();
            } else {
                // Just to be safe make sure the input marked as closed.
                shutdownInput(true);
            }

            // Clear the EPOLLRDHUP flag to prevent continuously getting woken up on this event.
            clearEpollRdHup();
        }

        /**
         * Clear the {@link Native#EPOLLRDHUP} flag from EPOLL, and close on failure.
         */
        private void clearEpollRdHup() {
            try {
                clearFlag(Native.EPOLLRDHUP);
            } catch (IOException e) {
                pipeline().fireExceptionCaught(e);
                close(newPromise());
            }
        }

        /**
         * Shutdown the input side of the channel.
         */
        void shutdownInput(boolean rdHup) {
            if (!socket.isInputShutdown()) {
                if (isAllowHalfClosure(config())) {
                    try {
                        socket.shutdown(true, false);
                    } catch (IOException ignored) {
                        // We attempted to shutdown and failed, which means the input has already effectively been
                        // shutdown.
                        fireEventAndClose(ChannelInputShutdownEvent.INSTANCE);
                        return;
                    } catch (NotYetConnectedException ignore) {
                        // We attempted to shutdown and failed, which means the input has already effectively been
                        // shutdown.
                    }
                    clearEpollIn();
                    pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                } else {
                    close(newPromise());
                }
            } else if (!rdHup) {
                inputClosedSeenErrorOnRead = true;
                pipeline().fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
            }
        }

        private void fireEventAndClose(Object evt) {
            pipeline().fireUserEventTriggered(evt);
            close(newPromise());
        }

        @Override
        public EpollRecvBufferAllocatorHandle recvBufAllocHandle() {
            if (allocHandle == null) {
                allocHandle = newEpollHandle(super.recvBufAllocHandle());
            }
            return allocHandle;
        }

        /**
         * Create a new {@link EpollRecvBufferAllocatorHandle} instance.
         * @param handle The handle to wrap with EPOLL specific logic.
         */
        EpollRecvBufferAllocatorHandle newEpollHandle(Handle handle) {
            return new EpollRecvBufferAllocatorHandle(handle);
        }

        @Override
        protected final void flush0() {
            // Flush immediately only when there's no pending flush.
            // If there's a pending flush operation, event loop will call forceFlush() later,
            // and thus there's no need to call it now.
            if (!isFlagSet(Native.EPOLLOUT)) {
                super.flush0();
            }
        }

        /**
         * Called once a EPOLLOUT event is ready to be processed
         */
        final void epollOutReady() {
            if (connectPromise != null) {
                // pending connect which is now complete so handle it.
                finishConnect();
            } else if (!socket.isOutputShutdown()) {
                // directly call super.flush0() to force a flush now
                super.flush0();
            }
        }

        protected final void clearEpollIn0() {
            assert executor().inEventLoop();
            try {
                readPending = false;
                clearFlag(Native.EPOLLIN);
            } catch (IOException e) {
                // When this happens there is something completely wrong with either the filedescriptor or epoll,
                // so fire the exception through the pipeline and close the Channel.
                pipeline().fireExceptionCaught(e);
                unsafe().close(newPromise());
            }
        }

        @Override
        public void connect(
                final SocketAddress remoteAddress, final SocketAddress localAddress, final Promise<Void> promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            try {
                if (connectPromise != null) {
                    throw new ConnectionPendingException();
                }

                boolean wasActive = isActive();
                if (doConnect(remoteAddress, localAddress)) {
                    fulfillConnectPromise(promise, wasActive);
                } else {
                    connectPromise = promise;
                    requestedRemoteAddress = remoteAddress;

                    // Schedule connect timeout.
                    int connectTimeoutMillis = config().getConnectTimeoutMillis();
                    if (connectTimeoutMillis > 0) {
                        connectTimeoutFuture = executor().schedule(() -> {
                            Promise<Void> connectPromise = AbstractEpollChannel.this.connectPromise;
                            if (connectPromise != null && !connectPromise.isDone()
                                    && connectPromise.tryFailure(new ConnectTimeoutException(
                                    "connection timed out: " + remoteAddress))) {
                                close(newPromise());
                            }
                        }, connectTimeoutMillis, TimeUnit.MILLISECONDS);
                    }

                    promise.asFuture().addListener(future -> {
                        if (future.isCancelled()) {
                            if (connectTimeoutFuture != null) {
                                connectTimeoutFuture.cancel();
                            }
                            connectPromise = null;
                            close(newPromise());
                        }
                    });
                }
            } catch (Throwable t) {
                closeIfClosed();
                promise.tryFailure(annotateConnectException(t, remoteAddress));
            }
        }

        private void fulfillConnectPromise(Promise<Void> promise, boolean wasActive) {
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }
            active = true;

            // Get the state as trySuccess() may trigger an ChannelFutureListeners that will close the Channel.
            // We still need to ensure we call fireChannelActive() in this case.
            boolean active = isActive();

            // trySuccess() will return false if a user cancelled the connection attempt.
            boolean promiseSet = promise.trySuccess(null);

            // Regardless if the connection attempt was cancelled, channelActive() event should be triggered,
            // because what happened is what happened.
            if (!wasActive && active) {
                pipeline().fireChannelActive();
                readIfIsAutoRead();
            }

            // If a user cancelled the connection attempt, close the channel, which is followed by channelInactive().
            if (!promiseSet) {
                close(newPromise());
            }
        }

        private void fulfillConnectPromise(Promise<Void> promise, Throwable cause) {
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }

            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            promise.tryFailure(cause);
            closeIfClosed();
        }

        private void finishConnect() {
            // Note this method is invoked by the event loop only if the connection attempt was
            // neither cancelled nor timed out.

            assert executor().inEventLoop();

            boolean connectStillInProgress = false;
            try {
                boolean wasActive = isActive();
                if (!doFinishConnect()) {
                    connectStillInProgress = true;
                    return;
                }
                fulfillConnectPromise(connectPromise, wasActive);
            } catch (Throwable t) {
                fulfillConnectPromise(connectPromise, annotateConnectException(t, requestedRemoteAddress));
            } finally {
                if (!connectStillInProgress) {
                    // Check for null as the connectTimeoutFuture is only created if a connectTimeoutMillis > 0 is used
                    // See https://github.com/netty/netty/issues/1770
                    if (connectTimeoutFuture != null) {
                        connectTimeoutFuture.cancel();
                    }
                    connectPromise = null;
                }
            }
        }

        /**
         * Finish the connect
         */
        private boolean doFinishConnect() throws Exception {
            if (socket.finishConnect()) {
                clearFlag(Native.EPOLLOUT);
                if (requestedRemoteAddress instanceof InetSocketAddress) {
                    remote = computeRemoteAddr((InetSocketAddress) requestedRemoteAddress, socket.remoteAddress());
                }
                requestedRemoteAddress = null;

                return true;
            }
            setFlag(Native.EPOLLOUT);
            return false;
        }
    }

    @Override
    protected void doBind(SocketAddress local) throws Exception {
        if (local instanceof InetSocketAddress) {
            checkResolvable((InetSocketAddress) local);
        }
        socket.bind(local);
        this.local = socket.localAddress();
    }

    /**
     * Connect to the remote peer
     */
    protected boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        if (localAddress instanceof InetSocketAddress) {
            checkResolvable((InetSocketAddress) localAddress);
        }

        InetSocketAddress remoteSocketAddr = remoteAddress instanceof InetSocketAddress
                ? (InetSocketAddress) remoteAddress : null;
        if (remoteSocketAddr != null) {
            checkResolvable(remoteSocketAddr);
        }

        if (remote != null) {
            // Check if already connected before trying to connect. This is needed as connect(...) will not return -1
            // and set errno to EISCONN if a previous connect(...) attempt was setting errno to EINPROGRESS and finished
            // later.
            throw new AlreadyConnectedException();
        }

        if (localAddress != null) {
            socket.bind(localAddress);
        }

        boolean connected = doConnect0(remoteAddress);
        if (connected) {
            remote = remoteSocketAddr == null ?
                    remoteAddress : computeRemoteAddr(remoteSocketAddr, socket.remoteAddress());
        }
        // We always need to set the localAddress even if not connected yet as the bind already took place.
        //
        // See https://github.com/netty/netty/issues/3463
        local = socket.localAddress();
        return connected;
    }

    boolean doConnect0(SocketAddress remote) throws Exception {
        boolean success = false;
        try {
            boolean connected = socket.connect(remote);
            if (!connected) {
                setFlag(Native.EPOLLOUT);
            }
            success = true;
            return connected;
        } finally {
            if (!success) {
                doClose();
            }
        }
    }

    @Override
    protected SocketAddress localAddress0() {
        return local;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return remote;
    }
}
