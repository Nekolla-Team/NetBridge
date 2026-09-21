package team.nekolla.netbridge.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import team.nekolla.netbridge.NetBridge;
import team.nekolla.netbridge.config.ConfigPaths;
import team.nekolla.netbridge.runtime.NetBridgeServices;

public class NetBridgeFabricMod implements ModInitializer {

    public static final String MOD_ID = "net_bridge";

    @Override
    public void onInitialize() {
        var paths = new ConfigPaths(
                FabricLoader.getInstance()
                        .getConfigDir()
                        .resolve("net-bridge")
        );
        NetBridgeServices.bootstrap(paths);

        if (!NetBridgeServices.nativeAvailable()) {
            NetBridge.LOGGER.error(
                    "net-bridge native unavailable; accelerated transports disabled (TCP fallback)"
            );
        }
    }

}
