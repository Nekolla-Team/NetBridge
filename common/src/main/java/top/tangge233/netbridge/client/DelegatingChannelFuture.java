package top.tangge233.netbridge.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import top.tangge233.netbridge.NetBridge;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import org.jspecify.annotations.Nullable;

final class DelegatingChannelFuture implements ChannelFuture {

    private final Object lock = new Object();
    private final List<GenericFutureListener<? extends Future<? super Void>>> listeners = new ArrayList<>();
    private final List<ScheduledFuture<?>> scheduledTasks = new ArrayList<>();
    private volatile State state = State.IN_PROGRESS;
    private volatile @Nullable Channel currentChannel;
    private volatile @Nullable ChannelFuture currentDelegate;
    private volatile @Nullable Throwable cause;

    DelegatingChannelFuture(EventLoopGroup _executorGroup) {
    }

    void registerScheduledTask(ScheduledFuture<?> task) {
        synchronized (lock) {
            if (state != State.IN_PROGRESS) {
                task.cancel(false);
                return;
            }
            scheduledTasks.add(task);
        }
    }

    void setDelegate(ChannelFuture future, boolean isTerminal) {
        if (isTerminal) {
            if (future.isDone()) {
                if (future.isSuccess()) {
                    completeSuccess(future.channel());
                } else {
                    completeFailure(future.cause(), future.channel());
                }
            } else {
                future.addListener(f -> {
                    if (f.isSuccess()) {
                        completeSuccess(future.channel());
                    } else {
                        completeFailure(f.cause(), future.channel());
                    }
                });
            }
        } else {
            onAttemptStarted(future.channel(), future);
        }
    }

    void completeSuccess(Channel channel) {
        List<GenericFutureListener<? extends Future<? super Void>>> snapshot;
        synchronized (lock) {
            if (state != State.IN_PROGRESS) {
                return;
            }

            this.currentChannel = channel;
            this.state = State.SUCCESS;
            lock.notifyAll();
            snapshot = new ArrayList<>(listeners);
            listeners.clear();
        }
        fireListeners(snapshot);
    }

    void completeFailure(
            @Nullable Throwable failureCause,
            @Nullable Channel channel
    ) {
        List<GenericFutureListener<? extends Future<? super Void>>> snapshot;
        synchronized (lock) {
            if (state != State.IN_PROGRESS) {
                return;
            }

            if (channel != null) {
                this.currentChannel = channel;
            }
            this.cause = failureCause != null
                    ? failureCause
                    : new ConnectException("connection failed");
            this.state = State.FAILED;
            lock.notifyAll();
            snapshot = new ArrayList<>(listeners);
            listeners.clear();
        }
        fireListeners(snapshot);
    }

    void onAttemptStarted(
            Channel channel,
            @Nullable ChannelFuture delegateFuture
    ) {
        registerAttempt(channel, delegateFuture);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void fireListeners(
            List<GenericFutureListener<? extends Future<? super Void>>> snapshot
    ) {
        for (var listener : snapshot) {
            try {
                var raw = (GenericFutureListener) listener;
                raw.operationComplete(this);
            } catch (Exception e) {
                NetBridge.LOGGER.warn(
                        "DelegatingChannelFuture listener failed: {}",
                        e.getMessage(),
                        e
                );
            }
        }
    }

    boolean registerAttempt(
            Channel channel,
            @Nullable ChannelFuture delegateFuture
    ) {
        synchronized (lock) {
            if (state != State.IN_PROGRESS) {
                try {
                    var _ = channel.close();
                } catch (Throwable _) {
                    // ignore
                }
                if (delegateFuture != null) {
                    delegateFuture.cancel(true);
                }
                return false;
            }
            this.currentChannel = channel;
            this.currentDelegate = delegateFuture;
            return true;
        }
    }

    @Override
    public Channel channel() {
        var cur = currentChannel;
        if (cur == null) {
            throw new IllegalStateException("Channel not yet initialized on delegating future");
        }
        return cur;
    }

    @Override
    public ChannelFuture addListener(
            GenericFutureListener<? extends Future<? super Void>> listener
    ) {
        var shouldInvokeImmediately = false;
        synchronized (lock) {
            if (state != State.IN_PROGRESS) {
                shouldInvokeImmediately = true;
            } else {
                listeners.add(listener);
            }
        }

        if (shouldInvokeImmediately) {
            fireListener(listener);
        }
        return this;
    }

    @SafeVarargs
    @Override
    public final ChannelFuture addListeners(
            GenericFutureListener<? extends Future<? super Void>>... listeners
    ) {
        Arrays.stream(listeners).forEachOrdered(this::addListener);
        return this;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void fireListener(GenericFutureListener<? extends Future<? super Void>> listener) {
        try {
            var raw = (GenericFutureListener) listener;
            raw.operationComplete(this);
        } catch (Exception e) {
            NetBridge.LOGGER.warn(
                    "DelegatingChannelFuture listener failed: {}",
                    e.getMessage(),
                    e
            );
        }
    }

    @Override
    public ChannelFuture removeListener(
            GenericFutureListener<? extends Future<? super Void>> listener
    ) {
        synchronized (lock) {
            listeners.remove(listener);
        }
        return this;
    }

    @SafeVarargs
    @Override
    public final ChannelFuture removeListeners(
            GenericFutureListener<? extends Future<? super Void>>... listeners
    ) {
        Arrays.stream(listeners).forEachOrdered(this::removeListener);
        return this;
    }

    @Override
    public ChannelFuture sync() throws InterruptedException {
        await();
        rethrowIfFailed();
        return this;
    }

    @Override
    public ChannelFuture syncUninterruptibly() {
        awaitUninterruptibly();
        rethrowIfFailed();
        return this;
    }

    @Override
    public ChannelFuture await() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        synchronized (lock) {
            while (state == State.IN_PROGRESS) {
                lock.wait();
            }
        }
        return this;
    }

    @Override
    public ChannelFuture awaitUninterruptibly() {
        var interrupted = false;
        try {
            synchronized (lock) {
                while (state == State.IN_PROGRESS) {
                    try {
                        lock.wait();
                    } catch (InterruptedException _) {
                        interrupted = true;
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return this;
    }

    @Override
    public boolean isVoid() {
        return false;
    }

    private void rethrowIfFailed() {
        if (state == State.CANCELLED) {
            throw new CancellationException();
        }

        if (state == State.FAILED) {
            var c = cause;
            if (c != null) {
                sneakyThrow(c);
            }
            throw new IllegalStateException("future failed without cause");
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    public boolean isAttemptCancelled() {
        return state == State.CANCELLED;
    }

    private enum State {

        IN_PROGRESS,
        SUCCESS,
        FAILED,
        CANCELLED

    }

    @Override
    public boolean isCancelled() {
        return state == State.CANCELLED;
    }

    @Override
    public boolean isSuccess() {
        return state == State.SUCCESS;
    }

    @Override
    public boolean isCancellable() {
        return state == State.IN_PROGRESS;
    }

    @Override
    public @Nullable Throwable cause() {
        return cause;
    }

    @Override
    public boolean await(
            long timeout,
            TimeUnit unit
    ) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }

        var nanos = unit.toNanos(timeout);
        var deadline = System.nanoTime() + nanos;
        synchronized (lock) {
            while (state == State.IN_PROGRESS) {
                if (nanos <= 0) {
                    return false;
                }

                var millis = TimeUnit.NANOSECONDS.toMillis(nanos);
                var remainderNanos = (int) (nanos - TimeUnit.MILLISECONDS.toNanos(millis));
                lock.wait(millis, remainderNanos);
                nanos = deadline - System.nanoTime();
            }
            return true;
        }
    }

    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
        return await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
        var nanos = unit.toNanos(timeout);
        var deadline = System.nanoTime() + nanos;
        var interrupted = false;
        try {
            synchronized (lock) {
                while (state == State.IN_PROGRESS) {
                    if (nanos <= 0) {
                        return false;
                    }
                    var millis = TimeUnit.NANOSECONDS.toMillis(nanos);
                    var remainderNanos = (int) (nanos - TimeUnit.MILLISECONDS.toNanos(millis));
                    try {
                        lock.wait(millis, remainderNanos);
                    } catch (InterruptedException _) {
                        interrupted = true;
                    }
                    nanos = deadline - System.nanoTime();
                }
                return true;
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean awaitUninterruptibly(long timeoutMillis) {
        return awaitUninterruptibly(
                timeoutMillis,
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    public @Nullable Void getNow() {
        return null;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        List<GenericFutureListener<? extends Future<? super Void>>> snapshot;
        Channel toClose;
        ChannelFuture delegateToCancel;
        List<ScheduledFuture<?>> tasksToCancel;

        synchronized (lock) {
            if (state != State.IN_PROGRESS) {
                return false;
            }
            state = State.CANCELLED;
            cause = new CancellationException();
            toClose = currentChannel;
            delegateToCancel = currentDelegate;
            tasksToCancel = new ArrayList<>(scheduledTasks);
            scheduledTasks.clear();
            lock.notifyAll();
            snapshot = new ArrayList<>(listeners);
            listeners.clear();
        }

        if (toClose != null) {
            try {
                var _ = toClose.close();
            } catch (Throwable _) {
                // ignore
            }
        }
        if (delegateToCancel != null) {
            delegateToCancel.cancel(mayInterruptIfRunning);
        }
        tasksToCancel.forEach(task ->
                task.cancel(mayInterruptIfRunning)
        );
        fireListeners(snapshot);
        return true;
    }

    @Override
    public boolean isDone() {
        return state != State.IN_PROGRESS;
    }

    @Override
    public @Nullable Void get() throws InterruptedException, ExecutionException {
        await();
        if (isCancelled()) {
            throw new CancellationException();
        }
        if (!isSuccess()) {
            throw new ExecutionException(cause());
        }
        return null;
    }

    @Override
    public @Nullable Void get(
            long timeout,
            TimeUnit unit
    ) throws InterruptedException, ExecutionException, TimeoutException {
        if (!await(timeout, unit)) {
            throw new TimeoutException();
        }
        if (isCancelled()) {
            throw new CancellationException();
        }
        if (!isSuccess()) {
            throw new ExecutionException(cause());
        }
        return null;
    }

}
