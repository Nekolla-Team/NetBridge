package top.tangge233.netbridge.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.config.ConfigPaths;
import top.tangge233.netbridge.mc.NativeServerTransport;
import top.tangge233.netbridge.runtime.NetBridgeServices;

@Mod(NetBridgeNeoForgeMod.MOD_ID)
public class NetBridgeNeoForgeMod {

    public static final String MOD_ID = "net_bridge";

    public NetBridgeNeoForgeMod(IEventBus modBus) {
        var paths = new ConfigPaths(
                FMLPaths.CONFIGDIR.get().resolve("net-bridge")
        );
        NetBridgeServices.bootstrap(paths);

        if (!NetBridgeServices.nativeAvailable()) {
            NetBridge.LOGGER.error(
                    "net-bridge native unavailable; accelerated transports disabled (TCP fallback)"
            );
        }
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            var server = e.getServer();
            var serverRuntime = NetBridgeServices.serverRuntime();
            serverRuntime.setAdopter((connection, generation) -> NativeServerTransport.adopt(
                    server,
                    connection,
                    generation
            ));
            serverRuntime.start(
                    server.getPort(),
                    server.getLocalIp()
            );
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent _) -> {
            var serverRuntime = NetBridgeServices.serverRuntime();
            serverRuntime.stop();
            serverRuntime.setAdopter(null);
        });
    }

}
