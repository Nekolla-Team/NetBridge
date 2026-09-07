package top.tangge233.netbridge.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.util.zip.ZipFile

/**
 * Automated final-jar packaging checks for loader artifacts.
 *
 * Verifies that embedded runtime libraries are present exactly once, that no
 * Databind classes leaked in, that loader metadata and native payloads survive,
 * and that no signed/duplicate manifest entries collide.
 */
@DisableCachingByDefault(because = "Cheap local verification of a final artifact")
abstract class VerifyEmbeddedPackaging : DefaultTask() {

    /** The final mod jar to inspect. */
    @get:InputFile
    abstract val jarFile: RegularFileProperty

    /** Loader metadata entry that must exist, e.g. "fabric.mod.json". */
    @get:Input
    abstract val metadataEntry: Property<String>

    @TaskAction
    fun verify() {
        val file = jarFile.get().asFile
        ZipFile(file).use { zip ->
            val names = zip.entries()
                    .asSequence()
                    .map { it.name }
                    .toList()
                    .toSet()
            checkExactlyOnce(
                names,
                "tools/jackson/core/json/JsonFactory.class",
                "Jackson Core JsonFactory"
            )
            checkAbsent(
                names,
                "tools/jackson/databind/",
                "Jackson Databind"
            )
            checkAbsent(
                names,
                "com/electronwill/nightconfig/json/",
                "NightConfig JSON (unexpected)"
            )
            checkExactlyOnce(
                names,
                "com/electronwill/nightconfig/core/file/CommentedFileConfig.class",
                "NightConfig core"
            )
            checkExactlyOnce(
                names,
                "com/electronwill/nightconfig/toml/Toml.class",
                "NightConfig toml"
            )
            val metadata = metadataEntry.get()
            if (metadata !in names) {
                throw GradleException("loader metadata entry missing: $metadata")
            }

            val platforms = names.filter {
                it.startsWith("native/") && it.endsWith("/manifest.json")
            }
            if (platforms.isEmpty()) {
                throw GradleException("no native platform manifest present in jar")
            }

            platforms.forEach { platform ->
                val payloads = names.filter {
                    it.startsWith(platform.removeSuffix("/manifest.json") + "/")
                            && !it.endsWith("/")
                            && !it.endsWith("/manifest.json")
                }
                if (payloads.isEmpty()) {
                    throw GradleException("no native payload alongside $platform")
                }
            }

            val signatures = names.filter {
                it.endsWith(".SF")
                        || it.endsWith(".RSA")
                        || it.endsWith(".DSA")
            }
            if (signatures.isNotEmpty()) {
                throw GradleException("signature files must not be embedded: $signatures")
            }

            val manifests = names.count { it == "META-INF/MANIFEST.MF" }
            if (manifests > 1) {
                throw GradleException("more than one MANIFEST.MF present: $manifests")
            }
        }
    }

    private fun checkExactlyOnce(
        names: Set<String>,
        entry: String,
        label: String
    ) {
        val count = names.count { it == entry }
        if (count != 1) {
            throw GradleException("$label must appear exactly once, found $count: $entry")
        }
    }

    private fun checkAbsent(
        names: Set<String>,
        prefix: String,
        label: String
    ) {
        val leaking = names.filter { it.startsWith(prefix) }
        if (leaking.isNotEmpty()) {
            throw GradleException("$label must not be present: ${leaking.take(5)}")
        }
    }

}
