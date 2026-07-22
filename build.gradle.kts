import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    id("dev.kikugie.stonecutter")
    // architectury-loom 1.17: consumes the newer Litematica/MaLiLib (built with
    // Loom 1.16.3+). NOTE: 26.x is NOT yet buildable — Loom 1.17's layered-mappings
    // factory NPEs on the deobfuscated 26.x jar's identity mappings (both
    // architectury and plain fabric-loom flavors). See the 26.x scaffolding below
    // (dormant until that mapping config is solved).
    id("dev.architectury.loom") version "1.17.491"
    java
}

// Parse the Stonecutter node: "1.21.1-fabric" -> mcVersion="1.21.1", loader="fabric".
// This mod is Fabric-only, but we keep the "-fabric" suffix so the layout mirrors
// the team's other Stonecutter mods.
val mcVersion = stonecutter.current.project.substringBeforeLast("-")

// Numeric MC version for comparisons (e.g. 1.21.4 -> 12104).
val mcParts = mcVersion.split(".").map { it.toIntOrNull() ?: 0 }
val mcVersionNum = mcParts[0] * 10000 + mcParts.getOrElse(1) { 0 } * 100 + mcParts.getOrElse(2) { 0 }

// MC 26.0+ ships a deobfuscated jar (Mojang dropped published mappings), so it
// uses identity mappings + Java 25 instead of Yarn. See the hacks below.
val is26Plus = mcVersionNum >= 260000

// Java toolchain: 1.20.1/1.20.4 on 17, 1.20.5+ (all 1.21.x) on 21, 26.x on 25.
val javaVersion = when {
    mcVersionNum >= 260000 -> 25
    mcVersionNum >= 12005  -> 21
    else                   -> 17
}

val modId: String by project
val modName: String by project
val modVersion: String by project
val modGroup: String by project

group = modGroup
version = "$modVersion+$mcVersion-fabric"

base {
    archivesName.set("$modId-fabric")
}

// Per-version dependency pins (see versions/<node>/gradle.properties).
val fabricLoaderVersion: String by project
val fabricApiVersion: String by project
val yarnMappings: String? by project // absent on 26.x (identity mappings)
val litematicaVersion: String by project
val malilibVersion: String by project

repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://maven.architectury.dev/")
    maven("https://masa.dy.fi/maven")
    maven("https://api.modrinth.com/maven") {
        name = "Modrinth"
        content { includeGroup("maven.modrinth") }
    }
    mavenCentral()
}

// 26.x hack — Fabric publishes the intermediary POM with version 0.0.0, which
// breaks Gradle metadata resolution. Generate a local identity intermediary with
// the real version + v2 tiny format. (Fabric-only; render-mod does the same.)
if (is26Plus) {
    val localMaven = rootDir.resolve(".gradle/local-maven")
    val intermediaryDir = localMaven.resolve("net/fabricmc/intermediary/$mcVersion")
    val jarFile = intermediaryDir.resolve("intermediary-$mcVersion-v2.jar")
    if (!jarFile.exists()) {
        intermediaryDir.mkdirs()
        intermediaryDir.resolve("intermediary-$mcVersion.pom").writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <modelVersion>4.0.0</modelVersion>
              <groupId>net.fabricmc</groupId>
              <artifactId>intermediary</artifactId>
              <version>$mcVersion</version>
            </project>
        """.trimIndent())
        ZipOutputStream(jarFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mappings/mappings.tiny"))
            zip.write("tiny\t2\t0\tofficial\tintermediary\n".toByteArray())
            zip.closeEntry()
        }
    }
    repositories {
        maven(localMaven) {
            name = "LocalIntermediary"
            content { includeModule("net.fabricmc", "intermediary") }
        }
    }
}

loom {
    runConfigs.all {
        ideConfigGenerated(true)
        runDir = "../../run"
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")

    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")

    @Suppress("UnstableApiUsage")
    if (is26Plus) {
        // Deobfuscated jar → identity mappings, and the remap pipeline is off
        // (nothing to remap), so mod deps are plain `implementation`.
        mappings(loom.layered { })
        implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
        implementation("maven.modrinth:litematica:$litematicaVersion")
        implementation("maven.modrinth:malilib:$malilibVersion")
    } else {
        // Litematica / MaLiLib are consumed with Yarn intermediary in dev (masa
        // convention); Loom remaps the mod jars intermediary -> Yarn.
        mappings("net.fabricmc:yarn:$yarnMappings:v2")
        modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
        modImplementation("maven.modrinth:litematica:$litematicaVersion")
        modImplementation("maven.modrinth:malilib:$malilibVersion")
    }
}

java {
    val target = JavaVersion.toVersion(javaVersion)
    sourceCompatibility = target
    targetCompatibility = target
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
}

tasks.withType<JavaCompile> {
    options.release.set(javaVersion)
    options.encoding = "UTF-8"
}

// Upper-bound the Minecraft dependency to the next patch so a per-version jar
// (each built against ONE MC version) can't be loaded on a different MC/
// Litematica install: e.g. mcVersion "1.20.1" -> "1.20.2". Litematica/MaLiLib
// pull in their own minecraft dep, so bounding minecraft here is the real
// cross-version guard; their pins below just require the compiled API level.
val mcVersionNextPatch: String = run {
    val parts = mcVersion.split(".").toMutableList()
    val last = parts.last().toIntOrNull()
    if (last != null) { parts[parts.size - 1] = (last + 1).toString(); parts.joinToString(".") }
    else mcVersion
}

tasks.processResources {
    val props = mapOf(
        "mod_id" to modId,
        "mod_name" to modName,
        "mod_version" to version,
        "mod_description" to (project.findProperty("modDescription") ?: ""),
        "mod_author" to (project.findProperty("modAuthor") ?: ""),
        "mod_license" to (project.findProperty("modLicense") ?: ""),
        "mc_version" to mcVersion,
        "mc_version_next_patch" to mcVersionNextPatch,
        "java_version" to javaVersion,
        "fabric_loader_version" to fabricLoaderVersion,
        "litematica_version" to litematicaVersion,
        "malilib_version" to malilibVersion,
    )

    inputs.properties(props)
    // Both files carry per-version placeholders (fabric.mod.json: deps; mixins
    // json: compatibilityLevel = JAVA_17 for 1.20.x / JAVA_21 for 1.21.x, so it
    // matches the actual bytecode level of each jar instead of a hardcoded 17).
    filesMatching(listOf("fabric.mod.json", "phantom-market-link.mixins.json")) { expand(props) }
}

// Stonecutter preprocessor constants for version-gated source.
stonecutter {
    const("fabric", true)
    const("mc1204", mcVersionNum >= 12004)   // 1.20.4+
    const("mc1211", mcVersionNum >= 12101)    // 1.21.1+
    const("mc1214", mcVersionNum >= 12104)    // 1.21.4+
    // 1.21.11+: Litematica migrated File→Path (getSchematicsBaseDirectory,
    // createFromFile) and vanilla renamed GameVersion.getName()→name() and
    // Entity.getPos()→getEntityPos().
    const("mc12111", mcVersionNum >= 12111)  // 1.21.11+
    // 26.x: deobfuscated jar → vanilla uses Mojang names (Minecraft, level,
    // position(), BlockPos.containing, Util.getPlatform().openUri, etc.).
    const("mc26", mcVersionNum >= 260000)
}
