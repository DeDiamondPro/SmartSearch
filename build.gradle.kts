import net.fabricmc.loom.build.nesting.NestableJarGenerationTask
import de.undercouch.gradle.tasks.download.Download
import de.undercouch.gradle.tasks.download.Verify
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.compose") version "2.4.0"
    id("net.fabricmc.fabric-loom")
    id("de.undercouch.download") version "5.7.0"
    id("me.modmuss50.mod-publish-plugin") version "2.2.0"
}

group = "org.polyfrost"
version = "1.1.0"

repositories {
    mavenCentral()
    mavenLocal()
    google()
    maven("https://redirector.kotlinlang.org/maven/compose-dev")
    maven("https://repo.polyfrost.org/releases")
    maven("https://repo.polyfrost.org/snapshots")
    maven("https://central.sonatype.com/repository/maven-snapshots") {
        content { includeGroup("net.kyori") }
    }
    maven("https://maven.terraformersmc.com/releases/") {
        content { includeGroup("com.terraformersmc") }
    }
    maven("https://repo.hypixel.net/repository/Hypixel/") {
        content { includeGroup("net.hypixel") }
    }
    maven("https://api.modrinth.com/maven") {
        content { includeGroup("maven.modrinth") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.19.3")

    val oneConfigVersion = "1.2.7"
    runtimeOnly("org.polyfrost.oneconfig:26.3-fabric:$oneConfigVersion")
    for (module in listOf("config", "config-impl", "events", "internal")) {
        implementation("org.polyfrost.oneconfig:$module:$oneConfigVersion")
    }

    // Include langchain4j BOM
    implementation(platform(libs.langchain4j.bom))
    include(platform(libs.langchain4j.bom))
    implementation(libs.bundles.embedding) {
        isTransitive = false
    }
    include(libs.bundles.embedding)

    // Include lucene
    implementation(libs.bundles.database) {
        isTransitive = false
    }
    include(libs.bundles.database)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

// Allow us to run 26.2+
configurations.runtimeClasspath {
    attributes {
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}
configurations.testRuntimeClasspath {
    exclude(group = "org.polyfrost.oneconfig", module = "26.3-fabric")
}

tasks.test {
    useJUnitPlatform()
    // Forward -Deval.* to the test JVM so the search evaluation sweep can be switched on from the command line.
    systemProperties(providers.systemPropertiesPrefixedBy("eval.").get())
    testLogging {
        showStandardStreams = true
    }
}

val embeddingModelFiles = mapOf(
    "onnx/model_int8.onnx" to "e6aa5e656466a73d7c3111e9a3378bd13e5b93af30eaac2b3f13fd56692589a1",
    "tokenizer.json" to "91f1def9b9391fdabe028cd3f3fcc4efd34e5d1f08c3bf2de513ebb5911a1854",
)
val embeddingModelDir = layout.buildDirectory.dir("downloaded-models/models/arctic-embed-xs")

val downloadEmbeddingModel by tasks.registering(Download::class) {
    // Download the model used
    src(embeddingModelFiles.keys.map { "https://huggingface.co/Snowflake/snowflake-arctic-embed-xs/resolve/main/$it" })
    dest(embeddingModelDir)
    overwrite(false)
    onlyIfModified(true)
    tempAndMove(true)
}

// Verify the hashes of the downloaded model
val verifyEmbeddingModel by tasks.registering {
    dependsOn(downloadEmbeddingModel)
}
for ((path, sha256) in embeddingModelFiles) {
    val name = path.substringAfterLast('/')
    val verifyTask = tasks.register<Verify>("verifyEmbeddingModel${name.replaceFirstChar { it.uppercase() }}") {
        dependsOn(downloadEmbeddingModel)
        src(embeddingModelDir.map { it.file(name) }.get().asFile)
        algorithm("SHA-256")
        checksum(sha256)
    }
    verifyEmbeddingModel { dependsOn(verifyTask) }
}

sourceSets.main {
    resources {
        srcDir(layout.buildDirectory.dir("downloaded-models"))
    }
}

tasks.processResources {
    dependsOn(verifyEmbeddingModel)

    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to "${project.version}+fabric")
    }
}

// Strip out Linux ARM natives, almost no consumer PCs run Linux on ARM right now
// these are mostly for servers, and stripping them out saves us like 10MB
val excludedEntries = listOf(
    "ai/onnxruntime/native/linux-aarch64/",
    "native/lib/linux-aarch64/cpu", // tokenizer lib
    // Exclude debug symbols, IDK why they are included
    "ai/onnxruntime/native/osx-aarch64/libonnxruntime.dylib.dSYM",
    "ai/onnxruntime/native/osx-aarch64/libonnxruntime4j_jni.dylib.dSYM",
)
tasks.named("processIncludeJars") {
    doLast {
        val outputDir = (this as NestableJarGenerationTask).outputDirectory.get().asFile
        outputDir.walkTopDown().filter { it.isFile && it.extension == "jar" }.forEach { jar ->
            val stripped = File(jar.parentFile, "${jar.name}.stripped")
            var removed = false
            ZipFile(jar).use { zip ->
                ZipOutputStream(stripped.outputStream().buffered()).use { out ->
                    for (entry in zip.entries()) {
                        if (excludedEntries.any { entry.name.startsWith(it) }) {
                            removed = true
                            continue
                        }
                        out.putNextEntry(ZipEntry(entry).apply { compressedSize = -1 })
                        if (!entry.isDirectory) zip.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
            if (removed) {
                stripped.copyTo(jar, overwrite = true)
            }
            stripped.delete()
        }
    }
}

val modrinthToken = listOf("oneconfig.publish.modrinth.token", "publish.modrinth.token", "modrinth.token")
    .firstNotNullOfOrNull { findProperty(it) }?.toString()?.takeIf { it.isNotBlank() }

// Modrinth requires distinct hashes per upload, so the Ornithe jar only differs in its version metadata
val ornitheJar by tasks.registering(Zip::class) {
    val jar = tasks.jar.flatMap { it.archiveFile }
    from(jar.map { zipTree(it) })
    filesMatching("fabric.mod.json") {
        filter { it.replace("${project.version}+fabric", "${project.version}+ornithe") }
    }
    destinationDirectory = tasks.jar.flatMap { it.destinationDirectory }
    archiveBaseName = tasks.jar.flatMap { it.archiveBaseName }
    archiveVersion = tasks.jar.flatMap { it.archiveVersion }
    archiveClassifier = "ornithe"
    archiveExtension = "jar"
}
tasks.assemble { dependsOn(ornitheJar) }

publishMods {
    changelog = rootProject.file("CHANGELOG.md").takeIf { it.exists() }?.readText() ?: "No changelog provided."
    type = STABLE

    dryRun = modrinthToken == null

    val modrinthOptions = modrinthOptions {
        projectId = "N5EQhK31"
        accessToken = modrinthToken.orEmpty()
        requires("oneconfig")
    }

    modrinth {
        from(modrinthOptions)
        file = tasks.jar.flatMap { it.archiveFile }
        displayName = "${project.version} (Fabric)"
        version = "v${project.version}+fabric"
        modLoaders.add("fabric")
        minecraftVersions.addAll("1.21.1", "1.21.4", "1.21.5", "1.21.8", "1.21.10", "1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3")
    }

    modrinth("modrinthOrnithe") {
        from(modrinthOptions)
        file = ornitheJar.flatMap { it.archiveFile }
        displayName = "${project.version} (Ornithe)"
        version = "v${project.version}+ornithe"
        modLoaders.add("ornithe")
        minecraftVersions.add("1.8.9")
    }
}