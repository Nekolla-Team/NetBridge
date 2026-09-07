package top.tangge233.netbridge.ability;

import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import top.tangge233.netbridge.transport.AcceleratedTransport;

import java.io.StringWriter;
import java.util.EnumMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class StatusNetworksCodec {

    private static final String KEY_NETWORKS = "networks";
    private static final String KEY_ENABLE = "enable";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_PROTOCOL = "protocol";

    private static final JsonFactory FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(262_144L)
                    .maxNestingDepth(64)
                    .maxTokenCount(32_768L)
                    .maxNameLength(1_024)
                    .maxStringLength(262_144)
                    .maxNumberLength(64)
                    .build())
            .build();

    private StatusNetworksCodec() {
    }

    public static NetworksAbility parse(@Nullable String statusJson) {
        if (statusJson == null || statusJson.isBlank()) {
            return NetworksAbility.empty();
        }

        try (var parser = FACTORY.createParser(ObjectReadContext.empty(), statusJson)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return NetworksAbility.empty();
            }

            Map<AcceleratedTransport, NetworksEntry> entries = new EnumMap<>(AcceleratedTransport.class);
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.PROPERTY_NAME) {
                    return NetworksAbility.empty();
                }

                var propName = parser.currentName();
                var valToken = parser.nextToken();

                if (KEY_NETWORKS.equals(propName)) {
                    if (valToken != JsonToken.START_OBJECT) {
                        // networks is not an object -> empty
                        return NetworksAbility.empty();
                    }
                    parseNetworksObject(parser, entries);
                } else {
                    parser.skipChildren();
                }
            }

            // Reject trailing content after the single root object.
            if (parser.nextToken() != null) {
                return NetworksAbility.empty();
            }

            return NetworksAbility.of(entries);
        } catch (Exception e) {
            return NetworksAbility.empty();
        }
    }

    private static void parseNetworksObject(
            JsonParser parser,
            Map<AcceleratedTransport, NetworksEntry> entries
    ) throws JacksonException {
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.PROPERTY_NAME) {
                return;
            }

            var transportName = parser.currentName();
            var valToken = parser.nextToken();

            var transport = AcceleratedTransport.fromKey(transportName);
            if (transport == null || valToken != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }

            // Parse one transport entry
            var entry = parseEntryObject(parser, transport);
            if (entry != null) {
                entries.put(transport, entry);
            }
        }
    }

    private static @Nullable NetworksEntry parseEntryObject(
            JsonParser parser,
            AcceleratedTransport transport
    ) throws JacksonException {
        Boolean enabled = null;
        String host = null;
        Integer port = null;
        String protocol = null;

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.PROPERTY_NAME) {
                return null;
            }

            var fieldName = parser.currentName();
            var fieldToken = parser.nextToken();

            switch (fieldName) {
                case KEY_ENABLE -> {
                    if (fieldToken == JsonToken.VALUE_TRUE) {
                        enabled = true;
                    } else if (fieldToken == JsonToken.VALUE_FALSE) {
                        enabled = false;
                    } else {
                        // wrong type -> malformed entry, skip children
                        parser.skipChildren();
                        enabled = null;
                    }
                }
                case KEY_HOST -> {
                    if (fieldToken == JsonToken.VALUE_STRING) {
                        host = parser.getString();
                    } else if (fieldToken == JsonToken.VALUE_NULL) {
                        host = null;
                    } else {
                        parser.skipChildren();
                    }
                }
                case KEY_PORT -> {
                    if (fieldToken == JsonToken.VALUE_NUMBER_INT) {
                        port = parseIntValue(parser);
                    } else {
                        parser.skipChildren();
                    }
                }
                case KEY_PROTOCOL -> {
                    if (fieldToken == JsonToken.VALUE_STRING) {
                        protocol = parser.getString();
                    } else if (fieldToken == JsonToken.VALUE_NULL) {
                        protocol = null;
                    } else {
                        parser.skipChildren();
                    }
                }
                default -> parser.skipChildren();
            }
        }

        // Validate port
        if (port == null || port < 1 || port > 65535) {
            return null;
        }

        // Check protocol exact match against expected protocol
        if (!transport.protocol().equals(protocol)) {
            return null;
        }

        var isEnabled = enabled != null && enabled;
        if (host != null && host.isBlank()) {
            host = null;
        }

        return new NetworksEntry(isEnabled, host, port);
    }

    private static @Nullable Integer parseIntValue(
            JsonParser parser
    ) throws JacksonException {
        var text = parser.getString();
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException _) {
            return null;
        }
    }

    public static String addNetworks(
            @Nullable String originalJson,
            @Nullable NetworksAbility networks
    ) {
        if (originalJson == null) {
            return "";
        }

        if (networks == null || networks.entries().isEmpty()) {
            return originalJson;
        }

        // Check if root already has "networks" or is not an object or is malformed
        try (var probe = FACTORY.createParser(ObjectReadContext.empty(), originalJson)) {
            if (probe.nextToken() != JsonToken.START_OBJECT) {
                return originalJson;
            }

            while (probe.nextToken() != JsonToken.END_OBJECT) {
                if (probe.currentToken() != JsonToken.PROPERTY_NAME) {
                    return originalJson;
                }

                var name = probe.currentName();
                if (KEY_NETWORKS.equals(name)) {
                    // already has networks property -> never overwrite
                    return originalJson;
                }
                probe.nextToken();
                probe.skipChildren();
            }
        } catch (Exception e) {
            return originalJson;
        }

        // Stream-copy root object and append "networks"
        try {
            var sw = new StringWriter();
            try (
                    var parser = FACTORY.createParser(ObjectReadContext.empty(), originalJson);
                    var generator = FACTORY.createGenerator(ObjectWriteContext.empty(), sw)
            ) {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    return originalJson;
                }

                generator.writeStartObject();

                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    var name = parser.currentName();
                    generator.writeName(name);
                    parser.nextToken();
                    generator.copyCurrentStructure(parser);
                }

                // Append networks
                generator.writeName(KEY_NETWORKS);
                generator.writeStartObject();
                networks.entries().forEach((transport, entry) -> {
                    generator.writeName(transport.key());
                    generator.writeStartObject();
                    generator.writeBooleanProperty(KEY_ENABLE, entry.enabled());
                    if (entry.host() != null && !entry.host().isBlank()) {
                        generator.writeStringProperty(KEY_HOST, entry.host());
                    }
                    generator.writeNumberProperty(KEY_PORT, entry.port());
                    generator.writeStringProperty(KEY_PROTOCOL, transport.protocol());
                    generator.writeEndObject();
                });
                generator.writeEndObject();
                generator.writeEndObject();
            }
            return sw.toString();
        } catch (Exception e) {
            return originalJson;
        }
    }

}
