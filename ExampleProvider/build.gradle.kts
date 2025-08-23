// bump this so CloudStream sees an update
version = 3

cloudstream {
  description = "EgyDead (tv2.egydead.live) source"
  authors = listOf("Toufik Too")
  language = "ar"
  status = 1
  tvTypes = listOf("Movie", "TvSeries")
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
