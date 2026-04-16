import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

// Resolve sandbox URLs.
//
// Both build types may be overridden via `local.properties`:
//   sandbox.base.url.debug=...     (default http://10.0.2.2:8080 — emulator → host)
//   sandbox.base.url.release=...   (default https://api.landolisp.dev — placeholder)
//
// `sandbox.base.url` (no suffix) is honored as a fallback that overrides BOTH for
// developers who only run one variant.
val localProps: Properties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun resolveSandboxUrl(buildType: String, default: String): String {
    val keyed = localProps.getProperty("sandbox.base.url.$buildType")
    val generic = localProps.getProperty("sandbox.base.url")
    return keyed ?: generic ?: default
}

val sandboxBaseUrlDebug: String = resolveSandboxUrl("debug", "http://10.0.2.2:8080")
val sandboxBaseUrlRelease: String = resolveSandboxUrl("release", "https://api.landolisp.dev")

android {
    namespace = "com.landolisp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.landolisp"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "SANDBOX_BASE_URL", "\"$sandboxBaseUrlDebug\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Production placeholder. Override via local.properties or CI to point at the
            // real sandbox backend once it's deployed.
            buildConfigField("String", "SANDBOX_BASE_URL", "\"$sandboxBaseUrlRelease\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Sync curriculum JSON from /curriculum into assets before each build.
//
// Prefers `scripts/sync-curriculum.sh` (owned by Agent B3) when present so that
// validation / index generation stays in one place. Falls back to a plain copy
// so a fresh checkout can still `assembleDebug` before the helper script lands.
val curriculumSourceDir = rootProject.projectDir.parentFile.resolve("curriculum")
val curriculumTargetDir = layout.projectDirectory.dir("src/main/assets/curriculum").asFile
val syncCurriculumScript = rootProject.projectDir.parentFile.resolve("scripts/sync-curriculum.sh")

val syncCurriculum = tasks.register("syncCurriculum") {
    group = "build"
    description = "Copies curriculum JSON files into the app's assets/curriculum directory."
    doLast {
        when {
            syncCurriculumScript.exists() -> exec {
                workingDir = rootProject.projectDir.parentFile
                commandLine("bash", syncCurriculumScript.absolutePath)
            }
            curriculumSourceDir.exists() -> {
                curriculumTargetDir.mkdirs()
                curriculumSourceDir.listFiles { f ->
                    f.isFile && f.name.endsWith(".json") && f.name != "schema.json"
                }?.forEach { src -> src.copyTo(curriculumTargetDir.resolve(src.name), overwrite = true) }
            }
            else -> logger.info("syncCurriculum: no curriculum source dir at $curriculumSourceDir; skipping")
        }
    }
}

tasks.named("preBuild") {
    dependsOn(syncCurriculum)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
