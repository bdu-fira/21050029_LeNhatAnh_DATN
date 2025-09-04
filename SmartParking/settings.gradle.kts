// settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots/") }

        // Add any private repositories here
        // maven {
        //     url = uri("https://your-private-repo.com/maven/")
        //     credentials {
        //         username = "your_username"
        //         password = "your_password"
        //     }
        // }
    }
}

// Project configuration
rootProject.name = "Smart Parking Mobile App"

// Include app module
include(":app")

// Include additional modules (if any)
// include(":shared")
// include(":core")
// include(":data")
// include(":domain")
// include(":feature-notifications")
// include(":feature-settings")

// Enable Gradle build cache
buildCache {
    local {
        directory = File(rootDir, "build-cache")
        removeUnusedEntriesAfterDays = 30
    }
}