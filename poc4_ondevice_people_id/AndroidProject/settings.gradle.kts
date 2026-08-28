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
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = "" // not needed
                password = System.getenv("GITHUB_TOKEN") ?: java.util.Properties().apply {
                    val localPropsFile = file("local.properties")
                    if (localPropsFile.exists()) {
                        load(localPropsFile.inputStream())
                    }
                }.getProperty("github_token") ?: ""
            }
        }
    }
}

rootProject.name = "POC4OnDevicePeopleID"
include(":app")
