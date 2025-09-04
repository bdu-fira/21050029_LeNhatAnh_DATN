// build.gradle.kts (Project level)
buildscript {
    val kotlin_version by extra("1.9.20")
    val gradle_version by extra("8.2.0")

    dependencies {
        classpath("com.android.tools.build:gradle:$gradle_version")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version")

        // Firebase (uncomment if using)
        // classpath("com.google.gms:google-services:4.4.0")
        // classpath("com.google.firebase:firebase-crashlytics-gradle:2.9.9")

        // Other useful plugins
        classpath("androidx.navigation:navigation-safe-args-gradle-plugin:2.7.6")
        classpath("org.jetbrains.kotlin:kotlin-serialization:$kotlin_version")

        // Detekt plugin
        classpath("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.23.4")

        // NOTE: Do not place your application dependencies here; they belong
        // in the individual module build.gradle.kts files
    }
}

plugins {
    // Apply plugins to all modules
    id("com.android.application") version "8.2.0" apply false
    id("com.android.library") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.20" apply false

    // Additional plugins
    id("androidx.navigation.safeargs.kotlin") version "2.7.6" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.20" apply false

    // Quality assurance plugins (apply false for root project)
    id("org.sonarqube") version "4.4.1.3373" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.4" apply false
}

// Subprojects configuration
subprojects {
    // Apply common plugins to all subprojects
    apply(plugin = "org.gradle.idea")

    // Configure dependencies for all modules
    configurations.all {
        resolutionStrategy {
            // Force specific versions to avoid conflicts
            force("org.jetbrains.kotlin:kotlin-stdlib:1.9.20")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.20")
            force("androidx.core:core-ktx:1.12.0")
            // REMOVE THE PROBLEMATIC EXCLUDE LINE
            // DO NOT exclude annotations - we need them!
        }
    }

    // Configure all projects - Kotlin compilation
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "1.8"

            // Enable experimental features globally
            freeCompilerArgs += listOf(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-opt-in=kotlin.time.ExperimentalTime"
            )
        }
    }
}

// Global task configuration
tasks.register("clean", Delete::class) {
    delete(layout.buildDirectory)
}

// Custom tasks for project management
tasks.register("printProjectInfo") {
    doLast {
        println("Project: ${rootProject.name}")
        println("Gradle Version: ${gradle.gradleVersion}")
        println("Android Gradle Plugin: 8.2.0")
        println("Kotlin Version: 1.9.20")

        subprojects.forEach { subproject ->
            println("Module: ${subproject.name}")
        }
    }
}

// Gradle configuration
gradle.projectsEvaluated {
    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:deprecation"))
    }
}

// Project properties
ext {
    set("compileSdkVersion", 34)
    set("minSdkVersion", 24)
    set("targetSdkVersion", 34)
    set("buildToolsVersion", "34.0.0")

    // Library versions
    set("lifecycleVersion", "2.7.0")
    set("roomVersion", "2.6.1")
    set("workVersion", "2.9.0")
    set("navigationVersion", "2.7.6")
    set("retrofitVersion", "2.9.0")
    set("okhttpVersion", "4.12.0")
    set("coroutinesVersion", "1.7.3")
    set("materialVersion", "1.11.0")

    // Testing versions
    set("junitVersion", "4.13.2")
    set("espressoVersion", "3.5.1")
    set("testExtJunitVersion", "1.1.5")
    set("mockitoVersion", "5.8.0")
}

// SonarQube configuration (if using)
/*
apply(plugin = "org.sonarqube")

sonarqube {
    properties {
        property("sonar.projectName", "Smart Parking Mobile App")
        property("sonar.projectKey", "smart-parking-mobile")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.language", "kotlin")
        property("sonar.sources", "src/main")
        property("sonar.tests", "src/test")
        property("sonar.sourceEncoding", "UTF-8")
    }
}
*/