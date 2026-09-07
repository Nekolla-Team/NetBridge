package top.tangge233.netbridge.mixin;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.tangge233.netbridge.runtime.NetBridgeServices;
import top.tangge233.netbridge.transport.TransportMode;

@SuppressWarnings({"UnusedMethod", "UnusedVariable"})
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {

    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(
            method = "init",
            at = @At("RETURN")
    )
    private void netbridge$addTransportButton(CallbackInfo ci) {
        var button =
                CycleButton.builder((TransportMode mode) ->
                                Component.translatable("netbridge.transport." + mode.configValue())
                                        .withStyle(
                                                mode == TransportMode.TCP
                                                        ? ChatFormatting.WHITE
                                                        : ChatFormatting.AQUA
                                        )
                        )
                        .withValues(TransportMode.values())
                        .withInitialValue(NetBridgeServices.clientSettings().current().mode())
                        .displayOnlyValue()
                        .create(
                                5,
                                6,
                                110,
                                20,
                                Component.translatable("netbridge.transport.mode"),
                                (_, mode) ->
                                        NetBridgeServices.clientSettings().updateMode(mode)
                        );
        button.setTooltip(Tooltip.create(
                Component.translatable("netbridge.transport.tooltip")
        ));
        this.addRenderableWidget(button);
    }

}
