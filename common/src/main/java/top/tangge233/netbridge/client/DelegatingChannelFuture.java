package top.tangge233.netbridge.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import top.tangge233.netbridge.NetBridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

final class DelegatingChannelFuture implements ChannelFuture {

    private final AtomicReference<@Nullable ChannelFuture> delegate = new AtomicReference<>();
    private final AtomicBoolean terminal = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Object lock = new Object();
    private final List<GenericFutureListener<? extends Future<? super Void>>> listeners = new ArrayList<>();
    private final List<ScheduledFuture<?>> scheduledTasks = new ArrayList<>();

    DelegatingChannelFuture(EventLoopGroup _executorGroup) {
    }

    void registerScheduledTask(ScheduledFuture<?> task) {
        synchronized (lock) {
            if (cancelled.get() || terminal.get()) {
                task.cancel(false);
                return;
            }
            scheduledTasks.add(task);
        }
    }

    void setDelegate(ChannelFuture future, boolean isTerminal) {
        if (cancelled.get()) {
            future.cancel(true);
            return;
        }
        delegate.set(future);
        if (isTerminal && terminal.compareAndSet(false, true)) {
            future.addListener(ignored -> fireListeners());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void fireListeners() {
        List<GenericFutureListener<? extends Future<? super Void>>> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(listeners);
            listeners.clear();
        }
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

    @Override
    public Channel channel() {
        var cur = current();
        if (cur == null) {
            throw new IllegalStateException("Channel not yet initialized on delegating future");
        }

        return cur.channel();
    }

    private @Nullable ChannelFuture current() {
        return delegate.get();
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ChannelFuture addListener(
            GenericFutureListener<? extends Future<? super Void>> listener
    ) {
        var shouldInvokeImmediately = false;
        synchronized (lock) {
            if (terminal.get()) {
                shouldInvokeImmediately = true;
            } else {
                listeners.add(listener);
            }
        }

        if (shouldInvokeImmediately) {
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

        if (isCancelled()) {
            throw new CancellationException();
        }

        if (!isSuccess()) {
            var cause = cause();
            throw cause == null
                    ? new IllegalStateException("delegating future failed")
                    : new IllegalStateException(cause);
        }

        return this;
    }

    @Override
    public ChannelFuture syncUninterruptibly() {
        awaitUninterruptibly();
        if (isCancelled()) {
            throw new CancellationException();
        }

        if (!isSuccess()) {
            var cause = cause();
            throw cause == null
                    ? new IllegalStateException("delegating future failed")
                    : new IllegalStateException(cause);
        }

        return this;
    }

    @Override
    public ChannelFuture await() throws InterruptedException {
        while (!isDone()) {
            var cur = current();
            if (cur != null) {
                cur.await(20, TimeUnit.MILLISECONDS);
            } else {
                Thread.sleep(10);
            }
        }

        return this;
    }

    @Override
    public ChannelFuture awaitUninterruptibly() {
        while (!isDone()) {
            var cur = current();
            if (cur != null) {
                cur.awaitUninterruptibly(20, TimeUnit.MILLISECONDS);
            } else {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException _) {
                    // ignore
                }
            }
        }
        return this;
    }

    @Override
    public boolean isVoid() {
        return false;
    }

    @Override
    public boolean isCancelled() {
        var cur = current();
        return cancelled.get()
                || (terminal.get() && cur != null && cur.isCancelled());
    }

    @Override
    public boolean isSuccess() {
        var cur = current();
        return !cancelled.get()
                && terminal.get()
                && cur != null
                && cur.isSuccess();
    }

    @Override
    public boolean isCancellable() {
        var cur = current();
        return !terminal.get()
                && (cur == null || cur.isCancellable());
    }

    @Override
    public @Nullable Throwable cause() {
        if (cancelled.get()) {
            return new CancellationException();
        }
        var cur = current();
        return terminal.get() && cur != null && cur.isDone()
                ? cur.cause()
                : null;
    }

    @Override
    public boolean await(
            long timeout,
            TimeUnit unit
    ) throws InterruptedException {
        var deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!isDone()) {
            if (System.nanoTime() > deadline) {
                return isDone();
            }

            var cur = current();
            if (cur != null) {
                cur.await(20, TimeUnit.MILLISECONDS);
            } else {
                Thread.sleep(10);
            }
        }

        return true;
    }

    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
        return await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
        var deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!isDone()) {
            if (System.nanoTime() > deadline) {
                return isDone();
            }

            var cur = current();
            if (cur != null) {
                cur.awaitUninterruptibly(20, TimeUnit.MILLISECONDS);
            } else {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException _) {
                    // uninterruptible loop
                }
            }
        }

        return true;
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
        if (terminal.get()) {
            return false;
        }

        if (cancelled.compareAndSet(false, true)) {
            synchronized (lock) {
                scheduledTasks.forEach(task ->
                        task.cancel(mayInterruptIfRunning)
                );
                scheduledTasks.clear();
            }
            var cur = current();
            if (cur != null) {
                cur.cancel(mayInterruptIfRunning);
            }
            if (terminal.compareAndSet(false, true)) {
                fireListeners();
            }
            return true;
        }

        return false;
    }

    @Override
    public boolean isDone() {
        var cur = current();
        return cancelled.get()
                || (terminal.get() && cur != null && cur.isDone());
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

    public boolean isAttemptCancelled() {
        return cancelled.get();
    }

}
