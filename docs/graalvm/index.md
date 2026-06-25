# GraalVM Native Image

!!! warning "Alpha"
    GraalVM Native Image support is **in alpha**. Most Compose Desktop apps work out of the box thanks to centralized reachability metadata, but edge cases (uncommon libraries, custom reflection) may still require additional configuration.

## Why Native Image?

For most Compose Desktop applications, the JDK 25+ AOT cache (Leyden) is the recommended way to improve startup. It's simple to set up and provides a major boost. But there are cases where even Leyden isn't enough:

- **Background services / system tray apps** — a lightweight app that mostly sits idle in the background will consume **300–400 MB of RAM** on a JVM, versus **100–150 MB** as a native image. For an app that's always running, this matters.
- **Instant-launch expectations** — Leyden brings cold boot down to ~1.5 s, but a native image starts in ~0.5 s. For utilities, launchers, or CLI-like tools where every millisecond counts, native image is the way to go.
- **Bundle size** — no bundled JRE means a much smaller distributable.

GraalVM Native Image compiles your entire application **ahead of time** into a standalone native binary that feels truly native to the OS.

## Trade-offs

Native image is not a free lunch. In addition to significantly more complex configuration (reflection, see below), there is a real **CPU throughput penalty**: the JVM's JIT compiler optimizes hot loops and polymorphic calls at runtime far better than AOT compilation can. For CPU-intensive workloads (heavy computation, real-time rendering, large data processing), a JVM with Leyden AOT cache will outperform a native image in sustained throughput.

| | JVM + Leyden | Native Image |
|---|---|---|
| Cold boot | ~1.5 s | ~0.5 s |
| RAM (idle) | 300–400 MB | 100–150 MB |
| CPU throughput | Excellent (JIT) | Lower (no JIT) |
| Bundle size | Larger (includes JRE) | Smaller |
| Configuration | Simple (`enableAotCache = true`) | Simplified (centralized metadata) |
| Stability | Stable | Alpha |

**Choose native image when** startup speed and memory footprint are critical and CPU throughput is secondary. **Choose Leyden when** you want the best balance of performance, simplicity, and stability.

## Requirements

### BellSoft Liberica NIK 25 (Full)

GraalVM Native Image compilation **requires [BellSoft Liberica NIK 25](https://bell-sw.com/liberica-native-image-kit/)** (full distribution, not lite). This is the only supported distribution — standard GraalVM CE does not include the AWT/Swing support needed for desktop GUI applications.

!!! failure "Will not work with other distributions"
    Using Oracle GraalVM, GraalVM CE, or Liberica NIK Lite will fail. Desktop GUI applications require the **full** Liberica NIK distribution which includes AWT and Swing native-image support.

### Platform toolchains

| Platform | Required |
|----------|----------|
| **macOS** | Xcode Command Line Tools (Xcode 26 for macOS 26 appearance) |
| **Windows** | MSVC (Visual Studio Build Tools) — `ilammy/msvc-dev-cmd` in CI |
| **Linux** | GCC, `patchelf`, `xvfb` (for headless compilation) |

## When to avoid native image

Some libraries and use cases make native image compilation **extremely difficult or impractical**. Potassium can handle most standard Compose Desktop dependencies automatically, but the following categories will likely require extensive manual configuration — or may not work at all:

!!! danger "Libraries that are very hard to support"

    - **Heavy JNA users** — Libraries that rely extensively on JNA (Java Native Access) for dynamic function calls. JNA's runtime proxy generation is fundamentally at odds with native-image's closed-world assumption. Examples: some system tray libraries, platform bridge libraries.
    - **Full-text search engines** — Apache Lucene, Elasticsearch client, and similar libraries use heavy reflection, dynamic class loading, custom classloaders, and `MethodHandle`-based access patterns that are nearly impossible to capture statically.
    - **Dynamic scripting engines** — Embedding Groovy, JRuby, Nashorn, or other scripting runtimes that rely on runtime code generation.
    - **Annotation-processing frameworks at runtime** — Libraries like Spring that scan classpath annotations and create proxies at runtime. (Compile-time DI frameworks like Koin or manual DI are fine.)
    - **OSGi or custom classloaders** — Any library that loads classes through non-standard classloaders will bypass native-image's static analysis entirely.
    - **Byte-code generation at runtime** — Libraries using ByteBuddy, cglib, or ASM to generate classes at runtime (e.g., mocking frameworks, some ORM lazy-loading proxies).

If your application depends on libraries in these categories, **prefer AOT Cache (Leyden)** instead — it provides significant startup improvement with zero configuration overhead and full compatibility.

For everything else — ktor, kotlinx.serialization, Coil, SQLite, Jewel, Compose Multiplatform resources, SLF4J, and most idiomatic Kotlin libraries — Potassium handles native image transparently.

## Next steps

- [Configuration](configuration.md) — Gradle DSL and build arguments
- [Automatic Metadata](automatic-metadata.md) — How Potassium resolves reflection metadata transparently
- [Runtime Bootstrap](runtime-bootstrap.md) — `graalvm-runtime` module, initializer, font fixes, resource inclusion
- [Tasks & CI/CD](tasks-ci.md) — Gradle tasks, output locations, CI workflows, debugging
