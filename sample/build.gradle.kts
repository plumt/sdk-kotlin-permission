import com.android.build.api.artifact.SingleArtifact
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 발행하지 않는 확인용 앱. SDK 를 의존한 앱의 병합 매니페스트가 앱이 선언한 것만 담는지, core 의 lint 검사가 실제 앱 lint 에 붙는지 본다
android {
    namespace = "net.plumt.permission.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.plumt.permission.sample"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        // core 의 lint.jar 검사가 오류를 내면 빌드가 멈춰야 한다
        abortOnError = true
        checkDependencies = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":compose"))
}

/**
 * 병합 매니페스트 확인. 앱이 선언한 권한 밖에 늘어난 권한, SDK 패키지의 컴포넌트, `<queries>` 가 있으면 실패한다.
 * androidx 가 넣는 항목(동적 리시버 보호 권한·초기화 제공자·프로파일 설치 리시버)은 이 SDK 와 무관하게 모든 androidx 앱에 들어가 허용 목록에 둔다.
 */
abstract class VerifyMergedManifest : DefaultTask() {
    @get:InputFile
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val expectedPermissions: SetProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(manifest.get().asFile)
        val android = "http://schemas.android.com/apk/res/android"
        fun names(tag: String): List<String> {
            val nodes = document.getElementsByTagName(tag)
            return (0 until nodes.length).map { (nodes.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") }
        }
        val permissions = (names("uses-permission") + names("uses-permission-sdk-23")).toSet()
        val components = listOf("activity", "service", "receiver", "provider").flatMap { tag -> names(tag).map { "$tag $it" } }
        val problems = ArrayList<String>()
        val extra = permissions - expectedPermissions.get()
        val missing = expectedPermissions.get() - permissions
        if (extra.isNotEmpty()) problems += "앱이 선언하지 않은 권한이 병합됐다: $extra"
        if (missing.isNotEmpty()) problems += "앱이 선언한 권한이 빠졌다: $missing"
        if (document.getElementsByTagName("queries").length > 0) problems += "<queries> 가 병합됐다"
        val sdkComponents = components.filter { it.contains("net.plumt.permission.") && !it.contains("net.plumt.permission.sample.") }
        if (sdkComponents.isNotEmpty()) problems += "SDK 컴포넌트가 병합됐다: $sdkComponents"
        report.get().asFile.writeText(
            "permissions=" + permissions.sorted() + "\ncomponents=" + components.sorted() + "\nproblems=" + problems + "\n",
        )
        check(problems.isEmpty()) { problems.joinToString("\n") }
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register<VerifyMergedManifest>("verify${name}MergedManifest") {
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            expectedPermissions.set(
                setOf(
                    "android.permission.CAMERA",
                    "android.permission.BLUETOOTH_CONNECT",
                    "android.permission.BLUETOOTH",
                    "android.permission.RUN_USER_INITIATED_JOBS",
                    "android.permission.ACCESS_NETWORK_STATE",
                    "android.permission.USE_BIOMETRIC",
                    // androidx.core 가 모든 앱에 넣는 서명 보호 권한 — 이 SDK 와 무관하다
                    "${variant.applicationId.get()}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                ),
            )
            report.set(layout.buildDirectory.file("reports/merged-manifest/${variant.name}.txt"))
        }
        tasks.named("check") { dependsOn(verify) }
    }
}
