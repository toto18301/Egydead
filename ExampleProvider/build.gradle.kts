// bump this so CloudStream sees an update
version = 2

cloudstream {
    name = "EgyDead"                         // ← this is what the app shows
    description = "EgyDead (tv2.egydead.live) source"
    authors = listOf("Toufik Too")
    status = 1                               // 0:Down, 1:Ok, 2:Slow, 3:Beta
    tvTypes = listOf("Movie", "TvSeries")
    language = "ar"                          // fixes “Language: No Data”
    // Optional:
    // iconUrl = "https://raw.githubusercontent.com/<user>/<repo>/builds/icon.png"
}

android {
    namespace = "com.example"
    compileSdk = 35
    defaultConfig { minSdk = 21; targetSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}
