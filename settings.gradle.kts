pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Offline workstation shim: compose-group-mapping's JAR is in the
        // Gradle cache but its module descriptor is not, so --offline
        // resolution of the R8 compose-mapping classpath fails. This local
        // m2 dir carries the cached jar + a minimal pom. Harmless when
        // building online.
        maven { url = uri(file("${System.getProperty("user.home")}/.zcode/local-m2")) }
        google()
        mavenCentral()
    }
}

rootProject.name = "Speechnotes"
include(":app")
