import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runtimeDir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")
val legalDir = layout.projectDirectory.dir("src/main/assets/legal")

val verifyEmbeddedRuntime by tasks.registering {
    group = "verification"
    description = "Verify Omochi's Android/Bionic PRoot runtime and corresponding sources"

    val requiredRuntime = listOf(
        runtimeDir.file("libproot.so") to 100_000L,
        runtimeDir.file("libproot-loader.so") to 1_000L,
        runtimeDir.file("libandroid-shmem.so") to 1_000L,
        runtimeDir.file("libtalloc.so") to 10_000L,
    )
    val requiredLegal = listOf(
        "NOTICE.txt",
        "SOURCE-AND-LICENSE-MANIFEST.sha256",
        "licenses/APACHE-2.0.txt",
        "licenses/GPL-2.0.txt",
        "licenses/LGPL-3.0.txt",
        "licenses/BSD-3-Clause-libandroid-shmem.txt",
        "licenses/COMMONS-COMPRESS-NOTICE.txt",
        "sources/proot-v5.1.107.92.zip",
        "sources/libandroid-shmem-v0.7.tar.gz.source",
        "sources/talloc-2.4.3.tar.gz.source",
        "sources/ccfa-prepare-termux-android-proot.sh",
        "sources/termux-build-recipes/proot-build.sh",
        "sources/termux-build-recipes/libandroid-shmem-build.sh",
        "sources/termux-build-recipes/libtalloc-build.sh",
    )

    inputs.files(requiredRuntime.map { it.first })
    inputs.files(requiredLegal.map { legalDir.file(it) })

    doLast {
        requiredRuntime.forEach { (provider, minimum) ->
            val file = provider.asFile
            check(file.isFile && file.length() >= minimum) {
                "Missing or truncated embedded runtime component: ${file.name}"
            }
        }
        requiredLegal.forEach { relative ->
            val file = legalDir.file(relative).asFile
            check(file.isFile && file.length() > 0L) {
                "Missing distribution legal/source asset: $relative"
            }
        }

        val inventory = legalDir.file("SOURCE-AND-LICENSE-MANIFEST.sha256").asFile
        inventory.readLines()
            .filter { it.isNotBlank() && !it.startsWith('#') }
            .forEach { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                check(parts.size == 2 && parts[0].matches(Regex("[0-9a-f]{64}"))) {
                    "Malformed source/license inventory line: $line"
                }
                val relative = parts[1]
                val file = layout.projectDirectory.file("src/main/$relative").asFile
                check(file.isFile) { "Inventory entry is missing: $relative" }
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == parts[0]) {
                    "Source/license inventory mismatch for $relative: expected ${parts[0]}, got $actual"
                }
            }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(verifyEmbeddedRuntime)
}

android {
    namespace = "io.github.hatake716.omochi"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.hatake716.omochi"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        buildConfigField("String", "CODE_SERVER_VERSION", "\"4.133.0\"")
        buildConfigField(
            "String",
            "UBUNTU_BASE_SHA256",
            "\"04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2\"",
        )
        buildConfigField(
            "String",
            "CODE_SERVER_ARCHIVE_SHA256",
            "\"d999d8b0256e5537f3b62e6c09f624220026e19107a04a876c0cef62d1c71147\"",
        )
        buildConfigField(
            "String",
            "CODE_SERVER_ARCHIVE_URL",
            "\"https://github.com/coder/code-server/releases/download/v4.133.0/code-server-4.133.0-linux-arm64.tar.gz\"",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libproot.so"
            keepDebugSymbols += "**/libproot-loader.so"
            keepDebugSymbols += "**/libandroid-shmem.so"
            keepDebugSymbols += "**/libtalloc.so"
        }
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
        )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // The embedded PRoot runtime is intentionally ARM64-only. x86_64 cannot
        // be declared until equivalent, redistributable native binaries exist.
        disable += "ChromeOsAbiSupport"
        // AndroidX's 2026.08 line requires compileSdk 37 and AGP 9.1. Keep the
        // latest versions validated with this project's SDK 36/AGP 8.13 toolchain.
        disable += "GradleDependency"
        disable += "NewerVersionAvailable"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.apache.commons:commons-compress:1.28.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
