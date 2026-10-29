import java.net.URI
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun apiUrl(value: String, requireHttps: Boolean): String {
    val uri = URI(value)
    require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) {
        "API URL must be an absolute HTTP(S) URL."
    }
    require(!requireHttps || uri.scheme == "https") { "Release API URL must use HTTPS." }
    require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "Do not put credentials, a query, or a fragment in the API URL."
    }
    return "\"${value.trimEnd('/')}\""
}

android {
    namespace = "com.moa.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.moa.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    val signingFile = providers.gradleProperty("moa.signingProperties").orNull?.let { file(it) }
    if (signingFile != null) {
        val signing = Properties().apply { signingFile.inputStream().use { load(it) } }
        signingConfigs.create("familyRelease") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "API_BASE_URL", apiUrl(
                providers.gradleProperty("moa.debugApiBaseUrl").getOrElse("http://10.0.2.2:8080"),
                requireHttps = false,
            ))
        }
        release {
            if (signingFile != null) signingConfig = signingConfigs.getByName("familyRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_BASE_URL", apiUrl(
                providers.gradleProperty("moa.releaseApiBaseUrl").getOrElse("https://api.moa.invalid"),
                requireHttps = true,
            ))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
}
