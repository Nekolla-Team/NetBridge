package top.tangge233.netbridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global logging entry point for net-bridge. All modules use {@link #LOGGER} so diagnostics can be
 * filtered by the "net-bridge" prefix in logs/latest.log.
 */
public final class NetBridge {

    public static final Logger LOGGER = LoggerFactory.getLogger("net-bridge");

    private NetBridge() {
    }

}
