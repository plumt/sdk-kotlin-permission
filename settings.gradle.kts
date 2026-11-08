pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "sdk-kotlin-permission"

// 발행 모듈 — 디렉터리 이름이 artifactId 와 같다
include(":permission")
include(":permission-compose")
// 발행하지 않는다 — permission 이 lintPublish 로 aar 안에 lint.jar 로 넣는다
include(":lint")
// 발행하지 않는다 — SDK 를 의존한 앱의 병합 매니페스트와 lint 검사를 빌드마다 확인한다
include(":sample")
