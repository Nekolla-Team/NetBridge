package top.tangge233.netbridge.mixin;

import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.tangge233.netbridge.ability.StatusNetworksCapture;
import top.tangge233.netbridge.runtime.NetBridgeServices;

import java.net.InetSocketAddress;

@SuppressWarnings({"UnusedMethod", "UnusedVariable"})
@Mixin(targets = "net.minecraft.client.multiplayer.ServerStatusPinger$1")
public abstract class ServerStatusPingerResponseMixin {

    @Final @Shadow(remap = false)
    Connection val$connection;
    @Final @Shadow(remap = false)
    ServerData val$data;

    @Inject(
            method = "handleStatusResponse",
            at = @At("RETURN")
    )
    private void netbridge$recordNetworks(CallbackInfo ci) {
        var networks = StatusNetworksCapture.take();
        var remote = this.val$connection.getRemoteAddress();
        if (remote instanceof InetSocketAddress inetSocketAddress) {
            NetBridgeServices.clientRuntime().recordServerCapabilities(
                    inetSocketAddress,
                    networks
            );
        }

        if (networks.hasUsableAccelerated()) {
            Component tag = Component
                    .translatable("netbridge.status.accelerated_tag")
                    .withStyle(ChatFormatting.AQUA);
            this.val$data.motd = Component.empty()
                    .append(this.val$data.motd)
                    .append(tag);
        }
    }

}
