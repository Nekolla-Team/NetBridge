package top.tangge233.netbridge.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.tangge233.netbridge.client.ConnectionSnapshot;
import top.tangge233.netbridge.runtime.NetBridgeServices;
import top.tangge233.netbridge.transport.TransportMode;

@SuppressWarnings({"UnusedMethod", "UnusedVariable"})
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin extends Screen {

    protected ConnectScreenMixin(Component title) {
        super(title);
    }

    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At("TAIL")
    )
    private void netbridge$drawStatusLine(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick,
            CallbackInfo ci
    ) {
        var snapshot = NetBridgeServices.clientRuntime().snapshot();
        if (snapshot.phase() == ConnectionSnapshot.Phase.IDLE
                || snapshot.phase() == ConnectionSnapshot.Phase.CONNECTED
        ) {
            return;
        }

        Component text;
        if (snapshot.phase() == ConnectionSnapshot.Phase.CONNECTING) {
            var mode = snapshot.requestedMode();
            var modeKey = mode == null
                    ? TransportMode.TCP.configValue()
                    : mode.configValue();
            text = Component.translatable(
                    "netbridge.connect.connecting",
                    Component.translatable("netbridge.transport." + modeKey)
            );
        } else {
            text = Component.translatable("netbridge.connect.falling_back");
        }

        graphics.drawCenteredString(
                this.font,
                text,
                this.width / 2,
                this.height / 2 - 65,
                0xFFFFFF
        );
    }

}
