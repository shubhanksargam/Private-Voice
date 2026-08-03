plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.privatevoice.engine"
    compileSdk = 35

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
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            // Populated by tools/setup_sherpa.py — see docs/SETUP.md
            jniLibs.srcDirs("src/main/jniLibs")
            java.srcDirs("src/main/java", "src/main/vendor")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
