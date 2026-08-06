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
        // .so payload small and avoids compiling whisper.cpp for ABIs no
        // target device uses; add others here if you widen device support.
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // ggml pulls in a lot of optional backends by default; we only
                // want CPU. Shared STL because two .so files (ggml + our JNI)
                // link against it.
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_OPENMP=OFF",
                    // ggml's ARM feature detection uses check_cxx_source_RUNS,
                    // which cannot work when cross-compiling — the host can't
                    // execute ARM test binaries. Left to itself it silently
                    // finds nothing (HAVE_DOTPROD et al. come out empty) and
                    // falls back to slow scalar matmul paths, which measured
                    // ~4x slower than the ONNX backend on the same model.
                    // GGML_CPU_ARM_ARCH bypasses the probing and sets -march
                    // directly.
                    //
                    // Exynos 1380 = 4x Cortex-A78 + 4x Cortex-A55. Both are
                    // ARMv8.2-A with FEAT_DotProd and FEAT_FP16. Neither has
                    // i8mm/SMMLA (ARMv8.6), so it is deliberately not listed —
                    // requesting it would build instructions this SoC traps on.
                    "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16",
                )
                cppFlags += "-fexceptions"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    // AGP's default NDK for 9.1.1; pinned so a machine with several installed
    // doesn't silently pick a different one.
    ndkVersion = "28.2.13676358"

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
    implementation(libs.onnxruntime.android)
    implementation(libs.onnxruntime.extensions.android)
    testImplementation(libs.junit)
}
