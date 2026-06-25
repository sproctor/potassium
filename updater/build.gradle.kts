import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.vanniktechMavenPublish)
}

// group / version are inherited from the root build's allprojects {} block
// (single-version model, derived from GITHUB_REF). Repositories come from settings.gradle.kts.

dependencies {
    api(libs.coroutines.core)
    testImplementation(libs.junit)
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
    coordinates(property("GROUP").toString(), "potassium-updater", project.version.toString())

    pom {
        name.set("Potassium Updater")
        description.set(
            "Standalone auto-update library for Compose/JVM desktop apps " +
                "(a fork of the Nucleus updater-runtime).",
        )
        url.set(property("WEBSITE").toString())

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
            val vcsUrl = property("VCS_URL").toString()
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
