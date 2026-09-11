package top.tangge233.netbridge.mc.benchmark;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.json.JsonFactory;
import top.tangge233.netbridge.NetBridge;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Real-Minecraft session recorder (client/server side markers).
 *
 * <p>Enabled only when {@code -Dnetbridge.benchmark=true}. The disabled path is
 * a single boolean read per call — trivially JIT-eliminable and it never calls {@code nanoTime} per
 * packet. Emits a compact JSON file {@code benchmark-results/minecraft-<ts>.json} in the game
 * directory.
 *
 * <p>Marker names follow the suite contract: {@code CONNECT_REQUESTED},
 * {@code TRANSPORT_CONNECTED}, {@code LOGIN_COMPLETE}, {@code PLAY_ENTERED}, and chunk milestones
 * {@code FIRST_CHUNK}/{@code CHUNKS_32}/{@code CHUNKS_64}.
 */
public final class MinecraftBenchmarkRecorder {

    public static final String PROPERTY = "netbridge.benchmark";
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);

    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private static final MinecraftBenchmarkRecorder INSTANCE = new MinecraftBenchmarkRecorder();

    private final List<Map<String, Object>> events = new ArrayList<>();
    private final Map<String, Boolean> chunkSeen = new LinkedHashMap<>();
    private final Object lock = new Object();
    private @Nullable Path targetFile;

    private MinecraftBenchmarkRecorder() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static MinecraftBenchmarkRecorder get() {
        return INSTANCE;
    }

    private static Path resultsDir() {
        var override = System.getProperty(PROPERTY + ".dir");
        return override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of("benchmark-results");
    }

    private static String nowIso() {
        return DateTimeFormatter.ISO_INSTANT
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }

    private static void writeValue(JsonGenerator g, Object value) {
        switch (value) {
            case null -> g.writeNull();
            case String s -> g.writeString(s);
            case Boolean b -> g.writeBoolean(b);
            case Integer i -> g.writeNumber(i);
            case Long l -> g.writeNumber(l);
            case Double d -> g.writeNumber(d);
            case Float f -> g.writeNumber(f);
            case Map<?, ?> map -> {
                g.writeStartObject();
                map.forEach((key, value1) -> {
                    g.writeName(String.valueOf(key));
                    writeValue(g, value1);
                });
                g.writeEndObject();
            }
            case Iterable<?> it -> {
                g.writeStartArray();
                for (var o : it) {
                    writeValue(g, o);
                }
                g.writeEndArray();
            }
            default -> g.writeString(String.valueOf(value));
        }
    }

    /** Records the moment the client asked to connect to a remote address. */
    public void connectRequested(InetSocketAddress address) {
        milestone(
                "CONNECT_REQUESTED", Map.of(
                        "host", address.getHostString(),
                        "port", address.getPort()
                )
        );
    }

    /** Records the moment the transport became usable (TCP or native, same contract). */
    public void transportConnected(InetSocketAddress address) {
        milestone(
                "TRANSPORT_CONNECTED", Map.of(
                        "host", address.getHostString(),
                        "port", address.getPort()
                )
        );
    }

    public void loginComplete() {
        milestone("LOGIN_COMPLETE", Map.of());
    }

    public void playEntered() {
        milestone("PLAY_ENTERED", Map.of());
    }

    /**
     * Records a chunk milestone. Chunks are deduplicated by coordinate so the first/32/64
     * milestones only count distinct chunks.
     */
    public void onClientChunk(int chunkX, int chunkZ) {
        if (!ENABLED) {
            return;
        }

        var key = chunkX + "," + chunkZ;
        String name = null;
        synchronized (lock) {
            if (chunkSeen.put(key, Boolean.TRUE) != null) {
                return;
            }

            var unique = chunkSeen.size();
            switch (unique) {
                case 1 -> name = "FIRST_CHUNK";
                case 32 -> name = "CHUNKS_32";
                case 64 -> name = "CHUNKS_64";
                default -> {
                }
            }
        }

        if (name != null) {
            milestone(
                    name,
                    Map.of("uniqueChunks", chunkSeen.size())
            );
        }
    }

    /** Generic milestone sink; metadata is environment-friendly (no credentials). */
    public void milestone(
            String name,
            Map<String, Object> metadata
    ) {
        if (!ENABLED) {
            return;
        }

        NetBridge.LOGGER.info("[benchmark] milestone {}", name);
        synchronized (lock) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("name", name);
            event.put("wallNanos", System.nanoTime());
            event.put("at", nowIso());
            event.putAll(metadata);
            events.add(event);
            writeDocument();
        }
    }

    private void writeDocument() {
        try {
            var file = targetFile;
            if (file == null) {
                Files.createDirectories(resultsDir());
                file = resultsDir().resolve(
                        "minecraft-" + Instant.now().toString().replace(':', '-') + ".json"
                );
                targetFile = file;
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("suite", "minecraft");
            root.put("startedAt", nowIso());
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("os", System.getProperty("os.name", "-"));
            env.put("jdkVersion", System.getProperty("java.version", "-"));
            env.put("recorder", "MinecraftBenchmarkRecorder");
            root.put("environment", env);
            root.put("results", new ArrayList<>(events));

            try (
                    var g = JSON_FACTORY.createGenerator(
                            Files.newBufferedWriter(file, StandardCharsets.UTF_8)
                    )
            ) {
                writeValue(g, root);
                g.writeRaw('\n');
            }
        } catch (IOException e) {
            NetBridge.LOGGER.warn("[benchmark] cannot persist recorder file: {}", e.toString());
        }
    }

}
