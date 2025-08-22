// use an integer for version numbers
version = 1

// CloudStream plugin metadata
version = 2

cloudstream {
    description = "EgyDead (tv2.egydead.live) source"
    authors = listOf("Toufik Too")
    status = 1                       // 0:Down, 1:Ok, 2:Slow, 3:Beta
    tvTypes = listOf("Movie", "TvSeries")
    language = "ar"
}

// Android Gradle Plugin 8.x requires a namespace per module.
// This MUST match the package at the top of your ExampleProvider.kt file.
// If your Kotlin file starts with `package com.example`, leave this as is.
android {
    namespace = "com.example"

    // These can mirror your root settings. It's fine to repeat here.
    compileSdk = 35
    defaultConfig {
        minSdk = 21
        targetSdk = 35
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

