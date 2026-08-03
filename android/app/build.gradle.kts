import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    // Kotlin compiled via AGP's built-in support (AGP 9+) — no separate
    // kotlin-android plugin. See docs/SETUP.md.
}

android {
    namespace = "dev.privatevoice.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.privatevoice.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-m0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Built-in Kotlin's jvmTarget defaults to this targetCompatibility, so
        // no separate kotlin { compilerOptions { ... } } block is needed.
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    // Explicit rather than transitive-via-appcompat: BenchmarkActivity uses
    // lifecycleScope, and that should not silently break on an appcompat bump.
    implementation(libs.androidx.lifecycle.runtime.ktx)
}

/**
 * The product's core claim is that audio cannot leave the device. That claim is
 * only as good as the merged manifest, which any transitive dependency can quietly
 * amend. So we assert it as a build step rather than trusting review.
 */
abstract class CheckNoInternetPermission : DefaultTask() {

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun check() {
        val manifest = mergedManifest.get().asFile
        val text = manifest.readText()
        val offenders = FORBIDDEN.filter { text.contains(it) }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Forbidden permission(s) in the merged manifest:")
                    offenders.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("This app must be incapable of network access. Find the")
                    appendLine("dependency that introduced it (./gradlew :app:dependencies)")
                    appendLine("and either drop it or strip the permission with")
                    appendLine("tools:node=\"remove\" in app/src/main/AndroidManifest.xml.")
                    appendLine()
                    appendLine("Merged manifest: ${manifest.absolutePath}")
                }
            )
        }
        logger.lifecycle("✓ no network permissions in merged manifest")
    }

    companion object {
        val FORBIDDEN = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
        )
    }
}

androidComponents {
    onVariants { variant ->
        val check = tasks.register<CheckNoInternetPermission>(
            "check${variant.name.replaceFirstChar { it.uppercase() }}NoInternet"
        ) {
            group = "verification"
            description = "Fails the build if the merged manifest requests network access."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        // Bind to assemble so a plain build cannot skip it. tasks.named() would
        // require assembleDebug to already be registered at this point in
        // configuration, which it isn't yet — matching+configureEach reacts as
        // AGP adds the task instead of requiring it to exist up front.
        val assembleTaskName = "assemble${variant.name.replaceFirstChar { it.uppercase() }}"
        tasks.matching { it.name == assembleTaskName }.configureEach {
            dependsOn(check)
        }
    }
}
