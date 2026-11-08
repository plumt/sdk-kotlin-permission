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
        // Robolectric 은 API 레벨마다 android-all 을 올린다 — 여러 API 를 도는 테스트가 기본 힙(512m)을 넘는다
        unitTests.all { it.maxHeapSize = "1536m" }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        // 공개 인터페이스의 기본 구현을 Java 기본 메서드로만 낸다(DefaultImpls 없음) — 인터페이스에 함수를 더해도 구현체가 깨지지 않는다
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
    // 가시성을 적지 않은 선언은 컴파일 오류 — 공개 API 는 의도한 것만 연다
    explicitApi()
}

dependencies {
    // ActivityResultCaller·ActivityResultRegistry 가 공개 API 에 드러나므로 api 로 내보낸다
    api(libs.androidx.activity)
    // SavedStateRegistry 가 공개 API(createRequester)에 드러난다. androidx.activity 가 끌어오는 판과 같다
    api(libs.androidx.savedstate)
    implementation(libs.androidx.core)
    // suspend request 의 메인 디스패처·취소. androidx.activity 가 이미 끌어오는 판과 같아 런타임 의존이 늘지 않는다
    implementation(libs.kotlinx.coroutines.android)

    // 앱 lint 에 매니페스트 선언 검사를 더한다. aar 안의 lint.jar 로 나가 따로 받을 아티팩트가 없다
    lintPublish(project(":lint"))

    // 일치 테스트가 lint 선언표(SpecTable)를 읽는다 — lint.jar 와 같은 클래스다
    testImplementation(project(":lint"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    coordinates(artifactId = "permission", version = property("VERSION_NAME").toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("permission")
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
