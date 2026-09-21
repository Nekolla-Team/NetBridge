package team.nekolla.netbridge.fabric.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import team.nekolla.netbridge.mc.NativeServerTransport;
import team.nekolla.netbridge.runtime.NetBridgeServices;

@Mixin(MinecraftServer.class)
@SuppressWarnings({"UnusedMethod", "UnusedVariable"})
public class MinecraftServerLifecycleMixin {

    @Inject(
            method = "runServer",
            at = @At("HEAD")
    )
    private void netbridge$startAcceptors(CallbackInfo ci) {
        var self = (MinecraftServer) (Object) this;
        var serverRuntime = NetBridgeServices.serverRuntime();
        serverRuntime.setAdopter((connection, generation) ->
                NativeServerTransport.adopt(self, connection, generation)
        );
        serverRuntime.start(
                self.getPort(),
                self.getLocalIp()
        );
    }

    @Inject(
            method = "close()V",
            remap = false,
            at = @At("HEAD")
    )
    private void netbridge$stopAcceptors(CallbackInfo ci) {
        var serverRuntime = NetBridgeServices.serverRuntime();
        serverRuntime.stop();
        serverRuntime.setAdopter(null);
    }

}
