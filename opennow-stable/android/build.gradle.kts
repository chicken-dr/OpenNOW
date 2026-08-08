plugins {
    id("com.android.application") version "8.4.0"
    id("org.jetbrains.kotlin.android") version "1.9.22"
    // Hilt temporarily disabled for Phase 1 build verification
    // id("com.google.dagger.hilt.android") version "2.48"
    // id("org.jetbrains.kotlin.kapt") version "1.9.22"
}

repositories {
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

android {
    namespace = "com.opennow"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.opennow"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs = listOf("-Xopt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        viewBinding = true
    }

    packagingOptions {
        resources {
            excludes += listOf("META-INF/*.kotlin_module")
        }
    }

    // Perfetto config for profiling
    androidResources {
        noCompress("pbtx")
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    
    // ConstraintLayout for UI
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // Material Components
    implementation("com.google.android.material:material:1.11.0")
    
    // Hilt DI - temporarily disabled for Phase 1 build verification
    // implementation("com.google.dagger:hilt-android:2.48")
    // kapt("com.google.dagger:hilt-compiler:2.48")
    // implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    
    // Serialization for shared types
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    
    // WebRTC Android SDK - temporarily commented out for Phase 1 build verification
    // implementation("org.webrtc:google-webrtc:1.0.32006")
    
    // Alternative WebRTC from jitpack if needed
    // implementation("com.github.webrtc:webrtc:1.0.32006")
    
    // For Phase 1, use local stub implementations
    // WebRTC classes will be implemented as stubs
    
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    
    // Hilt testing - temporarily disabled for Phase 1
    // androidTestImplementation("com.google.dagger:hilt-android-testing:2.48")
    // kaptAndroidTest("com.google.dagger:hilt-compiler:2.48")
}

kotlin {
    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
            }
        }
    }
}

// Hilt plugin setup - temporarily disabled for Phase 1
// kapt {
//     correctErrorTypes = true
// }