import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kover)
}

android {
    namespace = "net.plumt.permission.compose"
    compileSdk = 36
    // 소비 앱의 리소스와 이름이 겹치지 않게 접두사를 강제한다
    resourcePrefix = "plumt_permission_"

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
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
    }
    explicitApi()
}

dependencies {
    api(project(":core"))
    api(platform(libs.compose.bom))
    api(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    coordinates(artifactId = "compose", version = property("VERSION_NAME").toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("permission-compose")
        description.set("permission-core 의 권한 흐름을 Jetpack Compose 상태와 교체 가능한 Material3 대화상자로 쓰게 하는 라이브러리")
        inceptionYear.set("2026")
    }
}

kover {
    reports {
        filters {
            excludes {
                // @Composable 함수 — 컴포지션·ActivityResultRegistryOwner·수명주기가 있어야 돌아 유닛 테스트로 실행할 수 없다.
                // 로직은 모두 PermissionController 에 있고 그쪽은 PermissionControllerTest 가 검증한다
                classes(
                    "net.plumt.permission.compose.PermissionComposablesKt*",
                    "net.plumt.permission.compose.PermissionDialogsKt*",
                    "net.plumt.permission.compose.ComposableSingletons*",
                )
            }
        }
        verify {
            rule {
                // 라인 커버리지 90퍼센트 미만이면 koverVerify 가 실패한다
                minBound(90)
            }
        }
    }
}

tasks.named("check") { dependsOn("koverVerify") }
