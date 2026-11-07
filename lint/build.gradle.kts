plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

// lint 는 JDK 17 이상에서 돈다. lint API 는 실행하는 lint 가 주므로 compileOnly 다
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.lint.api)
    compileOnly(libs.lint.checks)

    testImplementation(libs.junit)
    testImplementation(libs.lint.api)
    testImplementation(libs.lint.checks)
    testImplementation(libs.lint.cli)
    testImplementation(libs.lint.tests)
}

tasks.jar {
    manifest {
        // 이 값이 있어야 lint 가 jar 를 검사 등록부로 읽는다(서비스 파일과 함께)
        attributes("Lint-Registry-v2" to "net.plumt.permission.lint.PermissionIssueRegistry")
    }
}
