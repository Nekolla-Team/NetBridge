package top.tangge233.netbridge.mixin;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import top.tangge233.netbridge.ability.StatusNetworksCapture;
import top.tangge233.netbridge.ability.StatusNetworksCodec;

@SuppressWarnings({"UnusedMethod", "UnusedVariable"})
@Mixin(ClientboundStatusResponsePacket.class)
public abstract class ClientboundStatusResponsePacketMixin {

    @Redirect(
            method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/network/FriendlyByteBuf;readJsonWithCodec(Lcom/mojang/serialization/Codec;)Ljava/lang/Object;"
            )
    )
    private static <T> T netbridge$captureNetworks(
            FriendlyByteBuf buf,
            Codec<T> codec
    ) {
        var json = buf.readUtf();
        StatusNetworksCapture.capture(StatusNetworksCodec.parse(json));
        var element = JsonParser.parseString(json);
        return codec.parse(JsonOps.INSTANCE, element)
                .getOrThrow(message ->
                        new DecoderException("Failed to decode json: " + message)
                );
    }

}
