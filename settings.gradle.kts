pluginManagement {
    repositories {
        maven { setUrl("https://cache-redirector.jetbrains.com/plugins.gradle.org") }
        maven { setUrl("https://cache-redirector.jetbrains.com/maven-central") }
    }
}

plugins {
    // Lets Gradle auto-provision whichever JDK toolchain the targeted IntelliJ Platform requires
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "tempo"
