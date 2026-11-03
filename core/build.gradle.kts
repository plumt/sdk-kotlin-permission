import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kover)
}

android {
    namespace = "net.plumt.permission"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    // D8 가 읽는 바이트코드는 Java 11 로 낸다
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
    // 가시성을 적지 않은 선언은 컴파일 오류 — 공개 API 는 의도한 것만 연다
    explicitApi()
}

dependencies {
    // ActivityResultCaller·ActivityResultRegistry 가 공개 API 에 드러나므로 api 로 내보낸다
    api(libs.androidx.activity)
    implementation(libs.androidx.core)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    coordinates(artifactId = "core", version = property("VERSION_NAME").toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("permission-core")
        description.set("Android 런타임 권한의 확인·근거 안내·요청·영구 거부 판정·설정 이동을 한 흐름으로 다루는 라이브러리")
        inceptionYear.set("2026")
    }
}

kover {
    reports {
        verify {
            rule {
                // 라인 커버리지 90퍼센트 미만이면 koverVerify 가 실패한다
                minBound(90)
            }
        }
    }
}

tasks.named("check") { dependsOn("koverVerify") }
