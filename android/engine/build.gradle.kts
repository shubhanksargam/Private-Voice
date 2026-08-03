plugins {
    alias(libs.plugins.android.library)
    // Kotlin compiled via AGP's built-in support (AGP 9+) — no separate
    // kotlin-android plugin. See docs/SETUP.md.
}

android {
    namespace = "dev.privatevoice.engine"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        // The A35 is arm64. Restricting ABIs keeps the vendored sherpa-onnx
        // .so payload small; add others here if you widen device support.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Built-in Kotlin's jvmTarget defaults to this targetCompatibility, so
        // no separate kotlin { compilerOptions { ... } } block is needed.
    }

    sourceSets {
        getByName("main") {
            // Populated by tools/setup_sherpa.py — see docs/SETUP.md.
            // Under AGP's built-in Kotlin support, extra Kotlin sources must be
            // added via the `kotlin` source-set property, not `java.srcDirs` —
            // the latter no longer feeds the Kotlin compile task.
            jniLibs.directories += "src/main/jniLibs"
            kotlin.directories += "src/main/vendor"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
