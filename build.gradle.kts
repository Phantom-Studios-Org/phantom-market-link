plugins {
    id("dev.kikugie.stonecutter")
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

// Java toolchain: 1.20.1/1.20.4 run on 17, 1.20.5+ (all 1.21.x) on 21.
val javaVersion = if (mcVersionNum >= 12005) 21 else 17

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
val yarnMappings: String by project
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

loom {
    runConfigs.all {
        ideConfigGenerated(true)
        runDir = "../../run"
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    // Litematica / MaLiLib are consumed with Yarn intermediary in dev (masa
    // convention); Loom remaps the mod jars intermediary -> Yarn.
    mappings("net.fabricmc:yarn:$yarnMappings:v2")

    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    modImplementation("maven.modrinth:litematica:$litematicaVersion")
    modImplementation("maven.modrinth:malilib:$malilibVersion")
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
}
