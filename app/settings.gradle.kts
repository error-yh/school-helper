pluginManagement {
    repositories {
        google()
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
            url = uri("https://jitpack.io")
            content { includeModule("cz.adaptech.tesseract4android", "tesseract4android") }
        }
    }
}

rootProject.name = "IdCodeAgg"

include(":app")
include(":feature:timetable")
include(":core:model")
include(":core:network")
include(":core:storage")
include(":core:webview")
include(":platform:api")
include(":platform:pdd")
include(":platform:taobao")
include(":platform:jd")
include(":platform:sf")
