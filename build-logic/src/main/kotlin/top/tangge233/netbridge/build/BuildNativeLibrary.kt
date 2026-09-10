package top.tangge233.netbridge.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

@DisableCachingByDefault(
    because = "Cargo manages its own incremental compilation cache"
)
abstract class BuildNativeLibrary @Inject constructor(
    private val execOperations: ExecOperations
) : DefaultTask() {

    @get:Input
    abstract val profile: Property<String>

    @get:Input
    abstract val skipNativeBuild: Property<Boolean>

    @get:Input
    abstract val crateName: Property<String>

    @get:Input
    abstract val libraryBaseName: Property<String>

    @get:InputDirectory
    abstract val cargoDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        profile.convention("debug")
        skipNativeBuild.convention(false)
        crateName.convention("net-bridge-native")
        libraryBaseName.convention("net_bridge_native")
        onlyIf { !skipNativeBuild.get() }
    }

    @TaskAction
    fun build() {
        val prof = profile.get()
        if (prof !in listOf("debug", "release")) {
            throw GradleException("nativeProfile must be debug or release, got: $prof")
        }

        val crate = crateName.get()
        if (crate.isBlank()) {
            throw GradleException("crateName must not be blank")
        }

        val base = libraryBaseName.get()
        if (base.isBlank()) {
            throw GradleException("libraryBaseName must not be blank")
        }

        val artifact = NativePlatform.cdylibNameFor(base)

        val cargoDirectory = cargoDir.get().asFile
        val cargoArgs = mutableListOf("cargo", "build", "-p", crate)
        if (prof == "release") {
            cargoArgs.add("--release")
        }

        val execResult = execOperations.exec {
            workingDir(cargoDirectory)
            commandLine(cargoArgs)
        }
        if (execResult.exitValue != 0) {
            throw GradleException("cargo build failed with exit code ${execResult.exitValue}")
        }

        val srcCdylib = cargoDirectory.resolve("target/$prof/$artifact")
        if (!srcCdylib.exists()) {
            throw GradleException("Rust cdylib not found after cargo build: $srcCdylib")
        }

        val targetDir = outputDir.get().asFile.resolve(NativePlatform.subdir)
        targetDir.mkdirs()

        val dst = targetDir.resolve(artifact)
        Files.copy(
            srcCdylib.toPath(),
            dst.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
        logger.lifecycle("copied native ($crate, $prof): $srcCdylib -> $dst")
    }

}
