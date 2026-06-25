# Configuration

## Gradle DSL

```kotlin
potassium.application {
    mainClass = "com.example.MainKt"

    graalvm {
        isEnabled = true
        imageName = "my-app"

        // Gradle Java Toolchain: auto-downloads Liberica NIK 25
        // if it's not already installed on the machine.
        // In CI, the JDK is set up by graalvm/setup-graalvm@v1 instead.
        javaLanguageVersion = 25
        jvmVendor = JvmVendorSpec.BELLSOFT

        buildArgs.addAll(
            "-H:+AddAllCharsets",
            "-Djava.awt.headless=false",
            "-Os",
            "-H:-IncludeMethodData",
        )

        // Optional: customize Oracle Reachability Metadata Repository
        metadataRepository {
            enabled = true              // default
            version = "0.10.6"          // default
            excludedModules.add("com.example:my-lib")
        }

        // Optional: point to your own app-specific metadata
        nativeImageConfigBaseDir.set(
            layout.projectDirectory.dir("src/main/graalvm-config"),
        )
    }
}
```

!!! info "About `nativeImageConfigBaseDir`"
    Potassium ships all generic and platform-specific reflection metadata automatically. The `nativeImageConfigBaseDir` is only needed if you have app-specific entries that the automatic metadata doesn't cover — which is rare.

## DSL Reference

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `isEnabled` | `Boolean` | `false` | Enable GraalVM native compilation |
| `javaLanguageVersion` | `Int` | `25` | Gradle toolchain language version — triggers auto-download of the matching JDK if not installed locally |
| `jvmVendor` | `JvmVendorSpec` | — | Gradle toolchain vendor filter — set to `BELLSOFT` to auto-provision Liberica NIK |
| `imageName` | `String` | project name | Output executable name |
| `march` | `String` | `"native"` | CPU architecture target (`native` for current CPU, `compatibility` for broad compatibility) |
| `buildArgs` | `ListProperty<String>` | empty | Extra arguments passed to `native-image` |
| `nativeImageConfigBaseDir` | `DirectoryProperty` | — | Directory containing app-specific `reachability-metadata.json` (optional — generic/platform metadata is built-in) |
| `metadataRepository` | `MetadataRepositorySettings` | enabled | Oracle GraalVM Reachability Metadata Repository settings (see below) |

### `metadataRepository` DSL Reference

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | `Boolean` | `true` | Whether to auto-resolve metadata from the Oracle repository for classpath dependencies |
| `version` | `String` | `"0.10.6"` | Version of the metadata repository artifact |
| `excludedModules` | `SetProperty<String>` | empty | Module coordinates (`group:artifact`) to exclude from repository resolution |
| `moduleToConfigVersion` | `MapProperty<String, String>` | empty | Override the metadata version for specific modules (key: `group:artifact`, value: version directory) |

## Recommended build arguments

| Argument | Purpose |
|----------|---------|
| `-H:+AddAllCharsets` | Include all character sets (required for text I/O) |
| `-Djava.awt.headless=false` | Enable GUI support (mandatory for desktop apps) |
| `-Os` | Optimize for binary size |
| `-H:-IncludeMethodData` | Reduce binary size by excluding method metadata |

## No Release Build Type

Unlike standard JVM builds, GraalVM native-image builds **do not have a release variant**. There is no `packageReleaseGraalvmNative` or `runReleaseGraalvmNative` task. This is intentional:

- **ProGuard is redundant** — GraalVM native-image already performs closed-world dead code elimination at compile time. Running ProGuard beforehand provides no additional size benefit.
- **ProGuard is harmful** — ProGuard can rename or remove classes that are referenced in `reachability-metadata.json`, causing runtime crashes. Maintaining both ProGuard keep rules and reflection metadata is error-prone.

All GraalVM tasks use the default (non-ProGuard) build type. Use `-Os` in `buildArgs` for size optimization.
