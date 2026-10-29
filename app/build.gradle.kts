import java.util.Properties

plugins { id("com.android.application") }
val releaseSigningFile = rootProject.file("signing.properties")
val releaseSigning = Properties().apply {
    if (releaseSigningFile.exists()) releaseSigningFile.reader(Charsets.UTF_8).use { load(it) }
}
android {
    buildFeatures { resValues = true }
    sourceSets.getByName("main").assets.srcDir("../receiver/web")
    namespace = "app.syntheticresin.audiocast"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.syntheticresin.audiocast"
        resValue("string", "cast_receiver_app_id", providers.gradleProperty("castReceiverAppId").getOrElse("E1D7D101"))
        minSdk = 29
        targetSdk = 37
        versionCode = 53
        versionName = "44.0.9"
    }
    if (releaseSigningFile.exists()) {
        signingConfigs {
            create("upload") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("upload")
    }
}
dependencies {
    // Restore Billing 9.1.0 only when optional-features/billing is enabled again.
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.mediarouter:mediarouter:1.8.1")
    implementation("com.google.android.gms:play-services-cast-framework:22.3.1")
    testImplementation("junit:junit:4.13.2")
}
tasks.withType<Test>().configureEach {
    systemProperty("relay.fixture", providers.gradleProperty("relayFixture").getOrElse(""))
}

