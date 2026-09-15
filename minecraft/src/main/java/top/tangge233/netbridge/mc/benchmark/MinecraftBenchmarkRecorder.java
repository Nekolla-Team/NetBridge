package top.tangge233.netbridge.mc.benchmark;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.json.JsonFactory;
import top.tangge233.netbridge.NetBridge;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * Real-Minecraft session recorder (client/server side markers).
 *
 * <p>Enabled only when {@code -Dnetbridge.benchmark=true}. The disabled path is a single boolean
 * read per call — trivially JIT-eliminable and it never calls {@code nanoTime} per packet. Emits a
 * compact JSON file {@code benchmark-results/minecraft-<ts>.json} in the game directory.
 *
 * <p>Marker names follow the suite contract: {@code CONNECT_REQUESTED},
 * {@code TRANSPORT_CONNECTED},{@code LOGIN_COMPLETE}, {@code PLAY_ENTERED}, and chunk milestones
 * {@code FIRST_CHUNK}/{@code CHUNKS_32}/{@code CHUNKS_64}.
 *
 * <p>Persistence is asynchronous (single daemon writer thread) so a milestone never blocks the
 * gameplay/network thread on disk I/O (B-020). Events carry session-relative {@code elapsedNanos};
 * the absolute monotonic clock value is never persisted as a duration (B-008). The target address
 * is reduced to a {@code targetKind} plus a salted {@code targetHash} unless
 * {@code -Dnetbridge.benchmark.captureTarget=true} (B-021).
 */
public final class MinecraftBenchmarkRecorder {

    public static final String PROPERTY = "netbridge.benchmark";
    private static final String CAPTURE_TARGET_PROPERTY = PROPERTY + ".captureTarget";
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    private static final boolean CAPTURE_TARGET = Boolean.getBoolean(CAPTURE_TARGET_PROPERTY);

    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private static final MinecraftBenchmarkRecorder INSTANCE = new MinecraftBenchmarkRecorder();

    private static final byte[] TARGET_SALT = new byte[16];

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "netbridge-benchmark-recorder");
        thread.setDaemon(true);
        return thread;
    });

    static {
        new SecureRandom().nextBytes(TARGET_SALT);
    }

    private final Object lock = new Object();
    private final AtomicBoolean persistPending = new AtomicBoolean();
    private @Nullable BenchmarkSession session;

    private MinecraftBenchmarkRecorder() {
        super();
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

    private static Map<String, Object> targetMetadata(@Nullable InetSocketAddress address) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (address == null) {
            metadata.put("targetKind", "unknown");
            return metadata;
        }

        var host = address.getHostString();
        var port = address.getPort();
        metadata.put("targetKind", classifyTarget(host));
        metadata.put("targetHash", targetHash(host, port));
        if (CAPTURE_TARGET) {
            metadata.put("host", host);
            metadata.put("port", port);
        }
        return metadata;
    }

    private static String classifyTarget(@Nullable String host) {
        if (host == null || host.isBlank()) {
            return "unknown";
        }
        if ("localhost".equalsIgnoreCase(host)) {
            return "loopback";
        }
        try {
            var address = InetAddress.getByName(host);
            if (address.isLoopbackAddress()) {
                return "loopback";
            }
            if (address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
                return "lan";
            }
            return "remote";
        } catch (UnknownHostException e) {
            return "remote";
        }
    }

    private static String targetHash(String host, int port) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(TARGET_SALT);
            digest.update(host.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(Integer.toString(port).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    /**
     * Starts a new logical client session and records {@code CONNECT_REQUESTED}. Only the outermost
     * connect must call this; accelerated-connect fallbacks inside the same logical connect must
     * use {@link #connectAttempt(InetSocketAddress, String)} instead (B-019).
     */
    public void beginSession(@Nullable InetSocketAddress address) {
        if (!ENABLED) {
            return;
        }

        var startedAt = Instant.now();
        synchronized (lock) {
            session = new BenchmarkSession(
                    "mc-" + Long.toHexString(System.nanoTime()),
                    startedAt
            );
            recordLocked("CONNECT_REQUESTED", targetMetadata(address));
        }
        schedulePersist();
    }

    /** Records an internal connect attempt (e.g. accelerated fallback) without starting a session. */
    public void connectAttempt(@Nullable InetSocketAddress address, String reason) {
        if (!ENABLED) {
            return;
        }

        synchronized (lock) {
            var current = session;
            if (current == null) {
                return;
            }
            current.attempts++;
            var metadata = targetMetadata(address);
            metadata.put("attempt", current.attempts);
            metadata.put("reason", reason);
            recordLocked("CONNECT_ATTEMPT", metadata);
        }
        schedulePersist();
    }

    /** Records the moment the transport became usable (TCP or native, same contract). */
    public void transportConnected(InetSocketAddress address) {
        milestone("TRANSPORT_CONNECTED", targetMetadata(address));
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

        String name;
        synchronized (lock) {
            var current = session;
            if (current == null) {
                return;
            }
            var key = chunkX + "," + chunkZ;
            if (current.chunkSeen.put(key, Boolean.TRUE) != null) {
                return;
            }

            var unique = current.chunkSeen.size();
            name = switch (unique) {
                case 1 -> "FIRST_CHUNK";
                case 32 -> "CHUNKS_32";
                case 64 -> "CHUNKS_64";
                default -> null;
            };
            if (name != null) {
                recordLocked(name, Map.of("uniqueChunks", unique));
            }
        }

        if (name != null) {
            schedulePersist();
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

        synchronized (lock) {
            if (session == null) {
                session = new BenchmarkSession(
                        "mc-" + Long.toHexString(System.nanoTime()),
                        Instant.now()
                );
            }
            recordLocked(name, metadata);
        }
        schedulePersist();
    }

    /** Requests a final asynchronous flush (safe to call on disconnect). */
    public void flush() {
        if (ENABLED) {
            schedulePersist();
        }
    }

    private void recordLocked(String name, Map<String, Object> metadata) {
        var current = session;
        if (current == null) {
            return;
        }

        NetBridge.LOGGER.info("[benchmark] milestone {}", name);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("name", name);
        event.put("sessionId", current.sessionId);
        event.put("attemptId", current.attempts);
        event.put("elapsedNanos", System.nanoTime() - current.startNanos);
        event.put("at", nowIso());
        event.putAll(metadata);
        current.events.add(event);
    }

    private void schedulePersist() {
        if (!persistPending.compareAndSet(false, true)) {
            return;
        }
        WRITER.execute(() -> {
            persistPending.set(false);
            writeDocument();
        });
    }

    private void writeDocument() {
        BenchmarkSession current;
        List<Map<String, Object>> snapshot;
        synchronized (lock) {
            current = session;
            if (current == null) {
                return;
            }
            snapshot = new ArrayList<>(current.events.size());
            current.events.stream()
                    .map(LinkedHashMap::new)
                    .forEach(snapshot::add);
        }

        try {
            var file = current.targetFile;
            if (file == null) {
                Files.createDirectories(resultsDir());
                file = resultsDir().resolve(
                        "minecraft-" + current.startedAt.toString().replace(':', '-') + ".json"
                );
                current.targetFile = file;
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("suite", "minecraft");
            root.put("sessionId", current.sessionId);
            root.put("attempts", current.attempts);
            root.put(
                    "startedAt", DateTimeFormatter.ISO_INSTANT
                            .withZone(ZoneOffset.UTC)
                            .format(current.startedAt)
            );
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("os", System.getProperty("os.name", "-"));
            env.put("jdkVersion", System.getProperty("java.version", "-"));
            env.put("recorder", "MinecraftBenchmarkRecorder");
            root.put("environment", env);
            root.put("results", snapshot);

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

    /** One logical benchmark session; fallback connects are attempts within it, not new sessions. */
    private static final class BenchmarkSession {

        final String sessionId;
        final Instant startedAt;
        final long startNanos;
        final List<Map<String, Object>> events = new ArrayList<>();
        final Map<String, Boolean> chunkSeen = new LinkedHashMap<>();
        int attempts = 1;
        @Nullable Path targetFile;

        BenchmarkSession(String sessionId, Instant startedAt) {
            super();
            this.sessionId = sessionId;
            this.startedAt = startedAt;
            this.startNanos = System.nanoTime();
        }

    }

}
