plugins { id("com.android.application") }
android {
    namespace = "app.syntheticresin.audiocast"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.syntheticresin.audiocast"
        minSdk = 29
        targetSdk = 37
        versionCode = 36
        versionName = "0.36"
    }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.mediarouter:mediarouter:1.8.1")
    implementation("com.google.android.gms:play-services-cast-framework:22.3.1")
    testImplementation("junit:junit:4.13.2")
}
tasks.withType<Test>().configureEach {
    systemProperty("relay.fixture", providers.gradleProperty("relayFixture").getOrElse(""))
}

