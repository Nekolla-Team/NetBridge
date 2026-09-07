package top.tangge233.netbridge.config.client;

import top.tangge233.netbridge.transport.KcpProfile;
import top.tangge233.netbridge.transport.TransportMode;

import static java.util.Objects.requireNonNull;

/**
 * Immutable client configuration model.
 *
 * @param mode       Client acceleration mode (TCP / QUIC / KCP)
 * @param kcpProfile KCP parameter profile (BALANCE / AGGRESSIVE)
 */
public record ClientSettings(
        TransportMode mode,
        KcpProfile kcpProfile
) {

    public ClientSettings {
        requireNonNull(mode, "mode");
        requireNonNull(kcpProfile, "kcpProfile");
    }

    public static ClientSettings defaults() {
        return new ClientSettings(TransportMode.TCP, KcpProfile.BALANCE);
    }

}
