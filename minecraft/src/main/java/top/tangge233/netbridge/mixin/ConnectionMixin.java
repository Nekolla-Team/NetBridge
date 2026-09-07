package top.tangge233.netbridge.mixin;

import io.netty.channel.ChannelFuture;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.client.AccelerationInterceptionScope;
import top.tangge233.netbridge.mc.NativeClientTransport;
import top.tangge233.netbridge.runtime.NetBridgeServices;

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
            CallbackInfoReturnable<ChannelFuture> cir
    ) {
        if (AccelerationInterceptionScope.isVanillaConnectBypass()
                || AccelerationInterceptionScope.isAcceleratedConnectInProgress()
        ) {
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

}
