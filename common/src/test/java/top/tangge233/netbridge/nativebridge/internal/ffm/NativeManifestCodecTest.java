package top.tangge233.netbridge.nativebridge.internal.ffm;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Native manifest Jackson-codec tests: valid generation contract, required-field validation, type
 * checks, duplicates, unknown-field tolerance and parser bounds.
 */
class NativeManifestCodecTest {

    private static final String ARTIFACT = "libnet_bridge_native.so";
    private static final String SHA_OK = "a".repeat(64);
    private static final String VERSION = "0.1.0";

    @Test
    void validGeneratedManifestAccepted() {
        var manifest = assertDoesNotThrow(() -> NativeManifestCodec.parse(validJson()));
        assertEquals(ARTIFACT, manifest.artifact());
        assertEquals(SHA_OK, manifest.sha256());
        assertEquals(1, manifest.abiMajor());
        assertEquals(0, manifest.abiMinor());
        assertEquals(VERSION, manifest.rustPackageVersion());
    }

    private static String validJson() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("artifact", q(ARTIFACT));
        fields.put("sha256", q(SHA_OK));
        fields.put("abiMajor", "1");
        fields.put("abiMinor", "0");
        fields.put("rustPackageVersion", q(VERSION));
        return manifestJson(fields);
    }

    private static String q(String s) {
        return '"' + s + '"';
    }

    private static String manifestJson(Map<String, String> fields) {
        var sb = new StringBuilder("{");
        var first = true;
        for (var e : fields.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
        }
        return sb.append('}').toString();
    }

    @Test
    void validManifestAcceptedFromInputStream() {
        var manifest = NativeManifestCodec.parse(
                new ByteArrayInputStream(validJson().getBytes(StandardCharsets.UTF_8))
        );
        assertEquals(ARTIFACT, manifest.artifact());
    }

    @Test
    void missingRequiredFieldIsRejected() {
        Stream.of(
                "artifact",
                "sha256",
                "abiMajor",
                "abiMinor",
                "rustPackageVersion"
        ).forEach(s -> assertThrows(
                NativeResourceException.class,
                () -> NativeManifestCodec.parse(without(s))
        ));
    }

    private static String without(String key) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("artifact", q(ARTIFACT));
        fields.put("sha256", q(SHA_OK));
        fields.put("abiMajor", "1");
        fields.put("abiMinor", "0");
        fields.put("rustPackageVersion", q(VERSION));
        fields.remove(key);
        return manifestJson(fields);
    }

    @Test
    void wrongTypeForEachRequiredCategoryRejected() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("artifact", "42");
        fields.put("sha256", q(SHA_OK));
        fields.put("abiMajor", "1");
        fields.put("abiMinor", "0");
        fields.put("rustPackageVersion", q(VERSION));
        parseError(manifestJson(fields));

        fields.put("artifact", q(ARTIFACT));
        fields.put("sha256", "42");
        parseError(manifestJson(fields));

        fields.put("sha256", q(SHA_OK));
        fields.put("abiMajor", q("1"));
        parseError(manifestJson(fields));

        fields.put("abiMajor", "1");
        fields.put("abiMinor", q("0"));
        parseError(manifestJson(fields));

        fields.put("abiMinor", "0");
        fields.put("rustPackageVersion", "true");
        parseError(manifestJson(fields));
    }

    private static NativeResourceException parseError(String json) {
        return assertThrows(
                NativeResourceException.class,
                () -> NativeManifestCodec.parse(json)
        );
    }

    @Test
    void blankRequiredStringRejected() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("artifact", q("  "));
        fields.put("sha256", q(SHA_OK));
        fields.put("abiMajor", "1");
        fields.put("abiMinor", "0");
        fields.put("rustPackageVersion", q(VERSION));
        parseError(manifestJson(fields));
    }

    @Test
    void duplicateRequiredFieldRejected() {
        parseError(
                "{\"artifact\":%s,\"artifact\":%s,\"sha256\":%s,\"abiMajor\":1,\"abiMinor\":0,\"rustPackageVersion\":%s}".formatted(
                        q(ARTIFACT),
                        q(ARTIFACT),
                        q(SHA_OK),
                        q(VERSION)
                )
        );
    }

    @Test
    void unknownFieldIsSkipped() {
        var json = validJson().replace(
                "{",
                "{\"futureKey\":{\"nested\":true},"
        );
        var manifest = NativeManifestCodec.parse(json);
        assertEquals(ARTIFACT, manifest.artifact());
    }

    @Test
    void malformedJsonRejected() {
        parseError("{not json");
        parseError(validJson().substring(0, 40));
    }

    @Test
    void nonObjectRootRejected() {
        parseError("[1,2,3]");
        parseError(q("hello"));
    }

    @Test
    void tooDeepRejected() {
        parseError(
                "{\"a\":%s1%s,\"artifact\":%s}".formatted(
                        "[1,".repeat(30),
                        "]".repeat(30),
                        q(ARTIFACT)
                )
        );
    }

    @Test
    void tooLargeRejected() {
        var big = IntStream.range(0, 2000)
                .mapToObj(i -> "\"%d\":\"%s\",".formatted(
                        i,
                        "x".repeat(20)
                ))
                .collect(Collectors.joining(
                        "",
                        "{",
                        "\"artifact\":%s}".formatted(q(ARTIFACT))
                ));
        parseError(big);
    }

    @Test
    void invalidShaLengthRejected() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("artifact", q(ARTIFACT));
        fields.put("sha256", q("a".repeat(63)));
        fields.put("abiMajor", "1");
        fields.put("abiMinor", "0");
        fields.put("rustPackageVersion", q(VERSION));
        parseError(manifestJson(fields));
    }

    @Test
    void invalidShaCharactersRejected() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("artifact", q(ARTIFACT));
        fields.put("sha256", q("g".repeat(64)));
        fields.put("abiMajor", "1");
        fields.put("abiMinor", "0");
        fields.put("rustPackageVersion", q(VERSION));
        parseError(manifestJson(fields));
    }

    @Test
    void uppercaseShaHexAccepted() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("artifact", q(ARTIFACT));
        fields.put("sha256", q("A".repeat(64)));
        fields.put("abiMajor", "1");
        fields.put("abiMinor", "0");
        fields.put("rustPackageVersion", q(VERSION));
        var manifest = NativeManifestCodec.parse(manifestJson(fields));
        assertEquals("A".repeat(64), manifest.sha256());
    }

    @Test
    void negativeAbiRejected() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("artifact", q(ARTIFACT));
        fields.put("sha256", q(SHA_OK));
        fields.put("abiMajor", "-1");
        fields.put("abiMinor", "0");
        fields.put("rustPackageVersion", q(VERSION));
        parseError(manifestJson(fields));

        fields.put("abiMajor", "1");
        fields.put("abiMinor", "-1");
        parseError(manifestJson(fields));
    }

    @Test
    void outOfIntRangeAbiRejected() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("artifact", q(ARTIFACT));
        fields.put("sha256", q(SHA_OK));
        fields.put("abiMajor", "99999999999999999999");
        fields.put("abiMinor", "0");
        fields.put("rustPackageVersion", q(VERSION));
        parseError(manifestJson(fields));
    }

    @Test
    void generatedManifestSpellingMatchesParserContract() {
        // Field spelling must match build-logic's generateNativeManifest output (camelCase).
        assertDoesNotThrow(() -> NativeManifestCodec.parse(validJson()));
        // snake_case-only spelling must NOT be silently accepted as the same format.
        var snake = "{\"artifact\":%s,\"sha256\":%s,\"abi_major\":1,\"abi_minor\":0,\"rust_package_version\":%s}".formatted(
                q(ARTIFACT),
                q(SHA_OK),
                q(VERSION)
        );
        parseError(snake);
    }

}
