import com.seanproctor.potassium.dsl.LinuxTargetFormat
import com.seanproctor.potassium.dsl.MacOSTargetFormat
import com.seanproctor.potassium.dsl.WindowsTargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.jetbrainsCompose)
    // No version: resolved from the included build (../) via the composite build above.
    id("com.seanproctor.potassium")
}

kotlin {
    jvm()

    sourceSets {
        // Compose Multiplatform artifacts (material3 versions independently of the core libraries).
        // Versions live in gradle/libs.versions.toml.
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
        }
        jvmMain.dependencies {
            // The OS/arch-specific Compose Desktop artifact for the build machine.
            implementation(compose.desktop.currentOs)
        }
    }
}

potassium {
    mainClass = "com.example.sample.MainKt"
    packageName = "PotassiumSample"
    packageVersion = "1.0.0"
    description = "Potassium Packager Compose Multiplatform sample"
    vendor = "Potassium"
    // electron-builder requires a homepage when building Linux DEB/RPM packages.
    homepage = "https://github.com/sproctor/potassium"

    // Formats are grouped per OS. Each platform's formats build in one electron-builder
    // invocation (packageMacOS / packageWindows / packageLinux); only the current OS runs.
    macOS {
        targetFormats(MacOSTargetFormat.Dmg)
    }
    windows {
        targetFormats(WindowsTargetFormat.Nsis, WindowsTargetFormat.Msi)
    }
    linux {
        // electron-builder requires a maintainer ("Name <email>") for .deb packages.
        debMaintainer = "Potassium Sample <noreply@example.com>"
        targetFormats(LinuxTargetFormat.Deb, LinuxTargetFormat.Rpm, LinuxTargetFormat.AppImage)
    }
}
