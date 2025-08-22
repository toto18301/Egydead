import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import com.android.build.gradle.BaseExtension
import org.gradle.api.Project

buildscript {
    repositories {
        google()
        mavenCentral()
        // JitPack repo which contains CloudStream tools and dependencies
        maven("https://jitpack.io")
    }
    dependencies {
        // ⬇️ Updated toolchain versions
        classpath("com.android.tools.build:gradle:8.4.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")

        // CloudStream gradle plugin which wires up tasks for building plugins
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// Convenience accessors for CloudStream + Android blocks used below
fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) =
    extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    // Apply core plugins to each plugin module (e.g., ExampleProvider)
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // When running through GitHub workflow, GITHUB_REPOSITORY should contain current repository name.
        // You can modify this to use other git hosting services, like GitLab.
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/user/repo")
    }

    android {
        // Updated SDK levels for AGP 8.x
        compileSdkVersion(35)

        defaultConfig {
            minSdk = 21
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        // Kotlin compiler options
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
            kotlinOptions {
                jvmTarget = "1.8" // Required
                // Disables some unnecessary checks
                freeCompilerArgs = freeCompilerArgs +
                    "-Xno-call-assertions" +
                    "-Xno-param-assertions" +
                    "-Xno-receiver-assertions"
            }
        }
    }

    dependencies {
        val apk by configurations
        val implementation by configurations

        // Stubs for all CloudStream classes (SDK)
        apk("com.lagradost:cloudstream3:pre-release")

        // Typical helper libs (same as before — adjust if you need newer versions)
        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.3.2")
        implementation("org.jsoup:jsoup:1.13.1")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}
