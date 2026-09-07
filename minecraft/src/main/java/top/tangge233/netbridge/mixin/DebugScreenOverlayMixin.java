package top.tangge233.netbridge.mixin;

import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.tangge233.netbridge.runtime.NetBridgeServices;

import java.util.List;

@SuppressWarnings({"UnusedMethod", "UnusedVariable"})
@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayMixin {

    @Inject(
            method = "getGameInformation",
            at = @At("RETURN")
    )
    private void netbridge$appendProtocolLine(CallbackInfoReturnable<List<String>> cir) {
        var line = NetBridgeServices.clientRuntime().snapshot().transportLine();
        if (line != null) {
            cir.getReturnValue().add("[net-bridge] " + line);
        }
    }

}
