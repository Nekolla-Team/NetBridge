package top.tangge233.netbridge.channel;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.nativebridge.*;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

public class NativeChannel extends AbstractChannel {

    public static final int MAX_IO_BYTES = 65536;

    private static final ChannelMetadata METADATA = new ChannelMetadata(
            false,
            16
    );
    private static final int MAX_READS_PER_POLL = 16;
    private static final int MAX_DRAIN_ROUNDS = 4;

    private final NativeConnection connection;
    private final ChannelConfig config = new DefaultChannelConfig(this);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean pendingWake = new AtomicBoolean();
    private final AtomicBoolean backpressured = new AtomicBoolean();
    private final AtomicReference<NativeConnectionState> nativeState =
            new AtomicReference<>(NativeConnectionState.CONNECTING);
    private final AtomicReference<NativeFailureReason> failureReason =
            new AtomicReference<>(NativeFailureReason.GENERIC);
    private volatile boolean connected;
    private volatile boolean readRequested;
    private volatile @Nullable InetSocketAddress remoteAddress;
    private volatile @Nullable ChannelPromise connectPromise;

    public NativeChannel(NativeConnection connection) {
        super(null);
        this.connection = connection;
        this.nativeState.set(snapshotState());
        connection.setListener(new NativeConnectionListener() {
            @Override
            public void onStateChanged(NativeConnectionState state) {
                onStateChanged(state, NativeFailureReason.GENERIC);
            }

            @Override
            public void onStateChanged(NativeConnectionState state, NativeFailureReason reason) {
                nativeState.set(state);
                failureReason.set(reason);
                marshal(NativeChannel.this::applyNativeState);
            }

            @Override
            public void onDataAvailable() {
                wakeupRead();
            }

            @Override
            public void onWritable() {
                marshal(NativeChannel.this::onNativeWritable);
            }
        });
    }

    private NativeConnectionState snapshotState() {
        try {
            return connection.state();
        } catch (RuntimeException e) {
            return NativeConnectionState.CLOSED;
        }
    }

    public long connId() {
        return connection.id();
    }

    public void setRemoteAddress(@Nullable InetSocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new NativeUnsafe();
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true;
    }

    @Override
    protected SocketAddress localAddress0() {
        return new InetSocketAddress(0);
    }

    @Override
    protected @Nullable SocketAddress remoteAddress0() {
        return remoteAddress;
    }

    @Override
    protected void doRegister() throws Exception {
        super.doRegister();
        eventLoop().execute(this::reconcileAfterRegister);
    }

    @Override
    protected void doBind(SocketAddress localAddress) {
    }

    @Override
    protected void doDisconnect() {
        doClose();
    }

    @Override
    protected void doClose() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        var p = connectPromise;
        connectPromise = null;
        if (p != null && !p.isDone()) {
            p.tryFailure(new ClosedChannelException());
        }
        try {
            connection.close();
        } catch (RuntimeException e) {
            NetBridge.LOGGER.warn(
                    "Error closing native connection {}: {}",
                    connection.id(),
                    e.getMessage()
            );
        }
    }

    @Override
    protected void doBeginRead() {
        readRequested = true;
        if (connected && !closed.get()) {
            drain();
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        if (!dataWritable()) {
            return;
        }

        var maxMessages = maxMessagesPerWrite();
        var written = 0;
        while (written < maxMessages && in.current() != null) {
            var r = writeMessage(in, in.current());
            if (r != 1) {
                break;
            }
            written++;
        }
    }

    private boolean dataWritable() {
        return !closed.get()
                && (connected || connection.transport() == NativeTransportKind.KCP);
    }

    private int writeMessage(ChannelOutboundBuffer in, Object msg) {
        if (!(msg instanceof ByteBuf buf)) {
            in.remove(new UnsupportedOperationException(
                    "unsupported message type: " + msg.getClass()
            ));
            return 1;
        }

        var readable = buf.readableBytes();
        if (readable == 0) {
            in.remove();
            return 1;
        }

        while (buf.isReadable()) {
            var take = Math.min(buf.readableBytes(), MAX_IO_BYTES);
            var res = writeChunk(buf, take);

            if (res.closed()) {
                in.remove();
                unsafe().close(voidPromise());
                return -1;
            }

            if (res.wouldBlock()) {
                backpressured.set(true);
                unsafe().outboundBuffer().setUserDefinedWritability(1, false);
                return 0;
            }

            backpressured.set(false);
            if (unsafe().outboundBuffer() != null) {
                unsafe().outboundBuffer().setUserDefinedWritability(1, true);
            }
        }
        in.remove();
        return 1;
    }

    private NativeIoResult writeChunk(
            ByteBuf buf,
            int take
    ) {
        var index = buf.readerIndex();
        if (buf.nioBufferCount() == 1) {
            return writeNio(
                    buf,
                    buf.nioBuffer(index, take)
            );
        }

        var scratch = alloc().ioBuffer(take, take);
        try {
            buf.getBytes(
                    index,
                    scratch,
                    take
            );
            return writeNio(
                    buf,
                    scratch.nioBuffer(0, take)
            );
        } finally {
            scratch.release();
        }
    }

    private NativeIoResult writeNio(ByteBuf buf, ByteBuffer nio) {
        var res = connection.write(nio);
        if (res.progressed() && res.bytes() > 0) {
            buf.skipBytes(res.bytes());
        }
        return res;
    }

    private void onNativeWritable() {
        backpressured.set(false);
        if (unsafe().outboundBuffer() != null) {
            unsafe().outboundBuffer().setUserDefinedWritability(1, true);
        }
        if (connected && !closed.get()) {
            unsafe().flush();
        }
    }

    private void applyNativeState() {
        if (closed.get()) {
            return;
        }

        var state = nativeState.get();
        switch (state) {
            case CONNECTED -> {
                if (!connected) {
                    completeConnect();
                }
            }
            case FAILED -> {
                if (!connected) {
                    var reason = failureReason.get();
                    var msg = "handshake failed: %s (conn %d)".formatted(
                            reason.description(),
                            connection.id()
                    );
                    var exc = new NativeConnectException(reason, msg);
                    failConnect(exc);
                    pipeline().fireExceptionCaught(exc);
                    unsafe().close(voidPromise());
                } else {
                    unsafe().close(voidPromise());
                }
            }
            case CLOSED -> {
                if (!connected) {
                    failConnect(new ClosedChannelException());
                }
                unsafe().close(voidPromise());
            }
            case CONNECTING -> {
            }
        }
    }

    private void reconcileAfterRegister() {
        var st = snapshotState();
        nativeState.set(st);
        applyNativeState();
        if (connected && !closed.get()) {
            drain();
        }
    }

    private void completeConnect() {
        connected = true;
        var p = connectPromise;
        connectPromise = null;
        if (p != null && !p.isDone()) {
            p.trySuccess();
        }
        pipeline().fireChannelActive();
        unsafe().flush();
        drain();
    }

    public void wakeupRead() {
        if (!pendingWake.compareAndSet(false, true)) {
            return;
        }
        marshal(this::drain);
    }

    private void drain() {
        pendingWake.set(false);
        if (closed.get() || !connected) {
            return;
        }

        var any = true;
        var rounds = 0;
        while (any && rounds < MAX_DRAIN_ROUNDS) {
            if (!(config().isAutoRead() || readRequested)) {
                break;
            }

            readRequested = false;
            any = readNow();
            rounds++;
            if (pendingWake.get()) {
                pendingWake.set(false);
            }
        }

        if (any || pendingWake.get()) {
            try {
                eventLoop().execute(this::drain);
            } catch (RejectedExecutionException e) {
                pendingWake.set(false);
            }
        }
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return !closed.get();
    }

    @Override
    public boolean isActive() {
        return connected && isOpen();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    public void abortConnect(Throwable cause) {
        marshal(() -> {
            failConnect(cause);
            unsafe().close(voidPromise());
        });
    }

    private void marshal(Runnable action) {
        if (closed.get() || !isRegistered()) {
            return;
        }

        var loop = eventLoop();

        try {
            loop.execute(action);
        } catch (RejectedExecutionException e) {
            pendingWake.set(false);
        }
    }

    private void failConnect(Throwable cause) {
        var p = connectPromise;
        connectPromise = null;
        if (p != null && !p.isDone()) {
            p.tryFailure(cause);
        }
    }

    private boolean readNow() {
        var any = false;
        for (var reads = 0; reads < MAX_READS_PER_POLL; reads++) {
            var buf = alloc().ioBuffer(MAX_IO_BYTES);
            try {
                var n = readInto(buf);
                if (n < 0) {
                    unsafe().close(voidPromise());
                    break;
                }

                if (n == 0) {
                    break;
                }

                buf.writerIndex(buf.writerIndex() + n);
                pipeline().fireChannelRead(buf);
                buf = null;
                any = true;
                if (n < MAX_IO_BYTES) {
                    break;
                }
            } finally {
                if (buf != null) {
                    buf.release();
                }
            }
        }

        if (any) {
            pipeline().fireChannelReadComplete();
        }

        return any;
    }

    private int readInto(ByteBuf buf) {
        var nio = buf.nioBuffer(
                buf.writerIndex(),
                buf.writableBytes()
        );
        NativeIoResult res;
        if (nio != null && nio.isDirect()) {
            res = connection.read(nio);
        } else {
            res = connection.read(nio != null
                    ? nio
                    : ByteBuffer.allocateDirect(buf.writableBytes())
            );
        }

        return res.closed()
                ? -1
                : res.progressed()
                        ? res.bytes()
                        : 0;
    }

    private final class NativeUnsafe extends AbstractUnsafe {

        @Override
        public void connect(
                SocketAddress remoteAddress,
                @Nullable SocketAddress localAddress,
                ChannelPromise promise
        ) {
            if (!isOpen()) {
                promise.tryFailure(new ClosedChannelException());
                return;
            }

            if (isActive()) {
                promise.tryFailure(new ConnectException("already connected"));
                return;
            }

            NativeChannel.this.remoteAddress = (InetSocketAddress) remoteAddress;
            NativeChannel.this.connectPromise = promise;
            eventLoop().execute(() -> {
                nativeState.set(snapshotState());
                applyNativeState();
                if (connected) {
                    unsafe().flush();
                }
            });
        }

    }

}
