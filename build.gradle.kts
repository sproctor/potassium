import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "com.seanproctor"
version =
    providers
        .environmentVariable("GITHUB_REF")
        .orNull
        ?.removePrefix("refs/tags/v")
        ?: "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

mavenPublishing {
    coordinates("com.seanproctor", "potassium-updater", version.toString())

    pom {
        name.set("Potassium Updater")
        description.set(
            "Standalone auto-update library for Compose/JVM desktop apps " +
                "(a fork of the Nucleus updater-runtime).",
        )
        url.set("https://github.com/sproctor/potassium-updater")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("sproctor")
                name.set("Sean Proctor")
                url.set("https://github.com/sproctor")
            }
            developer {
                id.set("kdroidfilter")
                name.set("kdroidFilter")
                url.set("https://github.com/kdroidFilter")
            }
        }

        scm {
            val vcsUrl = "https://github.com/sproctor/potassium-updater"
            url.set(vcsUrl)
            connection.set("scm:git:$vcsUrl")
            developerConnection.set("scm:git:$vcsUrl")
        }
    }

    publishToMavenCentral()
    // Signing is required for Maven Central but skipped for local publishes (no key present).
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
}
