package net.plumt.permission.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles.gradle
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

class PermissionDeclarationDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = PermissionDeclarationDetector()

    override fun getIssues(): List<Issue> = PermissionIssueRegistry().issues

    // 플랫폼 클래스를 쓰지 않아 SDK 없이 돈다 — CI 의 ANDROID_HOME 에 기대지 않는다
    override fun lint(): com.android.tools.lint.checks.infrastructure.TestLintTask = super.lint().allowMissingSdk()

    /** core 의 공개 표면을 흉내 낸 스텁 — 이름과 매개변수 순서가 core 와 같다 */
    private val stubs: TestFile = kotlin(
        """
        package net.plumt.permission

        object PermissionSpecs {
            val camera: Any = Any()
            val microphone: Any = Any()
            val usageStats: Any = Any()
            val exactAlarm: Any = Any()
            fun location(precise: Boolean = true): Any = Any()
            fun media(images: Boolean = true, video: Boolean = false): Any = Any()
            fun bluetooth(scan: Boolean = false, connect: Boolean = true, advertise: Boolean = false): Any = Any()
            fun nearbyWifiDevices(legacyLocation: Boolean = true): Any = Any()
            fun sms(send: Boolean = true, receive: Boolean = false, read: Boolean = false, receiveMms: Boolean = false, receiveWapPush: Boolean = false): Any = Any()
            val autoRevokeExempt: Any = Any()
            val vpn: Any = Any()
            val pictureInPicture: Any = Any()
            val turnScreenOn: Any = Any()
            val userInitiatedJobs: Any = Any()
            val crossProfile: Any = Any()
            val localNetwork: Any = Any()
            fun deviceAdmin(admin: Any, explanation: String? = null): Any = Any()
            fun screenCapture(onConsent: (Int, Any) -> Unit): Any = Any()
            fun role(role: Any): Any = Any()
            fun healthConnect(read: Array<String> = emptyArray(), write: Array<String> = emptyArray()): Any = Any()
        }

        object SystemRequirements {
            val bluetoothEnabled: Any = Any()
            fun hardware(feature: String, minSdk: Int = 1): Any = Any()
            val airplaneModeOff: Any = Any()
            val internetAvailable: Any = Any()
            val dataSaverOff: Any = Any()
            val powerSaveOff: Any = Any()
            val backgroundUnrestricted: Any = Any()
            fun standbyBucket(worstAllowed: Int = 40): Any = Any()
            val doNotDisturbOff: Any = Any()
            val deviceSecure: Any = Any()
            fun biometricEnrolled(strong: Boolean = false): Any = Any()
            val gpsEnabled: Any = Any()
            fun autoTimeEnabled(includeTimeZone: Boolean = false): Any = Any()
            val companionDeviceAssociated: Any = Any()
        }
        """,
    ).indented()

    private fun manifest(minSdk: Int = 23, body: String = "", tools: Boolean = false, targetSdk: Int = 36): TestFile = manifest(
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"${if (tools) " xmlns:tools=\"http://schemas.android.com/tools\"" else ""}
            package="test.app">
            <uses-sdk android:minSdkVersion="$minSdk" android:targetSdkVersion="$targetSdk" />
        $body
            <application />
        </manifest>
        """,
    ).indented()

    private fun code(body: String): TestFile = kotlin(
        """
        package test.app

        import net.plumt.permission.PermissionSpecs
        import net.plumt.permission.SystemRequirements

        fun specs(flag: Boolean) = listOf(
            $body
        )
        """,
    ).indented()

    fun `test 선언하지 않은 권한은 오류이고 고치기가 줄을 넣는다`() {
        lint().files(manifest(), stubs, code("PermissionSpecs.camera,"))
            .run()
            .expectErrorCount(1)
            .expectContains("PermissionSpecs.camera 이 요청하는 권한이 병합된 앱 매니페스트에 없습니다: android.permission.CAMERA")
            .expectFixDiffs(
                """
                Fix for src/test/app/test.kt line 7: 앱 매니페스트에 선언 추가:
                AndroidManifest.xml:
                @@ -2,0 +3 @@
                +    <uses-permission android:name="android.permission.CAMERA" />
                """,
            )
    }

    fun `test targetSdk 조건이 있는 선언은 그 targetSdk 부터 요구한다`() {
        // targetSdk 36 이하 앱은 로컬 네트워크 권한을 선언하지 않는다(INTERNET 으로 묵시 허용)
        lint().files(manifest(targetSdk = 36), stubs, code("PermissionSpecs.localNetwork,")).run().expectClean()
        lint().files(manifest(targetSdk = 37), stubs, code("PermissionSpecs.localNetwork,"))
            .run()
            .expectErrorCount(1)
            .expectContains("android.permission.ACCESS_LOCAL_NETWORK")
    }

    fun `test 선언돼 있으면 조용하다`() {
        lint().files(
            manifest(body = """    <uses-permission android:name="android.permission.CAMERA" />"""),
            stubs,
            code("PermissionSpecs.camera,"),
        ).run().expectClean()
    }

    fun `test 함수 인자를 읽어 필요한 권한만 요구한다`() {
        lint().files(
            manifest(body = """    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />"""),
            stubs,
            code("PermissionSpecs.location(precise = false),"),
        ).run().expectClean()

        lint().files(
            manifest(body = """    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />"""),
            stubs,
            code("PermissionSpecs.location(),"),
        ).run()
            .expectErrorCount(1)
            .expectContains("android.permission.ACCESS_FINE_LOCATION")
    }

    fun `test 상수가 아닌 인자는 오류로 삼지 않고 쓰는 선언으로만 센다`() {
        lint().files(
            manifest(
                body = """
                |    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
                |    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
                """.trimMargin(),
            ),
            stubs,
            code("PermissionSpecs.location(flag),"),
        ).run().expectClean()
    }

    fun `test maxSdkVersion 을 넣고 앱 minSdk 보다 낮은 상한의 선언은 요구하지 않는다`() {
        lint().files(manifest(minSdk = 23), stubs, code("PermissionSpecs.media(),"))
            .run()
            .expectContains("android.permission.READ_MEDIA_IMAGES, android.permission.READ_EXTERNAL_STORAGE")
            .expectFixDiffs(
                """
                Fix for src/test/app/test.kt line 7: 앱 매니페스트에 선언 추가:
                AndroidManifest.xml:
                @@ -2,0 +3,2 @@
                +    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
                +    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
                """,
            )

        lint().files(
            manifest(minSdk = 33, body = """    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />"""),
            stubs,
            code("PermissionSpecs.media(),"),
        ).run().expectClean()
    }

    fun `test 상한 없는 선언은 상한 있는 요구를 덮는다`() {
        lint().files(
            manifest(
                body = """
                |    <uses-permission android:name="android.permission.BLUETOOTH" />
                |    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
                |    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
                |    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
                |    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
                |    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" android:maxSdkVersion="30" />
                """.trimMargin(),
            ),
            stubs,
            code("PermissionSpecs.bluetooth(scan = true),"),
        ).run().expectClean()
    }

    fun `test 상한이 짧은 선언은 요구를 덮지 못한다`() {
        lint().files(
            manifest(body = """    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" android:maxSdkVersion="30" />"""),
            stubs,
            code("PermissionSpecs.location(precise = false),"),
        ).run()
            .expectErrorCount(1)
            .expectContains("android.permission.ACCESS_COARSE_LOCATION")
    }

    fun `test usesPermissionFlags 를 넣는다`() {
        lint().files(manifest(minSdk = 33), stubs, code("PermissionSpecs.nearbyWifiDevices(legacyLocation = false),"))
            .run()
            .expectFixDiffs(
                """
                Fix for src/test/app/test.kt line 7: 앱 매니페스트에 선언 추가:
                AndroidManifest.xml:
                @@ -2,0 +3 @@
                +    <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation" />
                """,
            )
    }

    fun `test 보호 권한은 tools_ignore 와 도구 네임스페이스를 함께 넣는다`() {
        lint().files(manifest(), stubs, code("PermissionSpecs.usageStats,"))
            .run()
            .expectFixDiffs(
                """
                Fix for src/test/app/test.kt line 7: 앱 매니페스트에 선언 추가:
                AndroidManifest.xml:
                @@ -1 +1 @@
                -<manifest xmlns:android="http://schemas.android.com/apk/res/android"
                +<manifest xmlns:tools="http://schemas.android.com/tools" xmlns:android="http://schemas.android.com/apk/res/android"
                @@ -2,0 +3 @@
                +    <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions" />
                """,
            )
    }

    fun `test 시스템 기능 요구 조건도 선언을 본다`() {
        lint().files(manifest(), stubs, code("SystemRequirements.bluetoothEnabled, SystemRequirements.hardware(\"x\"),"))
            .run()
            .expectErrorCount(1)
            .expectContains("SystemRequirements.bluetoothEnabled")
            .expectContains("android.permission.BLUETOOTH_CONNECT, android.permission.BLUETOOTH")
    }

    fun `test 쓰지 않는 선언은 정보로 알린다`() {
        lint().files(
            manifest(
                body = """
                |    <uses-permission android:name="android.permission.CAMERA" />
                |    <uses-permission android:name="android.permission.RECORD_AUDIO" />
                |    <uses-permission android:name="android.permission.INTERNET" />
                |    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
                |    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
                """.trimMargin(),
            ),
            stubs,
            code("PermissionSpecs.microphone, PermissionSpecs.exactAlarm,"),
        ).run()
            .expect(
                """
                AndroidManifest.xml:4: Hint: android.permission.CAMERA 을 선언했지만 코드의 어느 권한 명세도 이 권한을 쓰지 않습니다. 다른 라이브러리나 직접 요청에 쓰지 않는다면 지워 앱이 요청하는 권한을 줄이세요. [PlumtPermissionDeclaredButUnused]
                        <uses-permission android:name="android.permission.CAMERA" />
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                0 errors, 0 warnings, 1 hint
                """,
            )
    }

    fun `test 명세를 하나도 쓰지 않으면 쓰지 않는 선언도 알리지 않는다`() {
        lint().files(
            manifest(body = """    <uses-permission android:name="android.permission.CAMERA" />"""),
            stubs,
            kotlin("package test.app\nfun nothing() = Unit").indented(),
        ).run().expectClean()
    }

    fun `test Java 호출도 본다`() {
        lint().files(
            manifest(),
            stubs,
            java(
                """
                package test.app;

                import net.plumt.permission.PermissionSpecs;

                class Screen {
                    Object spec() { return PermissionSpecs.INSTANCE.getMicrophone(); }
                }
                """,
            ).indented(),
        ).run()
            .expectErrorCount(1)
            .expectContains("android.permission.RECORD_AUDIO")
    }

    fun `test 라이브러리 모듈만 검사하면 판정하지 않는다`() {
        lint().files(
            manifest(),
            stubs,
            code("PermissionSpecs.camera,"),
            gradle("apply plugin: 'com.android.library'"),
        ).run().expectClean()
    }

    fun `test 기능 모듈이 쓰고 앱 모듈이 선언하지 않으면 앱 판정에서 오류`() {
        val feature = project(stubs, code("PermissionSpecs.camera,"), manifest(), gradle("apply plugin: 'com.android.library'"))
            .name("feature")
        val app = project(manifest(body = """    <uses-permission android:name="android.permission.RECORD_AUDIO" />"""))
            .name("app")
            .dependsOn(feature)
        lint().projects(feature, app)
            // 억제 주석 삽입 모드는 여러 모듈의 의존 모듈 파일까지 다시 쓰지 못한다 — 억제는 단일 모듈 시험들이 확인한다
            .skipTestModes(com.android.tools.lint.checks.infrastructure.TestMode.SUPPRESSIBLE)
            .run()
            .expectErrorCount(1)
            .expectContains("PermissionSpecs.camera")
            .expectContains("PlumtPermissionDeclaredButUnused")
    }

    fun `test 앱이 Suppress 로 끄면 오류를 내지 않는다`() {
        lint().files(
            manifest(),
            stubs,
            kotlin(
                """
                package test.app

                import net.plumt.permission.PermissionSpecs

                @Suppress("PlumtPermissionNotDeclared")
                fun spec() = PermissionSpecs.camera
                """,
            ).indented(),
        ).run().expectClean()
    }

    fun `test 새 특수 접근은 필요한 선언만 요구한다`() {
        lint().files(
            manifest(),
            stubs,
            code(
                "PermissionSpecs.autoRevokeExempt, PermissionSpecs.vpn, PermissionSpecs.pictureInPicture, " +
                    "PermissionSpecs.deviceAdmin(Any()), PermissionSpecs.screenCapture { _, _ -> }, PermissionSpecs.role(Any()),",
            ),
        ).run().expectClean()

        lint().files(
            manifest(),
            stubs,
            code("PermissionSpecs.turnScreenOn, PermissionSpecs.userInitiatedJobs, PermissionSpecs.crossProfile,"),
        ).run()
            .expectErrorCount(3)
            .expectContains("android.permission.TURN_SCREEN_ON")
            .expectContains("android.permission.RUN_USER_INITIATED_JOBS")
            .expectContains("android.permission.INTERACT_ACROSS_PROFILES")
    }

    fun `test Health Connect 는 배열 인자에서 권한 이름을 만든다`() {
        lint().files(manifest(), stubs, code("PermissionSpecs.healthConnect(read = arrayOf(\"STEPS\"), write = arrayOf(\"HEART_RATE\")),"))
            .run()
            .expectErrorCount(1)
            .expectContains("android.permission.health.READ_STEPS, android.permission.health.WRITE_HEART_RATE")

        lint().files(
            manifest(
                body = """
                |    <uses-permission android:name="android.permission.health.READ_STEPS" />
                |    <uses-permission android:name="android.permission.health.READ_SLEEP" />
                """.trimMargin(),
            ),
            stubs,
            code("PermissionSpecs.healthConnect(arrayOf(\"STEPS\")),"),
        ).run()
            .expectErrorCount(0)
            .expectContains("android.permission.health.READ_SLEEP 을 선언했지만")
    }

    fun `test Health Connect 인자를 읽지 못하면 오류 없이 모든 건강 선언을 쓰는 것으로 센다`() {
        lint().files(
            manifest(body = """    <uses-permission android:name="android.permission.health.READ_SLEEP" />"""),
            stubs,
            kotlin(
                """
                package test.app

                import net.plumt.permission.PermissionSpecs

                fun spec(types: Array<String>) = PermissionSpecs.healthConnect(read = types)
                """,
            ).indented(),
        ).run().expectClean()
    }

    fun `test 새 시스템 상태는 상태 확인에 필요한 선언을 본다`() {
        lint().files(
            manifest(),
            stubs,
            code(
                "SystemRequirements.airplaneModeOff, SystemRequirements.dataSaverOff, SystemRequirements.powerSaveOff, " +
                    "SystemRequirements.backgroundUnrestricted, SystemRequirements.standbyBucket(), SystemRequirements.doNotDisturbOff, " +
                    "SystemRequirements.deviceSecure, SystemRequirements.gpsEnabled, SystemRequirements.autoTimeEnabled(true), " +
                    "SystemRequirements.companionDeviceAssociated,",
            ),
        ).run().expectClean()

        lint().files(manifest(), stubs, code("SystemRequirements.internetAvailable, SystemRequirements.biometricEnrolled(),"))
            .run()
            .expectErrorCount(2)
            .expectContains("android.permission.ACCESS_NETWORK_STATE")
            .expectContains("android.permission.USE_BIOMETRIC")
    }

    fun `test 선언표 단위 동작`() {
        assertEquals("camera", SpecTable.memberName("getCamera", 0))
        assertEquals("get", SpecTable.memberName("get", 0))
        assertEquals("getaway", SpecTable.memberName("getaway", 0))
        assertEquals("location", SpecTable.memberName("location", 1))
        val decl = Decl("a.B", 30, "neverForLocation", true)
        assertEquals(decl, Decl.decode(decl.encode()))
        assertEquals(Decl("a.B"), Decl.decode(Decl("a.B").encode()))
        assertEquals(null, Decl.decode("broken"))
        assertEquals(null, Decl.decode("|||"))
        assertTrue("android.permission.BODY_SENSORS_BACKGROUND" in SpecTable.knownPermissions)
        assertTrue("android.permission.USE_EXACT_ALARM" !in SpecTable.knownPermissions)
        assertEquals(null, SpecTable.lookup("x.Other", "camera", Args { _, _ -> true }))
        assertEquals(null, SpecTable.lookup(SpecTable.SPECS, "unknown", Args { _, _ -> true }))
        assertEquals(null, SpecTable.lookup(SpecTable.REQUIREMENTS, "unknown", Args { _, _ -> true }))
        val unknown = SpecTable.lookup(SpecTable.SPECS, "nearbyWifiDevices", Args { _, _ -> null })!!
        assertEquals(listOf("android.permission.NEARBY_WIFI_DEVICES"), unknown.required.map { it.name })
        assertEquals(2, unknown.possible.size)
        assertTrue(SpecTable.isKnown("android.permission.health.READ_STEPS"))
        assertTrue(!SpecTable.isKnown("android.permission.INTERNET"))
        assertTrue(SpecTable.isKnown("android.permission.TURN_SCREEN_ON"))
        val health = SpecTable.lookup(SpecTable.SPECS, "healthConnect", Args { _, _ -> null })!!
        assertEquals(emptyList<Decl>(), health.required)
        assertEquals(listOf(SpecTable.HEALTH_ANY), health.possible.map { it.name })
        assertEquals(emptyList<Decl>(), SpecTable.lookup(SpecTable.SPECS, "role", Args { _, _ -> null })!!.required)
    }
}
