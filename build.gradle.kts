import org.gradle.internal.os.OperatingSystem.current
import top.tangge233.netbridge.build.BuildNativeLibrary
import top.tangge233.netbridge.build.NativePlatform.cdylibName
import top.tangge233.netbridge.build.NativePlatform.subdir
import java.security.MessageDigest

plugins {
    id("netbridge.java-conventions") apply false
    alias(libs.plugins.neoforge.moddev) apply false
    alias(libs.plugins.fabric.loom) apply false
}

allprojects {
    group = property("mavenGroup").toString()
    version = property("modVersion").toString()
}

val nativeProfileProperty = providers
        .gradleProperty("nativeProfile")
        .orElse("debug")

val skipNativeBuildProperty = providers
        .gradleProperty("skipNativeBuild")
        .map { true }
        .orElse(false)

tasks.register<BuildNativeLibrary>("buildCdylib") {
    group = "build"
    description = "Builds the native Rust cdylib and stages it for packaging."

    profile.set(nativeProfileProperty)
    skipNativeBuild.set(skipNativeBuildProperty)
    cargoDir.set(layout.projectDirectory.dir("rust"))
    outputDir.set(layout.buildDirectory.dir("native"))
}

val updateNativeHeader = tasks.register<Exec>("updateNativeHeader") {
    group = "verification"
    description =
        "Regenerates the checked-in C ABI header " +
                "(rust/crates/net-bridge-native/include/netbridge.h) " +
                "from the Rust ABI definitions using pinned cbindgen."

    workingDir = rootDir.resolve("rust")
    commandLine("cargo", "xtask", "abi-header", "update")
}

val verifyNativeHeader = tasks.register<Exec>("verifyNativeHeader") {
    group = "verification"
    description =
        "Fails if the checked-in C ABI header is stale relative to the Rust ABI source of truth " +
                "(cbindgen drift check)."

    workingDir = rootDir.resolve("rust")
    commandLine("cargo", "xtask", "abi-header", "check")
}

val generateNativeManifest = tasks.register("generateNativeManifest") {
    group = "build"
    description =
        "Writes a per-platform native manifest (sha256/ABI/version) next to each staged cdylib."

    dependsOn(tasks.named("buildCdylib"))

    val nativeDir = layout.buildDirectory.dir("native")

    inputs.dir(nativeDir).optional(true)
    outputs.files(nativeDir.map { dir -> dir.asFileTree.matching { include("**/manifest.json") } })

    onlyIf {
        val dir = nativeDir.get().asFile
        dir.listFiles { file: File -> file.isDirectory }
                ?.any { platform ->
                    platform.listFiles { file: File ->
                        file.isFile &&
                                (
                                        file.name.startsWith("libnet_bridge_native") ||
                                                file.name == "net_bridge_native.dll"
                                        )
                    }?.isNotEmpty() == true
                }
            ?: false
    }

    doLast {
        val dir = nativeDir.get().asFile
        val header = rootDir
                .resolve("rust/crates/net-bridge-native/include/netbridge.h")
                .readText()

        val abiMajor = Regex("#define\\s+NB_ABI_MAJOR\\s+(\\d+)")
                .find(header)
                ?.groupValues
                ?.get(1)
            ?: throw GradleException("NB_ABI_MAJOR not found in netbridge.h")

        val abiMinor = Regex("#define\\s+NB_ABI_MINOR\\s+(\\d+)")
                .find(header)
                ?.groupValues
                ?.get(1)
            ?: throw GradleException("NB_ABI_MINOR not found in netbridge.h")

        val cargoToml = rootDir
                .resolve("rust/crates/net-bridge-native/Cargo.toml")
                .readText()

        val rustVersion = Regex("(?m)^version\\s*=\\s*\"([^\"]+)\"")
                .find(cargoToml)
                ?.groupValues
                ?.get(1)
            ?: throw GradleException(
                "package version not found in net-bridge-native Cargo.toml"
            )

        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0

        dir.listFiles { file: File -> file.isDirectory }
                ?.sortedBy { it.name }
                ?.forEach { platform ->
                    val lib = platform.listFiles { file: File ->
                        file.isFile &&
                                (
                                        file.name.startsWith("libnet_bridge_native") ||
                                                file.name == "net_bridge_native.dll"
                                        )
                    }?.firstOrNull() ?: return@forEach

                    digest.reset()

                    lib.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        var read: Int

                        while (input.read(buffer).also { read = it } > 0) {
                            digest.update(buffer, 0, read)
                        }
                    }

                    val sha = digest
                            .digest()
                            .joinToString("") { "%02x".format(it) }

                    platform.resolve("manifest.json").writeText(
                        """
                        {
                          "artifact": "${lib.name}",
                          "sha256": "$sha",
                          "abiMajor": $abiMajor,
                          "abiMinor": $abiMinor,
                          "rustPackageVersion": "$rustVersion"
                        }
                        """.trimIndent() + "\n"
                    )

                    written++
                }

        if (written == 0) {
            logger.warn(
                "generateNativeManifest: no staged native libraries found in $dir"
            )
        } else {
            logger.lifecycle(
                "native manifests written for $written platform(s) " +
                        "(ABI $abiMajor.$abiMinor, rust $rustVersion)"
            )
        }
    }
}

val verifyNativeSymbols = tasks.register("verifyNativeSymbols") {
    group = "verification"
    description =
        "Verifies the staged cdylib exports only netbridge_get_api (Linux/macOS)."

    dependsOn(tasks.named("buildCdylib"))

    onlyIf {
        current().isLinux || current().isMacOsX
    }

    doLast {
        val lib = layout.buildDirectory
                .dir("native")
                .get()
                .asFile
                .resolve(subdir)
                .resolve(cdylibName)

        if (!lib.exists()) {
            throw GradleException("staged cdylib not found: $lib")
        }

        val exported = providers.exec {
            commandLine("nm", "-D", lib.absolutePath)
        }.standardOutput
                .asText
                .get()
                .lineSequence()
                .filter { it.contains(" T ") }
                .map { it.substringAfterLast(' ').trim() }
                .filter { it.isNotEmpty() }
                .filter { !it.startsWith("_") }
                .filter { !it.startsWith("__") }
                .toList()

        val expected = setOf("netbridge_get_api")
        val unexpected = exported - expected

        if (unexpected.isNotEmpty()) {
            throw GradleException(
                "unexpected exported symbols in native library: " +
                        "$unexpected (allowed: $expected)"
            )
        }

        logger.lifecycle(
            "native symbol check OK (exported business symbols: $exported)"
        )
    }
}

val verifyArchitecture = tasks.register("verifyArchitecture") {
    group = "verification"
    description =
        "Architecture guards: FFM import allowlist, JNI zero, layer purity, " +
                "nullness, no duplicated loader copies."

    doLast {
        val failures = mutableListOf<String>()

        fun javaMainSources(vararg roots: String): List<File> =
            roots.flatMap { root ->
                val dir = rootDir.resolve(root)

                if (dir.isDirectory) {
                    dir.walkTopDown()
                            .filter { it.isFile && it.name.endsWith(".java") }
                            .toList()
                } else {
                    emptyList()
                }
            }

        val production = javaMainSources(
            "common/src/main/java",
            "minecraft/src/main/java",
            "fabric/src/main/java",
            "neoforge/src/main/java"
        )

        val ffmAllowlistPrefix =
            "common/src/main/java/top/tangge233/netbridge/nativebridge/internal/ffm/"

        val runtimeAllowlist = setOf(
            "common/src/main/java/top/tangge233/netbridge/runtime/NetBridgeServices.java"
        )

        val mixinGuardAllowlist = setOf(
            "common/src/main/java/top/tangge233/netbridge/ability/StatusNetworksCapture.java"
        )

        // Jackson Core may only be imported by the two NetBridge JSON codecs; Gson may
        // only be imported in the shared Minecraft adapter layer (Mojang boundary).
        val jsonCodecAllowlist = setOf(
            "common/src/main/java/top/tangge233/netbridge/ability/StatusNetworksCodec.java",
            "common/src/main/java/top/tangge233/netbridge/nativebridge/internal/ffm/NativeManifestCodec.java"
        )

        for (file in production) {
            val rel = file.relativeTo(rootDir).path.replace('\\', '/')
            val text = file.readText()

            if (
                text.contains("import java.lang.foreign") &&
                !rel.startsWith(ffmAllowlistPrefix)
            ) {
                failures.add(
                    "$rel: java.lang.foreign import outside nativebridge.internal.ffm"
                )
            }

            if (
                Regex("\\bSystem\\.loadLibrary\\b|\\bSystem\\.load\\b")
                        .containsMatchIn(text)
            ) {
                failures.add(
                    "$rel: System.load/loadLibrary is forbidden (FFM libraryLookup only)"
                )
            }

            if (Regex("public\\s+static\\s+native\\s").containsMatchIn(text)) {
                failures.add(
                    "$rel: Java native method declarations are forbidden"
                )
            }

            if (
                Regex("static\\s+volatile").containsMatchIn(text) &&
                rel !in runtimeAllowlist
            ) {
                failures.add(
                    "$rel: static volatile business state outside the composition root"
                )
            }

            if (
                rel.endsWith("channel/NativeChannel.java") &&
                text.contains("BACKPRESSURE_RETRY_MILLIS")
            ) {
                failures.add(
                    "$rel: polling backpressure fallback is forbidden " +
                            "(WRITABLE event only)"
                )
            }

            if (
                rel.contains("/client/") &&
                text.contains("syncUninterruptibly") &&
                !rel.contains("Test") &&
                !rel.endsWith("DelegatingChannelFuture.java")
            ) {
                failures.add(
                    "$rel: client orchestration must not block the calling thread"
                )
            }

            if (
                Regex(
                    "static\\s+(final\\s+)?" +
                            "(ExecutorService|ScheduledExecutorService|ThreadLocal)"
                ).containsMatchIn(text) &&
                rel !in mixinGuardAllowlist &&
                rel !in runtimeAllowlist
            ) {
                failures.add(
                    "$rel: static executor/thread-local outside the allowlist"
                )
            }

            if (
                text.contains("import tools.jackson") &&
                rel !in jsonCodecAllowlist
            ) {
                failures.add(
                    "$rel: tools.jackson import outside the JSON codec boundary"
                )
            }

            if (
                text.contains("tools.jackson.databind") ||
                text.contains("ObjectMapper")
            ) {
                failures.add(
                    "$rel: Jackson Databind/ObjectMapper is forbidden (Core streaming only)"
                )
            }

            if (
                text.contains("import com.google.gson") &&
                !rel.startsWith("minecraft/")
            ) {
                failures.add(
                    "$rel: com.google.gson import allowed only in the Minecraft adapter layer"
                )
            }

            if (
                text.contains(
                    "import top.tangge233.netbridge.nativebridge.internal.ffm."
                ) &&
                rel !in runtimeAllowlist &&
                !rel.startsWith(ffmAllowlistPrefix)
            ) {
                failures.add(
                    "$rel: reaching into nativebridge.internal.ffm from outside " +
                            "the FFM layer (composition root only)"
                )
            }
        }

        val common = javaMainSources("common/src/main/java")

        for (file in common) {
            val rel = file.relativeTo(rootDir).path.replace('\\', '/')

            if (
                Regex("import\\s+net\\.(minecraft|fabricmc|neoforged)")
                        .containsMatchIn(file.readText())
            ) {
                failures.add(
                    "$rel: common must not import Minecraft or loader APIs"
                )
            }
        }

        for (file in javaMainSources("minecraft/src/main/java")) {
            val rel = file.relativeTo(rootDir).path.replace('\\', '/')

            if (
                Regex("import\\s+net\\.(fabricmc|neoforged)")
                        .containsMatchIn(file.readText())
            ) {
                failures.add(
                    "$rel: shared minecraft layer must not import loader APIs"
                )
            }
        }

        val loaderDupDirs = listOf(
            "fabric/src/main/java/top/tangge233/netbridge/fabric/mc",
            "neoforge/src/main/java/top/tangge233/netbridge/neoforge/mc",
            "neoforge/src/main/java/top/tangge233/netbridge/neoforge/mixin"
        )

        for (dir in loaderDupDirs) {
            if (rootDir.resolve(dir).isDirectory) {
                failures.add(
                    "$dir: duplicated loader copy directory must not exist " +
                            "(shared source set only)"
                )
            }
        }

        val pkgRoots = listOf(
            "common/src/main/java/top/tangge233/netbridge",
            "minecraft/src/main/java/top/tangge233/netbridge"
        )

        for (rootPath in pkgRoots) {
            val root = rootDir.resolve(rootPath)

            if (!root.isDirectory) {
                continue
            }

            val packages = root.walkTopDown()
                    .filter {
                        it.isDirectory &&
                                it.listFiles { file: File ->
                                    file.name.endsWith(".java")
                                }?.isNotEmpty() == true
                    }
                    .toList()

            for (pkg in packages) {
                val hasInfo = pkg.resolve("package-info.java").isFile

                if (!hasInfo) {
                    val rel = pkg.relativeTo(rootDir)
                            .path
                            .replace('\\', '/')

                    failures.add(
                        "$rel: missing package-info.java (@NullMarked required)"
                    )
                }
            }
        }

        for (file in production) {
            if (file.readText().contains("@NullUnmarked")) {
                failures.add(
                    "${file.relativeTo(rootDir).path}: @NullUnmarked is forbidden"
                )
            }
        }

        val coreSrc = rootDir.resolve("rust/crates/net-bridge-core/src")

        if (coreSrc.isDirectory) {
            coreSrc.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".rs") }
                    .forEach { file ->
                        val rel = file.relativeTo(rootDir).path.replace('\\', '/')
                        val text = file.readText()

                        if (
                            text.contains("extern \"C\"") ||
                            text.contains("use jni") ||
                            text.contains("JavaVM")
                        ) {
                            failures.add(
                                "$rel: FFI/JNI constructs are forbidden in net-bridge-core"
                            )
                        }

                        if (
                            Regex(
                                "(?m)^static\\s+(mut\\s+)?" +
                                        "(RUNTIME|CONNS|SERVERS|NEXT_ID|EVENT_SINK)\\b"
                            ).containsMatchIn(text)
                        ) {
                            failures.add(
                                "$rel: process-global native registries are forbidden " +
                                        "in net-bridge-core"
                            )
                        }

                        if (
                            text.contains("BridgeError::Other") ||
                            text.contains("Other(")
                        ) {
                            failures.add(
                                "$rel: BridgeError::Other escape hatch is forbidden " +
                                        "in net-bridge-core"
                            )
                        }

                        if (
                            !rel.contains("tests") &&
                            !rel.contains("context.rs") &&
                            !rel.contains("lib.rs") &&
                            text.contains(".on_event(")
                        ) {
                            failures.add(
                                "$rel: direct on_event call is forbidden; " +
                                        "use unified event publisher"
                            )
                        }

                        if (
                            rel.startsWith("rust/crates/net-bridge-core/src/transport/") &&
                            !rel.contains("tests") &&
                            text.contains("tokio::spawn")
                        ) {
                            failures.add(
                                "$rel: raw tokio::spawn in transports is forbidden"
                            )
                        }
                    }
        }

        val coreCargo = rootDir.resolve("rust/crates/net-bridge-core/Cargo.toml")

        if (coreCargo.readText().contains("jni")) {
            failures.add(
                "rust/crates/net-bridge-core/Cargo.toml: " +
                        "jni dependency is forbidden in core"
            )
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                "Architecture verification failed:\n  " +
                        failures.joinToString("\n  ")
            )
        }

        logger.lifecycle(
            "architecture verification OK (${production.size} java files scanned)"
        )
    }
}

val fabricJarConfig = configurations.create("fabricJarConfig") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val neoforgeJarConfig = configurations.create("neoforgeJarConfig") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    fabricJarConfig(
        project(
            mapOf(
                "path" to ":fabric",
                "configuration" to "fabricJarConfig"
            )
        )
    )

    neoforgeJarConfig(
        project(
            mapOf(
                "path" to ":neoforge",
                "configuration" to "neoforgeJarConfig"
            )
        )
    )
}

tasks.register<Copy>("assembleAll") {
    group = "build"
    description = "Assembles both platform mod jars into root build/libs/."

    dependsOn(tasks.named("buildCdylib"))
    dependsOn(generateNativeManifest)
    dependsOn(verifyNativeSymbols)
    dependsOn(verifyArchitecture)

    from(fabricJarConfig) {
        rename { "net-bridge-fabric-${project.version}.jar" }
    }

    from(neoforgeJarConfig) {
        rename { "net-bridge-neoforge-${project.version}.jar" }
    }

    into(layout.buildDirectory.dir("libs"))
}

tasks.register("printVersions") {
    doLast {
        val mcVer = libs.versions.minecraft.get()
        val neoVer = libs.versions.neoforge.asProvider().get()

        println("Minecraft: $mcVer (NeoForge $neoVer / Fabric $mcVer)")
    }
}
