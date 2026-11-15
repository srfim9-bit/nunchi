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
        // Pleos Connect SDK (Vehicle, NaviHelper, TTS, STT ...)
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/")
    }
}

rootProject.name = "Nunchi"

// :core  — 케어 판단 엔진 (순수 Kotlin — 차 없이 자동 테스트로 검증)
// :app   — Pleos Connect 앱 (Vehicle SDK 어댑터 + 화면)
include(":core", ":app")
