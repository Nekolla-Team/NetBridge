package top.tangge233.netbridge.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AccelerationInterceptionScopeTest {

    @Test
    void notBoundOutsideAnyScope() {
        assertFalse(AccelerationInterceptionScope.isVanillaConnectBypass());
        assertFalse(AccelerationInterceptionScope.isAcceleratedConnectInProgress());
    }

    @Test
    void vanillaBypassBoundInsideAndOnlyInside() {
        var result = AccelerationInterceptionScope.callWithVanillaConnectBypass(() -> {
            assertTrue(AccelerationInterceptionScope.isVanillaConnectBypass());
            assertFalse(AccelerationInterceptionScope.isAcceleratedConnectInProgress());
            return 42;
        });
        assertEquals(42, result);
        assertFalse(AccelerationInterceptionScope.isVanillaConnectBypass());
    }

    @Test
    void interceptionFlagBoundInsideAndOnlyInside() {
        AccelerationInterceptionScope.callWithAcceleratedConnectInProgress(() -> {
            assertTrue(AccelerationInterceptionScope.isAcceleratedConnectInProgress());
            assertFalse(AccelerationInterceptionScope.isVanillaConnectBypass());
            return Boolean.TRUE;
        });
        assertFalse(AccelerationInterceptionScope.isAcceleratedConnectInProgress());
    }

    @Test
    void nestedBypassStaysBound() {
        var innerSeen = new AtomicBoolean(false);
        AccelerationInterceptionScope.callWithVanillaConnectBypass(() -> {
            AccelerationInterceptionScope.callWithVanillaConnectBypass(() -> {
                innerSeen.set(AccelerationInterceptionScope.isVanillaConnectBypass());
                return Boolean.TRUE;
            });
            return Boolean.TRUE;
        });
        assertTrue(innerSeen.get(), "nested bypass call must remain bypassed");
    }

    @Test
    void exceptionUnwindsBinding() {
        assertThrows(
                IllegalStateException.class,
                () -> AccelerationInterceptionScope.callWithVanillaConnectBypass(() -> {
                    throw new IllegalStateException("boom");
                })
        );
        assertFalse(
                AccelerationInterceptionScope.isVanillaConnectBypass(),
                "binding must unwind when the operation throws"
        );
    }

    @Test
    void flagsAreIndependent() {
        AccelerationInterceptionScope.callWithVanillaConnectBypass(() -> {
            assertFalse(AccelerationInterceptionScope.isAcceleratedConnectInProgress());
            AccelerationInterceptionScope.callWithAcceleratedConnectInProgress(() -> {
                assertTrue(AccelerationInterceptionScope.isVanillaConnectBypass());
                assertTrue(AccelerationInterceptionScope.isAcceleratedConnectInProgress());
                return Boolean.TRUE;
            });
            assertTrue(AccelerationInterceptionScope.isVanillaConnectBypass());
            return Boolean.TRUE;
        });
    }

}
