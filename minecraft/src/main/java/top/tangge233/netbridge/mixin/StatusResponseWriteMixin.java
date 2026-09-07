package top.tangge233.netbridge.mixin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.ability.StatusNetworksCodec;
import top.tangge233.netbridge.runtime.NetBridgeServices;

import java.nio.charset.StandardCharsets;

@SuppressWarnings({"UnusedMethod", "UnusedVariable"})
@Mixin(ClientboundStatusResponsePacket.class)
public abstract class StatusResponseWriteMixin {

    @Unique
    private static final int MAX_STATUS_JSON = 262144;

    @Redirect(
            method = "write(Lnet/minecraft/network/FriendlyByteBuf;)V",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/network/FriendlyByteBuf;writeJsonWithCodec(Lcom/mojang/serialization/Codec;Ljava/lang/Object;)V"
            )
    )
    private <T> void netbridge$injectNetworks(FriendlyByteBuf buf, Codec<T> codec, T value) {
        var serverRuntime = NetBridgeServices.serverRuntime();
        String json = null;
        if (value instanceof ServerStatus status
                && serverRuntime.isRunning()
        ) {
            @SuppressWarnings("unchecked")
            var statusCodec = (Codec<ServerStatus>) codec;
            json = statusCodec
                    .encodeStart(JsonOps.INSTANCE, status)
                    .getOrThrow(msg ->
                            new IllegalStateException("Failed to encode status: " + msg)
                    )
                    .toString();
        }
        if (json != null) {
            var injected = StatusNetworksCodec.addNetworks(
                    json,
                    serverRuntime.announcement()
            );
            if (injected.getBytes(StandardCharsets.UTF_8).length <= MAX_STATUS_JSON) {
                buf.writeUtf(injected, MAX_STATUS_JSON);
                return;
            }

            NetBridge.LOGGER.warn(
                    "networks injection dropped: status json {} chars + networks exceeds {} limit",
                    json.length(),
                    MAX_STATUS_JSON
            );
        }
        buf.writeJsonWithCodec(codec, value);
    }

}
