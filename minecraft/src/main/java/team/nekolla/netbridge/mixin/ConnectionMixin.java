package team.nekolla.netbridge.mixin;

import io.netty.channel.ChannelFuture;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import team.nekolla.netbridge.NetBridge;
import team.nekolla.netbridge.client.AccelerationInterceptionScope;
import team.nekolla.netbridge.mc.NativeClientTransport;
import team.nekolla.netbridge.mc.benchmark.MinecraftBenchmarkRecorder;
import team.nekolla.netbridge.runtime.NetBridgeServices;

import java.net.InetSocketAddress;

@SuppressWarnings({"UnusedMethod", "UnusedVariable"})
@Mixin(Connection.class)
public abstract class ConnectionMixin {

    @Inject(
            method = "connect",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void netbridge$tryAccelerated(
            InetSocketAddress address,
            boolean useEpoll,
            Connection connection,
            CallbackInfoReturnable<? super ChannelFuture> cir
    ) {
        var bypass = AccelerationInterceptionScope.isVanillaConnectBypass()
                || AccelerationInterceptionScope.isAcceleratedConnectInProgress();
        if (MinecraftBenchmarkRecorder.enabled()) {
            if (bypass) {
                MinecraftBenchmarkRecorder.get().connectAttempt(address, "accelerated-fallback");
            } else {
                MinecraftBenchmarkRecorder.get().beginSession(address);
            }
        }

        if (bypass) {
            return;
        }

        if (!NetBridgeServices.clientRuntime().acceleratedRequested()) {
            NetBridge.LOGGER.info("Transport for {}: TCP (mode=tcp)", address);
            return;
        }

        cir.setReturnValue(
                AccelerationInterceptionScope.callWithAcceleratedConnectInProgress(
                        () -> NativeClientTransport.connectWithFallback(
                                address,
                                useEpoll,
                                connection
                        )
                )
        );
    }

    @Inject(
            method = "connect",
            at = @At("RETURN")
    )
    private static void netbridge$recordTransportConnected(
            InetSocketAddress address,
            boolean useEpoll,
            Connection connection,
            CallbackInfoReturnable<? extends ChannelFuture> cir
    ) {
        if (!MinecraftBenchmarkRecorder.enabled()) {
            return;
        }

        var future = cir.getReturnValue();
        future.addListener(result -> {
            if (result.isSuccess()) {
                MinecraftBenchmarkRecorder.get().transportConnected(address);
            }
        });
    }

}
