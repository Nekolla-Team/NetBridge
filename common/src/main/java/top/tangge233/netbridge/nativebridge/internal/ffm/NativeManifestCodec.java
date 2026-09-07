package top.tangge233.netbridge.nativebridge.internal.ffm;

import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Jackson Core streaming decoder for native platform manifests.
 */
final class NativeManifestCodec {

    private static final JsonFactory FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(16_384L)
                    .maxNestingDepth(8)
                    .maxTokenCount(128L)
                    .maxNameLength(64)
                    .maxStringLength(1_024)
                    .maxNumberLength(16)
                    .build())
            .build();

    private NativeManifestCodec() {
    }

    public static NativeManifest parse(InputStream in) throws NativeResourceException {
        try (var parser = FACTORY.createParser(ObjectReadContext.empty(), in)) {
            return parse(parser);
        } catch (JacksonException e) {
            throw new NativeResourceException(
                    NativeResourceError.RESOURCE_MANIFEST_INVALID,
                    "RESOURCE_MANIFEST_INVALID: JSON parse failure",
                    e
            );
        } catch (NativeResourceException e) {
            throw e;
        } catch (Exception e) {
            throw new NativeResourceException(
                    NativeResourceError.RESOURCE_MANIFEST_INVALID,
                    "RESOURCE_MANIFEST_INVALID: cannot read manifest",
                    e
            );
        }
    }

    private static NativeManifest parse(JsonParser parser) throws JacksonException {
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            throw new NativeResourceException(
                    NativeResourceError.RESOURCE_MANIFEST_INVALID,
                    "RESOURCE_MANIFEST_INVALID: root is not an object"
            );
        }

        String artifact = null;
        String sha256 = null;
        Integer abiMajor = null;
        Integer abiMinor = null;
        String rustPackageVersion = null;
        Set<String> seenProperties = new HashSet<>();

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            var token = parser.currentToken();
            if (token != JsonToken.PROPERTY_NAME) {
                throw new NativeResourceException(
                        NativeResourceError.RESOURCE_MANIFEST_INVALID,
                        "RESOURCE_MANIFEST_INVALID: expected property name"
                );
            }

            var propName = parser.currentName();
            if (!seenProperties.add(propName)) {
                throw new NativeResourceException(
                        NativeResourceError.RESOURCE_MANIFEST_INVALID,
                        "RESOURCE_MANIFEST_INVALID: duplicate property " + propName
                );
            }

            var valToken = parser.nextToken();
            switch (propName) {
                case "artifact" -> {
                    if (valToken != JsonToken.VALUE_STRING) {
                        throw new NativeResourceException(
                                NativeResourceError.RESOURCE_MANIFEST_INVALID,
                                "RESOURCE_MANIFEST_INVALID: artifact must be string"
                        );
                    }
                    artifact = parser.getString();
                }
                case "sha256" -> {
                    if (valToken != JsonToken.VALUE_STRING) {
                        throw new NativeResourceException(
                                NativeResourceError.RESOURCE_MANIFEST_INVALID,
                                "RESOURCE_MANIFEST_INVALID: sha256 must be string"
                        );
                    }
                    sha256 = parser.getString();
                }
                case "abiMajor" -> {
                    if (valToken != JsonToken.VALUE_NUMBER_INT) {
                        throw new NativeResourceException(
                                NativeResourceError.RESOURCE_MANIFEST_INVALID,
                                "RESOURCE_MANIFEST_INVALID: abiMajor must be int"
                        );
                    }
                    abiMajor = parser.getIntValue();
                }
                case "abiMinor" -> {
                    if (valToken != JsonToken.VALUE_NUMBER_INT) {
                        throw new NativeResourceException(
                                NativeResourceError.RESOURCE_MANIFEST_INVALID,
                                "RESOURCE_MANIFEST_INVALID: abiMinor must be int"
                        );
                    }
                    abiMinor = parser.getIntValue();
                }
                case "rustPackageVersion" -> {
                    if (valToken != JsonToken.VALUE_STRING) {
                        throw new NativeResourceException(
                                NativeResourceError.RESOURCE_MANIFEST_INVALID,
                                "RESOURCE_MANIFEST_INVALID: rustPackageVersion must be string"
                        );
                    }
                    rustPackageVersion = parser.getString();
                }
                // Unknown fields skipped for forward compatibility
                default -> parser.skipChildren();
            }
        }

        if (artifact == null
                || sha256 == null
                || abiMajor == null
                || abiMinor == null
                || rustPackageVersion == null
        ) {
            throw new NativeResourceException(
                    NativeResourceError.RESOURCE_MANIFEST_INVALID,
                    "RESOURCE_MANIFEST_INVALID: missing required fields"
            );
        }

        try {
            return new NativeManifest(
                    artifact,
                    sha256,
                    abiMajor,
                    abiMinor,
                    rustPackageVersion
            );
        } catch (IllegalArgumentException e) {
            throw new NativeResourceException(
                    NativeResourceError.RESOURCE_MANIFEST_INVALID,
                    "RESOURCE_MANIFEST_INVALID: " + e.getMessage(),
                    e
            );
        }
    }

    public static NativeManifest parse(String json) throws NativeResourceException {
        try (var parser = FACTORY.createParser(ObjectReadContext.empty(), json)) {
            return parse(parser);
        } catch (JacksonException e) {
            throw new NativeResourceException(
                    NativeResourceError.RESOURCE_MANIFEST_INVALID,
                    "RESOURCE_MANIFEST_INVALID: JSON parse failure",
                    e
            );
        } catch (NativeResourceException e) {
            throw e;
        } catch (Exception e) {
            throw new NativeResourceException(
                    NativeResourceError.RESOURCE_MANIFEST_INVALID,
                    "RESOURCE_MANIFEST_INVALID: cannot read manifest",
                    e
            );
        }
    }

}
