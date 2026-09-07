package top.tangge233.netbridge.client;

import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.transport.KcpProfile;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("DataFlowIssue")
class ConnectionPlanValueTest {

    @Test
    @SuppressWarnings("NullAway")
    void plansRequireTcpAddress() {
        assertThrows(
                NullPointerException.class,
                () -> new TcpPlan(null)
        );
        assertThrows(
                NullPointerException.class,
                () -> new AcceleratedPlan(null, new QuicAttempt(addr(1)))
        );
    }

    private static InetSocketAddress addr(int port) {
        return new InetSocketAddress("203.0.113.1", port);
    }

    @Test
    @SuppressWarnings("NullAway")
    void acceleratedPlanRequiresAttempt() {
        assertThrows(
                NullPointerException.class,
                () -> new AcceleratedPlan(addr(1), null)
        );
    }

    @Test
    @SuppressWarnings("NullAway")
    void attemptsRequireEndpoint() {
        assertThrows(
                NullPointerException.class,
                () -> new QuicAttempt(null)
        );
        assertThrows(
                NullPointerException.class,
                () -> new KcpAttempt(null, KcpProfile.BALANCE)
        );
    }

    @Test
    @SuppressWarnings("NullAway")
    void kcpAttemptRequiresProfile() {
        assertThrows(
                NullPointerException.class,
                () -> new KcpAttempt(addr(1), null)
        );
    }

    @Test
    void planVariantsExposeTcpAddress() {
        assertEquals(
                addr(25565),
                new TcpPlan(addr(25565)).tcpAddress()
        );
        var accel = new AcceleratedPlan(
                addr(25565),
                new QuicAttempt(addr(2443))
        );
        assertEquals(
                addr(25565),
                accel.tcpAddress()
        );
        assertInstanceOf(
                QuicAttempt.class,
                accel.nativeAttempt()
        );
    }

}
