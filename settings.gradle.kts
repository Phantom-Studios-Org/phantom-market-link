pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev/")
        // architectury-loom's plugin classpath pulls Forge tooling (DiffPatch, mcinjector)
        // even for Fabric-only builds, so these mavens are required at buildscript time.
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.neoforged.net/releases/")
        maven("https://maven.kikugie.dev/releases")
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.5.1"
}

stonecutter {
    kotlinController = true
    centralScript = "build.gradle.kts"

    create(rootProject) {
        // Fabric only. PhantomMarket Link is a client-side Litematica addon.
        versions(
            "1.20.1-fabric",
            "1.20.4-fabric",
            "1.21.1-fabric",
            "1.21.4-fabric",
            "1.21.11-fabric",
        )
    }
}
