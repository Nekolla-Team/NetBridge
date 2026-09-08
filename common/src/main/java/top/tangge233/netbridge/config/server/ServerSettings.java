package top.tangge233.netbridge.config.server;

/**
 * Collection of server transport settings.
 *
 * @param quic QUIC transport settings
 * @param kcp  KCP transport settings
 */
public record ServerSettings(
        ServerTransportSettings quic,
        ServerTransportSettings kcp
) {

    public static ServerSettings defaults() {
        return new ServerSettings(
                ServerTransportSettings.defaultQuic(),
                ServerTransportSettings.defaultKcp()
        );
    }

}
