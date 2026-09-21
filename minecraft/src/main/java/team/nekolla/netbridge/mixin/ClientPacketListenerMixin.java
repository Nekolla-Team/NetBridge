package team.nekolla.netbridge.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import team.nekolla.netbridge.mc.benchmark.MinecraftBenchmarkRecorder;

/**
 * Records {@code PLAY_ENTERED} when the play-state login packet is fully handled and chunk
 * milestones as level chunks arrive (B-007). Both hook protocol/state completion, not UI.
 */
@SuppressWarnings({"UnusedMethod", "UnusedVariable"})
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(
            method = "handleLogin",
            at = @At("TAIL")
    )
    private void netbridge$recordPlayEntered(
            ClientboundLoginPacket packet,
            CallbackInfo ci
    ) {
        if (MinecraftBenchmarkRecorder.enabled()) {
            MinecraftBenchmarkRecorder.get().playEntered();
        }
    }

    @Inject(
            method = "handleLevelChunkWithLight",
            at = @At("TAIL")
    )
    private void netbridge$recordChunk(
            ClientboundLevelChunkWithLightPacket packet,
            CallbackInfo ci
    ) {
        if (MinecraftBenchmarkRecorder.enabled()) {
            MinecraftBenchmarkRecorder.get().onClientChunk(packet.getX(), packet.getZ());
        }
    }

}
