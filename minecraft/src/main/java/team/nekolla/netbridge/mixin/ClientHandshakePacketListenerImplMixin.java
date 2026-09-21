package team.nekolla.netbridge.mixin;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import team.nekolla.netbridge.mc.benchmark.MinecraftBenchmarkRecorder;

/**
 * Records {@code LOGIN_COMPLETE} once the server has accepted the login and the game profile has
 * been received — a protocol/state transition, not a UI or packet-entry hook (B-007).
 */
@SuppressWarnings({"UnusedMethod", "UnusedVariable"})
@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class ClientHandshakePacketListenerImplMixin {

    @Inject(
            method = "handleGameProfile",
            at = @At("TAIL")
    )
    private void netbridge$recordLoginComplete(
            ClientboundGameProfilePacket packet,
            CallbackInfo ci
    ) {
        if (MinecraftBenchmarkRecorder.enabled()) {
            MinecraftBenchmarkRecorder.get().loginComplete();
        }
    }

}
