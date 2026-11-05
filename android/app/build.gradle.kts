plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}
android {
    namespace = "app.carsync.music"
    compileSdk = 36
    defaultConfig { applicationId = "app.carsync.music"; minSdk = 23; targetSdk = 36; versionCode = 27; versionName = "1.9.3"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/licenseAssets"))
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs.getByName("debug") {
        enableV1Signing = true; enableV2Signing = true
        val location = System.getenv("CARSYNC_KEYSTORE")
        if (location != null && file(location).exists()) { storeFile = file(location); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" }
    }
    signingConfigs.create("playUpload") {
        System.getenv("CARSYNC_UPLOAD_KEYSTORE")?.let { storeFile = file(it) }
        storePassword = System.getenv("CARSYNC_UPLOAD_STORE_PASSWORD")
        keyAlias = System.getenv("CARSYNC_UPLOAD_KEY_ALIAS") ?: "carsync-upload"
        keyPassword = System.getenv("CARSYNC_UPLOAD_KEY_PASSWORD") ?: System.getenv("CARSYNC_UPLOAD_STORE_PASSWORD")
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("internal") { dimension = "distribution"; signingConfig = signingConfigs.getByName("debug") }
        create("play") { dimension = "distribution"; signingConfig = signingConfigs.getByName("playUpload") }
    }
    buildTypes { release {
        isMinifyEnabled = true; isShrinkResources = true; isDebuggable = false
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    } }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
val copyProjectLicense by tasks.registering(Copy::class) {
    from(rootProject.file("../LICENSE"))
    into(layout.buildDirectory.dir("generated/licenseAssets"))
    rename { "LICENSE.txt" }
}
tasks.named("preBuild") { dependsOn(copyProjectLicense) }
val copyPrivacyPolicy by tasks.registering(Copy::class) {
    from(rootProject.file("../backend/public/privacy-policy.txt"))
    into(layout.buildDirectory.dir("generated/licenseAssets"))
}
tasks.named("preBuild") { dependsOn(copyPrivacyPolicy) }
tasks.configureEach {
    if (name == "validateSigningPlayRelease" || name == "bundlePlayRelease") doFirst {
        check(!System.getenv("CARSYNC_UPLOAD_KEYSTORE").isNullOrBlank() && !System.getenv("CARSYNC_UPLOAD_STORE_PASSWORD").isNullOrBlank()) {
            "Play release requires a private upload keystore and password. Test signing is never used for Play."
        }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}





