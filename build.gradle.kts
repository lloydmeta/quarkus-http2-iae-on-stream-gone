plugins {
    java
    id("io.quarkus") version "3.37.3" // Inlined for Dependabot version management
}

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.38.0")) // Inlined for Dependabot version management
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-arc")
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("com.squareup.okhttp3:okhttp:5.4.0")
}

group = "com.beachape"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

// The IAE only fires when Vert.x issues an outbound DATA write *after* the client's
// RST_STREAM has been processed. A body that fits in a single writeData cannot reproduce it,
// so the served asset must be large enough to stream in many chunks (AsyncFile reads 8 KiB at
// a time). We generate it at build time to avoid committing a binary blob.
val largeAssetBytes = 4 * 1024 * 1024 // 4 MiB

val generateLargeAsset = tasks.register("generateLargeAsset") {
    val outputDir = layout.buildDirectory.dir("generated-resources/large-asset")
    outputs.dir(outputDir)
    inputs.property("size", largeAssetBytes)
    doLast {
        val target = outputDir.get().file("META-INF/resources/large-asset.bin").asFile
        target.parentFile.mkdirs()
        val chunk = ByteArray(8192) { (it % 251).toByte() }
        target.outputStream().buffered().use { out ->
            var written = 0
            while (written < largeAssetBytes) {
                val n = minOf(chunk.size, largeAssetBytes - written)
                out.write(chunk, 0, n)
                written += n
            }
        }
    }
}

sourceSets {
    main {
        resources.srcDir(generateLargeAsset)
    }
}

tasks.withType<Test> {
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}
