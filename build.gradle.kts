import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    alias(libs.plugins.kotlinJvm)
    id("org.jetbrains.intellij.platform") version "2.20.1"     // See https://github.com/JetBrains/intellij-platform-gradle-plugin/releases
}

val ProductVersion: String by project
val PluginSinceBuild: String by project
val PublishToken: String by project

version = extra["PluginVersion"] as String

repositories {
    maven { setUrl("https://cache-redirector.jetbrains.com/maven-central") }
    intellijPlatform {
        defaultRepositories()
    }
}

tasks.wrapper {
    gradleVersion = "9.7.1"
    distributionType = Wrapper.DistributionType.ALL
    distributionUrl = "https://cache-redirector.jetbrains.com/services.gradle.org/distributions/gradle-${gradleVersion}-all.zip"
}

dependencies {
    intellijPlatform {
        rider(ProductVersion) { useInstaller.set(false) }
        // Optional at runtime (see plugin.xml): only used to read the current branch name.
        bundledPlugin("Git4Idea")
        // Git4Idea's repository types extend these, so the compiler needs them on the classpath.
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        bundledModule("intellij.platform.vcs.dvcs.impl.shared")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(libs.junit)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = PluginSinceBuild
            untilBuild = provider { null }
        }

        // The first section of CHANGELOG.md becomes the change notes shown on the Marketplace.
        changeNotes = provider {
            val changelogText = file("${rootDir}/CHANGELOG.md").readText()
            Regex("(?s)(-.+?)(?=##|$)").findAll(changelogText)
                .map { it.groups[1]!!.value.replace("(?s)\r?\n".toRegex(), "<br />\n") }
                .take(1)
                .joinToString()
        }
    }

    publishing {
        token = PublishToken
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks.runIde {
    // Match Rider's default heap size of 1.5Gb (default for runIde is 512Mb)
    maxHeapSize = "1500m"
}
