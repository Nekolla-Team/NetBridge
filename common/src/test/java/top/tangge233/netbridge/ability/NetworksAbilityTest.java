package top.tangge233.netbridge.ability;

import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.transport.AcceleratedTransport;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * wire v2 status networks codec tests against the typed domain API.
 *
 * <p>Covers: missing/absent networks handling, enable defaults, host normalization,
 * port bounds and types, protocol matching, unknown-key tolerance, single-entry degradation,
 * malformed/oversized input safety, and injector semantics (no-overwrite, empty passthrough, field
 * preservation, unicode/escaping).
 */
class NetworksAbilityTest {

    private static final String QUIC_PROTO = AcceleratedTransport.QUIC.protocol();
    private static final String KCP_PROTO = AcceleratedTransport.KCP.protocol();

    private static final String QUIC_OK =
            "{\"enable\":true,\"host\":null,\"port\":25565,\"protocol\":\"%s\"}".formatted(
                    QUIC_PROTO
            );

    @Test
    void noNetworksPropertyYieldsEmpty() {
        assertEmpty("{\"version\":{\"name\":\"1.21.1\"},\"players\":{}}");
        assertEmpty("{}");
    }

    private static void assertEmpty(String json) {
        assertEquals(NetworksAbility.empty(), StatusNetworksCodec.parse(json));
    }

    @Test
    void emptyNetworksObjectYieldsEmpty() {
        assertEmpty(networks(""));
    }

    private static String networks(String body) {
        return "{\"networks\":{%s}}".formatted(body);
    }

    // --- parse: absence / structural shape ---

    @Test
    void networksNotObjectYieldsEmpty() {
        assertEmpty("{\"networks\":[]}");
        assertEmpty("{\"networks\":\"quic\"}");
        assertEmpty("{\"networks\":42}");
        assertEmpty("{\"networks\":null}");
    }

    @Test
    void rootNotObjectYieldsEmpty() {
        assertEmpty("[1,2,3]");
        assertEmpty("\"hello\"");
        assertEmpty("null");
        assertEmpty("42");
    }

    @Test
    void malformedRootJsonYieldsEmpty() {
        assertEmpty("{not json");
        assertEmpty("{\"networks\":{\"quic\":");
    }

    @Test
    void trailingGarbageAfterRootYieldsEmpty() {
        assertEmpty("{\"networks\":{\"quic\":%s}} trailing".formatted(QUIC_OK));
    }

    @Test
    void validQuicEntry() {
        var ability = StatusNetworksCodec.parse(networksQuic(QUIC_OK));
        assertTrue(ability.usable(AcceleratedTransport.QUIC));
        var entry = ability.entry(AcceleratedTransport.QUIC);
        assertNotNull(entry);
        assertEquals(25565, entry.port());
        assertNull(entry.host());
    }

    private static String networksQuic(String entry) {
        return networks("\"quic\":" + entry);
    }

    // --- parse: valid entries ---

    @Test
    void validKcpEntry() {
        var ability = StatusNetworksCodec.parse(networks(
                "\"kcp\":{\"enable\":true,\"host\":\"kcp.example.org\",\"port\":25566,\"protocol\":\"%s\"}".formatted(
                        KCP_PROTO
                )
        ));
        assertTrue(ability.usable(AcceleratedTransport.KCP));
        var entry = ability.entry(AcceleratedTransport.KCP);
        assertNotNull(entry);
        assertEquals("kcp.example.org", entry.host());
    }

    @Test
    void bothEntriesParsed() {
        var ability = StatusNetworksCodec.parse(networks(
                "\"quic\":%s,\"kcp\":{\"enable\":true,\"host\":null,\"port\":25566,\"protocol\":\"%s\"}".formatted(
                        QUIC_OK,
                        KCP_PROTO
                )
        ));
        assertTrue(ability.hasUsableAccelerated());
        assertEquals(2, ability.entries().size());
        assertTrue(ability.usable(AcceleratedTransport.QUIC));
        assertTrue(ability.usable(AcceleratedTransport.KCP));
    }

    @Test
    void missingEnableMeansDisabled() {
        var ability = StatusNetworksCodec.parse(networksQuic(
                "{\"host\":null,\"port\":25565,\"protocol\":\"%s\"}".formatted(QUIC_PROTO)
        ));
        assertNotNull(ability.entry(AcceleratedTransport.QUIC));
        assertFalse(ability.usable(AcceleratedTransport.QUIC));
        assertFalse(ability.hasUsableAccelerated());
    }

    // --- parse: field normalization ---

    @Test
    void explicitFalseMeansDisabled() {
        var ability = StatusNetworksCodec.parse(networksQuic(
                "{\"enable\":false,\"port\":25565,\"protocol\":\"%s\"}".formatted(QUIC_PROTO)
        ));
        assertFalse(ability.usable(AcceleratedTransport.QUIC));
    }

    @Test
    void blankHostNormalizedToNull() {
        var ability = StatusNetworksCodec.parse(networksQuic(
                "{\"enable\":true,\"host\":\"  \",\"port\":25565,\"protocol\":\"%s\"}".formatted(
                        QUIC_PROTO
                )
        ));
        var entry = ability.entry(AcceleratedTransport.QUIC);
        assertNotNull(entry);
        assertNull(entry.host());
    }

    @Test
    void explicitNullHostIsNull() {
        var ability = StatusNetworksCodec.parse(networksQuic(
                "{\"enable\":true,\"host\":null,\"port\":25565,\"protocol\":\"%s\"}".formatted(
                        QUIC_PROTO
                )
        ));
        assertNotNull(ability.entry(AcceleratedTransport.QUIC));
    }

    @Test
    void portBoundsAccepted() {
        IntStream.of(1, 65535)
                .forEach(port -> {
                    var ability = StatusNetworksCodec.parse(networksQuic(
                            "{\"enable\":true,\"port\":%d,\"protocol\":\"%s\"}".formatted(
                                    port,
                                    QUIC_PROTO
                            )
                    ));
                    assertTrue(
                            ability.usable(AcceleratedTransport.QUIC),
                            "port %d must be accepted".formatted(port)
                    );
                });
    }

    // --- parse: port handling ---

    @Test
    void portZeroRejected() {
        assertEmpty(networksQuic(
                "{\"enable\":true,\"port\":0,\"protocol\":\"%s\"}".formatted(QUIC_PROTO)
        ));
    }

    @Test
    void portTooLargeRejected() {
        assertEmpty(networksQuic(
                "{\"enable\":true,\"port\":65536,\"protocol\":\"%s\"}".formatted(QUIC_PROTO)
        ));
    }

    @Test
    void portOverflowRejectedSafely() {
        assertEmpty(networksQuic(
                "{\"enable\":true,\"port\":99999999999999999999,\"protocol\":\"%s\"}".formatted(
                        QUIC_PROTO
                )
        ));
    }

    @Test
    void wrongPortTypeRejected() {
        assertEmpty(networksQuic(
                "{\"enable\":true,\"port\":\"25565\",\"protocol\":\"%s\"}".formatted(QUIC_PROTO)
        ));
        assertEmpty(networksQuic(
                "{\"enable\":true,\"port\":25565.5,\"protocol\":\"%s\"}".formatted(QUIC_PROTO)
        ));
    }

    @Test
    void missingPortRejected() {
        assertEmpty(networksQuic("{\"enable\":true,\"protocol\":\"%s\"}".formatted(QUIC_PROTO)));
    }

    @Test
    void wrongEnableTypeDegradesToDisabled() {
        var ability = StatusNetworksCodec.parse(networksQuic(
                "{\"enable\":1,\"port\":25565,\"protocol\":\"%s\"}".formatted(QUIC_PROTO)
        ));
        assertNotNull(ability.entry(AcceleratedTransport.QUIC));
        assertFalse(ability.usable(AcceleratedTransport.QUIC));
    }

    // --- parse: enable/protocol type handling ---

    @Test
    void protocolMismatchRejected() {
        assertEmpty(networksQuic(
                "{\"enable\":true,\"port\":25565,\"protocol\":\"%s\"}".formatted(KCP_PROTO)
        ));
    }

    @Test
    void unknownProtocolRejected() {
        assertEmpty(networksQuic(
                "{\"enable\":true,\"port\":25565,\"protocol\":\"net-bri-quic/99\"}"
        ));
    }

    @Test
    void unknownTransportKeyIgnored() {
        var ability = StatusNetworksCodec.parse(networks(
                "\"sctp\":{\"enable\":true,\"port\":25567,\"protocol\":\"net-bri-sctp/1\"},\"quic\":%s".formatted(
                        QUIC_OK
                )
        ));
        assertEquals(1, ability.entries().size());
        assertTrue(ability.usable(AcceleratedTransport.QUIC));
        assertNull(ability.entry(AcceleratedTransport.KCP));
    }

    @Test
    void malformedOneEntryKeepsAnother() {
        var ability = StatusNetworksCodec.parse(networks(
                "\"quic\":{\"enable\":\"yes\",\"port\":25565,\"protocol\":\"%s\"},\"kcp\":{\"enable\":true,\"host\":null,\"port\":25566,\"protocol\":\"%s\"}".formatted(
                        QUIC_PROTO,
                        KCP_PROTO
                )
        ));
        assertFalse(ability.usable(AcceleratedTransport.QUIC));
        assertTrue(ability.usable(AcceleratedTransport.KCP));
    }

    @Test
    void unknownFieldsInsideEntryIgnored() {
        var ability = StatusNetworksCodec.parse(networksQuic(
                "{\"enable\":true,\"host\":null,\"port\":25565,\"protocol\":\"%s\",\"future\":{\"a\":[1,2,{\"b\":null}]}}".formatted(
                        QUIC_PROTO
                )
        ));
        assertTrue(ability.usable(AcceleratedTransport.QUIC));
    }

    @Test
    void unicodeHostRoundTrips() {
        var host = "mc-example\u0301.test";
        var ability = StatusNetworksCodec.parse(networksQuic(
                "{\"enable\":true,\"host\":\"%s\",\"port\":25565,\"protocol\":\"%s\"}".formatted(
                        host,
                        QUIC_PROTO
                )
        ));
        var entry = ability.entry(AcceleratedTransport.QUIC);
        assertNotNull(entry);
        assertEquals(host, entry.host());
    }

    // --- parse: unicode and escaping ---

    @Test
    void escapedStringsParsed() {
        var ability = StatusNetworksCodec.parse(networksQuic(
                "{\"enable\":true,\"host\":\"a\\\"b\\\\c\\/d\\u0041\",\"port\":25565,\"protocol\":\"%s\"}".formatted(
                        QUIC_PROTO
                )
        ));
        var entry = ability.entry(AcceleratedTransport.QUIC);
        assertNotNull(entry);
        assertEquals("a\"b\\c/dA", entry.host());
    }

    @Test
    void excessiveNestingRejectedSafely() {
        assertEmpty(networksQuic(
                "{\"enable\":true,\"port\":25565,\"protocol\":\"%s\",\"pad\":%s}".formatted(
                        QUIC_PROTO,
                        nestedPad(200)
                )
        ));
    }

    // --- parse: bounded limits degrade to empty, never throw ---

    private static String nestedPad(int depth) {
        return "%s0%s".formatted(
                "[".repeat(depth),
                "]".repeat(depth)
        );
    }

    @Test
    void moderateNestingAccepted() {
        var ability = StatusNetworksCodec.parse(networksQuic(
                "{\"enable\":true,\"port\":25565,\"protocol\":\"%s\",\"pad\":%s}".formatted(
                        QUIC_PROTO,
                        nestedPad(32)
                )
        ));
        assertTrue(ability.usable(AcceleratedTransport.QUIC));
    }

    @Test
    void oversizedDocumentRejectedSafely() {
        var pad = IntStream.range(0, 20_000)
                .mapToObj("\"k%d\":1,"::formatted)
                .collect(Collectors.joining());
        assertEmpty("{%s\"networks\":{\"quic\":%s}}".formatted(pad, QUIC_OK));
    }

    @Test
    void overlongPropertyNameRejectedSafely() {
        assertEmpty("{\"%s\":1,\"networks\":{\"quic\":%s}}".formatted(
                "a".repeat(2_000),
                QUIC_OK
        ));
    }

    @Test
    void excessiveTokenCountRejectedSafely() {
        var pad = "0,".repeat(40_000) + "0";
        assertEmpty("{\"pad\":[%s],\"networks\":{\"quic\":%s}}".formatted(
                pad,
                QUIC_OK
        ));
    }

    @Test
    void overlongNumberRejectedSafely() {
        assertEmpty("{\"pad\":%s,\"networks\":{\"quic\":%s}}".formatted(
                "9".repeat(200),
                QUIC_OK
        ));
    }

    @Test
    void unterminatedNestingDoesNotOverflowStack() {
        assertEmpty("{\"a\":%s".formatted(
                nestedPad(500)
                        .replace("[", "[")
                        .substring(0, 500)
        ));
    }

    @Test
    void existingNetworksNeverOverwritten() {
        var original = networks("\"quic\":{\"enable\":false,\"port\":1,\"protocol\":\"%s\"}".formatted(
                QUIC_PROTO
        ));
        assertEquals(
                original,
                StatusNetworksCodec.addNetworks(
                        original,
                        quic(new NetworksEntry(true, null, 25565))
                )
        );
    }

    // --- addNetworks: injector semantics ---

    private static NetworksAbility quic(NetworksEntry entry) {
        return NetworksAbility.of(Map.of(AcceleratedTransport.QUIC, entry));
    }

    @Test
    void emptyAbilityReturnsOriginalUnchanged() {
        var original = "{\"version\":{\"name\":\"srv\"}}";
        assertEquals(
                original,
                StatusNetworksCodec.addNetworks(original, NetworksAbility.empty())
        );
        assertEquals(
                original,
                StatusNetworksCodec.addNetworks(original, null)
        );
    }

    @Test
    void malformedRootReturnsOriginalUnchanged() {
        var original = "{not json";
        assertEquals(
                original,
                StatusNetworksCodec.addNetworks(
                        original,
                        quic(new NetworksEntry(true, null, 25565))
                )
        );
    }

    @Test
    void nonObjectRootReturnsOriginalUnchanged() {
        Stream.of("[1,2]", "\"hi\"", "42")
                .forEach(original -> assertEquals(
                        original,
                        StatusNetworksCodec.addNetworks(
                                original,
                                quic(new NetworksEntry(true, null, 25565))
                        )
                ));
    }

    @Test
    void injectorPreservesUnrelatedFields() {
        var original = "{\"version\":{\"name\":\"srv\",\"protocol\":7},"
                + "\"players\":{\"max\":20,\"online\":3,\"sample\":[{\"name\":\"a\",\"id\":\"b\"}]},"
                + "\"favicon\":\"data:image/png;base64,AAAA\"}";
        var injected = StatusNetworksCodec.addNetworks(
                original,
                NetworksAbility.of(Map.of(
                        AcceleratedTransport.QUIC, new NetworksEntry(true, null, 25565),
                        AcceleratedTransport.KCP, new NetworksEntry(true, "kcp.example.org", 25566)
                ))
        );

        var parsed = StatusNetworksCodec.parse(injected);
        assertTrue(parsed.usable(AcceleratedTransport.QUIC));
        assertTrue(parsed.usable(AcceleratedTransport.KCP));

        assertTrue(injected.contains("\"version\""));
        assertTrue(injected.contains("\"protocol\":7"));
        assertTrue(injected.contains("\"sample\""));
        assertTrue(injected.contains("\"favicon\""));
    }

    @Test
    void injectorKeepsUnrelatedTopLevelContentSemantically() {
        var original = "{\"description\":{\"text\":\"hi\"},\"favicon\":\"data:image/png;base64,AAAA==\"}";
        var injected = StatusNetworksCodec.addNetworks(
                original,
                quic(new NetworksEntry(true, null, 25565))
        );
        assertTrue(injected.startsWith("{"));
        assertTrue(injected.endsWith("}"));
        assertTrue(injected.contains("\"description\":{\"text\":\"hi\"}"));
    }

    @Test
    void injectorWritesHostOnlyWhenPresent() {
        var injected = StatusNetworksCodec.addNetworks(
                "{\"version\":{}}",
                quic(new NetworksEntry(true, null, 25565))
        );
        assertTrue(injected.contains("\"quic\""));
        assertFalse(injected.contains("\"host\""));
    }

}
