package net.plumt.permission

import android.app.Activity
import android.app.AppOpsManager
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.app.job.JobScheduler
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.CrossProfileApps
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTelephony
import org.robolectric.shadows.ShadowVpnService

/** 앱 접근 특수 권한 — API 레벨별 적용 여부, 실제 판정, 해결 인텐트, 선언 확인, 결과 전달 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessSpecsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager: PermissionManager
        get() = PermissionManager.create(app, PermissionConfig(RequestHistoryStore.inMemory(), ManifestCheck.Off))

    private fun setOp(op: String, mode: Int) =
        shadowOf(app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager).setMode(op, Process.myUid(), app.packageName, mode)

    private fun applicable(spec: SpecialPermissionSpec, sdk: Int, targetSdk: Int = sdk) =
        spec.isApplicable(PermissionEnvironment.of(sdk, targetSdkInt = targetSdk))

    private fun applicableFrom(spec: SpecialPermissionSpec, min: Int) {
        for (sdk in 23..37) assertEquals("${spec.id}@$sdk", sdk >= min, applicable(spec, sdk))
    }

    private val admin = ComponentName("x.pkg", "x.pkg.Admin")

    @After
    fun tearDown() {
        ShadowVpnService.reset()
    }

    @Test
    fun `적용 API·선언 권한·id·문자열`() {
        applicableFrom(PermissionSpecs.autoRevokeExempt, 30)
        applicableFrom(PermissionSpecs.pictureInPicture, 26)
        applicableFrom(PermissionSpecs.turnScreenOn, 36)
        applicableFrom(PermissionSpecs.userInitiatedJobs, 34)
        applicableFrom(PermissionSpecs.crossProfile, 30)
        applicableFrom(PermissionSpecs.vpn, 1)
        applicableFrom(PermissionSpecs.screenCapture { _, _ -> }, 1)
        applicableFrom(PermissionSpecs.deviceAdmin(admin), 1)
        applicableFrom(PermissionSpecs.role(AppRole.SMS), 23)
        applicableFrom(PermissionSpecs.role(AppRole.DIALER), 23)
        for (role in AppRole.BUILT_IN - AppRole.SMS - AppRole.DIALER) applicableFrom(PermissionSpecs.role(role), role.minSdk)
        applicableFrom(PermissionSpecs.role(AppRole.NOTES), 34)
        applicableFrom(PermissionSpecs.role(AppRole.WALLET), 35)
        // 화면 켜기도 다른 특수 권한처럼 기기 API 만 본다 — 앱 targetSdk 와 관계없다
        assertTrue(applicable(PermissionSpecs.turnScreenOn, 36, targetSdk = 35))
        assertFalse(applicable(PermissionSpecs.turnScreenOn, 35, targetSdk = 36))

        assertEquals("android.permission.TURN_SCREEN_ON", PermissionSpecs.turnScreenOn.manifestPermission)
        assertEquals("android.permission.RUN_USER_INITIATED_JOBS", PermissionSpecs.userInitiatedJobs.manifestPermission)
        assertEquals("android.permission.INTERACT_ACROSS_PROFILES", PermissionSpecs.crossProfile.manifestPermission)
        val none = listOf(
            PermissionSpecs.autoRevokeExempt,
            PermissionSpecs.vpn,
            PermissionSpecs.pictureInPicture,
            PermissionSpecs.screenCapture { _, _ -> },
            PermissionSpecs.deviceAdmin(admin),
            PermissionSpecs.role(AppRole.HOME),
        )
        for (spec in none) assertNull(spec.id, spec.manifestPermission)

        val ids = listOf(
            PermissionSpecs.autoRevokeExempt, PermissionSpecs.vpn, PermissionSpecs.pictureInPicture, PermissionSpecs.turnScreenOn,
            PermissionSpecs.userInitiatedJobs, PermissionSpecs.crossProfile, PermissionSpecs.screenCapture { _, _ -> },
        ).map { it.id }
        assertEquals(
            listOf("auto_revoke_exempt", "vpn", "picture_in_picture", "turn_screen_on", "user_initiated_jobs", "cross_profile", "screen_capture"),
            ids,
        )
        for (spec in listOf(
            PermissionSpecs.autoRevokeExempt, PermissionSpecs.vpn, PermissionSpecs.pictureInPicture, PermissionSpecs.turnScreenOn,
            PermissionSpecs.userInitiatedJobs, PermissionSpecs.crossProfile, PermissionSpecs.screenCapture { _, _ -> },
        )) {
            assertTrue(spec.toString().endsWith("Spec"))
        }
        assertEquals("device_admin:x.pkg/x.pkg.Admin", PermissionSpecs.deviceAdmin(admin).id)
        assertEquals("role.call_screening", PermissionSpecs.role(AppRole.CALL_SCREENING).id)
        assertEquals(AppRole.BUILT_IN.size, AppRole.BUILT_IN.map { PermissionSpecs.role(it).id }.toSet().size)
        assertEquals("role.wallet", PermissionSpecs.role(AppRole.WALLET).id)
        // 같은 인자면 같은 명세
        assertEquals(PermissionSpecs.deviceAdmin(admin, "e"), PermissionSpecs.deviceAdmin(admin, "e"))
        assertNotEquals(PermissionSpecs.deviceAdmin(admin, "e"), PermissionSpecs.deviceAdmin(admin))
        assertEquals(PermissionSpecs.role(AppRole.SMS), PermissionSpecs.role(AppRole.SMS))
        assertEquals(PermissionSpecs.healthConnect(arrayOf("STEPS")), PermissionSpecs.healthConnect(arrayOf("STEPS", "STEPS")))
    }

    @Test
    fun `역할 상수는 플랫폼 값과 같다`() {
        assertEquals(RoleManager.ROLE_SMS, AppRole.SMS.roleName)
        assertEquals(RoleManager.ROLE_DIALER, AppRole.DIALER.roleName)
        assertEquals(RoleManager.ROLE_BROWSER, AppRole.BROWSER.roleName)
        assertEquals(RoleManager.ROLE_HOME, AppRole.HOME.roleName)
        assertEquals(RoleManager.ROLE_CALL_SCREENING, AppRole.CALL_SCREENING.roleName)
        assertEquals(RoleManager.ROLE_CALL_REDIRECTION, AppRole.CALL_REDIRECTION.roleName)
        assertEquals(RoleManager.ROLE_ASSISTANT, AppRole.ASSISTANT.roleName)
        assertEquals(RoleManager.ROLE_EMERGENCY, AppRole.EMERGENCY.roleName)
        assertEquals(RoleManager.ROLE_NOTES, AppRole.NOTES.roleName)
        assertEquals(RoleManager.ROLE_WALLET, AppRole.WALLET.roleName)
        assertEquals(
            listOf(AppRole.ASSISTANT, AppRole.EMERGENCY, AppRole.NOTES, AppRole.WALLET),
            AppRole.BUILT_IN.filterNot { it.requestable },
        )
        assertEquals(listOf(AppRole.SMS, AppRole.DIALER), AppRole.BUILT_IN.filter { it.hasLegacyApi })
        assertEquals(listOf(29, 29, 29, 29, 29, 29, 29, 29, 34, 35), AppRole.BUILT_IN.map { it.minSdk })
        // 새 플랫폼 역할은 앱이 직접 만든다 — 이름이 같으면 같은 역할
        assertEquals(AppRole.SMS, AppRole("android.app.role.SMS", requestable = true))
        assertFalse(AppRole("x.role", requestable = true).hasLegacyApi)
        assertThrows(IllegalArgumentException::class.java) { AppRole(" ", requestable = true) }
        assertThrows(IllegalArgumentException::class.java) { AppRole("x.role", requestable = true, minSdk = 28) }
        assertEquals("AppRole(android.app.role.SMS)", AppRole.SMS.toString())
    }

    // ---- 자동 회수 예외

    @Test
    @Config(sdk = [30, 31, 34, 35])
    fun `자동 회수 예외는 isAutoRevokeWhitelisted 를 따르고 이 앱 화면을 연다`() {
        val pm = shadowOf(app.packageManager)
        pm.setAutoRevokeWhitelisted(false)
        assertEquals(PermissionStatus.Denied(false), manager.check(PermissionSpecs.autoRevokeExempt))
        pm.setAutoRevokeWhitelisted(true)
        assertEquals(PermissionStatus.Granted, manager.check(PermissionSpecs.autoRevokeExempt))
        val intent = PermissionSpecs.autoRevokeExempt.settingsIntent(app)
        assertEquals(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
    }

    @Test
    @Config(sdk = [23, 29])
    fun `자동 회수 예외 API 30 미만은 NotApplicable 이고 직접 물으면 참`() {
        assertEquals(PermissionStatus.NotApplicable(Build.VERSION.SDK_INT), manager.check(PermissionSpecs.autoRevokeExempt))
        assertTrue(PermissionSpecs.autoRevokeExempt.isGranted(app))
    }

    // ---- 기기 관리자

    @Test
    fun `기기 관리자 인자 검사`() {
        val e = assertThrows(IllegalArgumentException::class.java) { PermissionSpecs.deviceAdmin(ComponentName("x.pkg", " ")) }
        assertTrue(e.message!!.contains("클래스 이름이 비었습니다"))
        val empty = assertThrows(IllegalArgumentException::class.java) { PermissionSpecs.deviceAdmin(admin, "") }
        assertTrue(empty.message!!.contains("explanation"))
        // 유니코드·긴 설명은 그대로 넘긴다
        val long = "설명 ✓ ".repeat(500)
        assertEquals(long, PermissionSpecs.deviceAdmin(admin, long).settingsIntent(app).getStringExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION))
    }

    @Test
    @Config(sdk = [23, 30, 35])
    fun `기기 관리자는 isAdminActive 를 따르고 활성화 화면에 컴포넌트와 설명을 넘긴다`() {
        val component = ComponentName(app.packageName, "x.Admin")
        val spec = PermissionSpecs.deviceAdmin(component, "설명")
        assertEquals(PermissionStatus.Denied(false), manager.check(spec))
        shadowOf(app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).setActiveAdmin(component)
        assertEquals(PermissionStatus.Granted, manager.check(spec))
        val intent = spec.settingsIntent(app)
        assertEquals(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN, intent.action)
        @Suppress("DEPRECATION")
        assertEquals(component, intent.getParcelableExtra<ComponentName>(DevicePolicyManager.EXTRA_DEVICE_ADMIN))
        assertEquals("설명", intent.getStringExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION))
        assertFalse(PermissionSpecs.deviceAdmin(component).settingsIntent(app).hasExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION))
    }

    @Test
    fun `기기 관리자 수신기가 없거나 보호 권한이 다르면 선언 누락을 알린다`() {
        val component = ComponentName(app.packageName, "x.Admin")
        val spec = PermissionSpecs.deviceAdmin(component)
        assertTrue(spec.missingDeclarations(app).single().contains("android.permission.BIND_DEVICE_ADMIN"))
        val pm = shadowOf(app.packageManager)
        pm.addOrUpdateReceiver(ActivityInfo().apply { packageName = app.packageName; name = "x.Admin"; permission = "x.OTHER" })
        assertEquals(1, spec.missingDeclarations(app).size)
        pm.addOrUpdateReceiver(
            ActivityInfo().apply { packageName = app.packageName; name = "x.Admin"; permission = "android.permission.BIND_DEVICE_ADMIN" },
        )
        assertEquals(emptyList<String>(), spec.missingDeclarations(app))
    }

    // ---- VPN

    private fun declareService(action: String, permission: String, name: String) {
        val component = ComponentName(app.packageName, name)
        val pm = shadowOf(app.packageManager)
        pm.addOrUpdateService(
            ServiceInfo().apply {
                packageName = app.packageName
                this.name = name
                this.permission = permission
                applicationInfo = app.applicationInfo
            },
        )
        pm.addIntentFilterForService(component, IntentFilter(action))
    }

    @Test
    @Config(sdk = [23, 24, 34])
    fun `VPN 은 prepare 결과를 따르고 동의 화면을 연다`() {
        val consent = Intent("x.VPN_CONSENT")
        ShadowVpnService.setPrepareResult(consent)
        assertEquals(PermissionStatus.Denied(false), manager.check(PermissionSpecs.vpn))
        assertSame(consent, PermissionSpecs.vpn.settingsIntent(app))
        ShadowVpnService.setPrepareResult(null)
        assertEquals(PermissionStatus.Granted, manager.check(PermissionSpecs.vpn))
        // 그사이 동의됐으면 VPN 설정 화면(API 24+)으로 간다
        val fallback = if (Build.VERSION.SDK_INT >= 24) Settings.ACTION_VPN_SETTINGS else Settings.ACTION_WIRELESS_SETTINGS
        assertEquals(fallback, PermissionSpecs.vpn.settingsIntent(app).action)
    }

    @Test
    fun `VPN 서비스가 없으면 선언 누락을 알린다`() {
        assertTrue(PermissionSpecs.vpn.missingDeclarations(app).single().contains("android.net.VpnService"))
        declareService("android.net.VpnService", "android.permission.BIND_VPN_SERVICE", "x.Vpn")
        assertEquals(emptyList<String>(), PermissionSpecs.vpn.missingDeclarations(app))
    }

    // ---- 화면 캡처

    @Test
    @Config(sdk = [23, 29, 34, 35])
    fun `화면 캡처는 늘 Denied 이고 동의 화면 인텐트를 만들며 허용 결과만 넘긴다`() {
        val got = ArrayList<Pair<Int, Intent>>()
        val spec = PermissionSpecs.screenCapture { code, data -> got += code to data }
        assertEquals(PermissionStatus.Denied(false), manager.check(spec))
        val intent = spec.settingsIntent(app)
        assertTrue(intent.component != null || intent.action != null)
        spec.onSettingsResult(Activity.RESULT_CANCELED, Intent())
        spec.onSettingsResult(Activity.RESULT_OK, null)
        assertTrue(got.isEmpty())
        val data = Intent("token")
        spec.onSettingsResult(Activity.RESULT_OK, data)
        assertEquals(listOf(Activity.RESULT_OK to data), got)
        // 다른 특수 권한의 결과 훅은 기본 무시다
        PermissionSpecs.vpn.onSettingsResult(Activity.RESULT_OK, data)
    }

    @Test
    fun `요청기는 연 화면의 결과를 그 특수 권한에 넘기고 대신 연 앱 설정 화면의 결과는 넘기지 않는다`() {
        val got = ArrayList<Intent>()
        val spec = PermissionSpecs.screenCapture { _, data -> got += data }
        val registry = FakeRegistry()
        var results = 0
        val r = fakeManager(app, FakeDevice()).createRequester(registry, "s", { null }, listOf(spec)) { results++ }
        assertTrue(r.launch())
        val data = Intent("token")
        registry.respondSettings(Activity.RESULT_OK, data)
        assertEquals(listOf(data), got)
        assertEquals(1, results)

        // 화면을 열 수 없어 앱 상세 설정을 대신 열면 결과는 명세의 것이 아니다
        registry.failIntentLaunches = 1
        assertTrue(r.launch())
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, registry.lastIntent.action)
        registry.respondSettings(Activity.RESULT_OK, Intent("other"))
        assertEquals(listOf(data), got)
        assertEquals(2, results)
    }

    @Test
    fun `결과 훅이 던지면 PermissionException 이고 흐름은 끝난다`() {
        val spec = object : SpecialPermissionSpec {
            override val id = "hook.failing"
            override val manifestPermission: String? = null
            override fun isApplicable(environment: PermissionEnvironment) = true
            override fun isGranted(context: Context) = false
            override fun settingsIntent(context: Context) = Intent("x")
            override fun onSettingsResult(resultCode: Int, data: Intent?) = throw IllegalStateException("hook boom")
        }
        val registry = FakeRegistry()
        val r = fakeManager(app, FakeDevice()).createRequester(registry, "h", { null }, listOf(spec)) {}
        r.launch()
        val e = assertThrows(PermissionException::class.java) { registry.respondSettings(Activity.RESULT_OK, null) }
        assertEquals("hook boom", e.cause!!.message)
        assertFalse(r.isRequesting)
    }

    @Test
    fun `연 특수 권한 화면은 저장 상태로 이어져 재생성 뒤 결과도 명세에 간다`() {
        val got = ArrayList<Intent>()
        val m = fakeManager(app, FakeDevice())
        val first = SavedStateOwner()
        val r1 = m.createRequester(FakeRegistry(), "k", { null }, listOf(PermissionSpecs.screenCapture { _, _ -> }), first.registry) {}
        r1.launch()
        val saved = first.save()
        r1.unregister()

        val second = SavedStateOwner(saved)
        var done = 0
        val r2 = m.createRequester(FakeRegistry(), "k", { null }, listOf(PermissionSpecs.screenCapture { _, d -> got += d }), second.registry) {
            done++
        } as net.plumt.permission.internal.DefaultPermissionRequester
        assertTrue(r2.isRequesting)
        r2.onSettingsResult(androidx.activity.result.ActivityResult(Activity.RESULT_OK, Intent("token")))
        assertEquals(listOf("token"), got.map { it.action })
        assertEquals(1, done)
        // 결과 없이 돌아온 경우(옛 호출)는 명세에 넘기지 않는다
        r2.launch()
        r2.onSettingsResult()
        assertEquals(1, got.size)
    }

    // ---- Picture-in-Picture

    @Test
    @Config(sdk = [26, 29, 34, 35])
    fun `PiP 는 앱 작업 모드를 따르고 기본값은 허용이다`() {
        val spec = PermissionSpecs.pictureInPicture
        setOp(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, AppOpsManager.MODE_IGNORED)
        assertEquals(PermissionStatus.Denied(false), manager.check(spec))
        setOp(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, AppOpsManager.MODE_ERRORED)
        assertFalse(spec.isGranted(app))
        setOp(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, AppOpsManager.MODE_DEFAULT)
        assertTrue(spec.isGranted(app))
        setOp(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, AppOpsManager.MODE_ALLOWED)
        assertEquals(PermissionStatus.Granted, manager.check(spec))
        val intent = spec.settingsIntent(app)
        assertEquals("android.settings.PICTURE_IN_PICTURE_SETTINGS", intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
    }

    @Test
    @Config(sdk = [23, 25])
    fun `PiP API 26 미만은 NotApplicable 이고 직접 물으면 거짓`() {
        assertEquals(PermissionStatus.NotApplicable(Build.VERSION.SDK_INT), manager.check(PermissionSpecs.pictureInPicture))
        assertFalse(PermissionSpecs.pictureInPicture.isGranted(app))
    }

    // ---- 역할

    private fun roles() = shadowOf(app.getSystemService(Context.ROLE_SERVICE) as RoleManager)

    @Test
    @Config(sdk = [29, 31, 34, 35])
    fun `역할은 RoleManager 를 따르고 요청 대화상자를 연다`() {
        for (role in AppRole.BUILT_IN.filter { it.requestable }) {
            val spec = PermissionSpecs.role(role)
            // 이 기기에 없는 역할은 기본 앱 설정 화면
            assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, spec.settingsIntent(app).action)
            roles().addAvailableRole(role.roleName)
            val intent = spec.settingsIntent(app)
            assertEquals(role.roleName, "android.app.role.action.REQUEST_ROLE", intent.action)
            assertEquals(role.roleName, intent.getStringExtra("android.intent.extra.ROLE_NAME"))
            assertEquals(PermissionStatus.Denied(false), manager.check(spec))
            roles().addHeldRole(role.roleName)
            assertEquals(PermissionStatus.Granted, manager.check(spec))
            roles().removeHeldRole(role.roleName)
        }
        // 보조 앱은 요청할 수 없는 역할 — 늘 기본 앱 설정 화면
        roles().addAvailableRole(RoleManager.ROLE_ASSISTANT)
        assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, PermissionSpecs.role(AppRole.ASSISTANT).settingsIntent(app).action)
    }

    @Test
    @Config(sdk = [23, 26, 28])
    fun `API 29 미만은 SMS·전화만 옛 기본 앱 API 로 다룬다`() {
        val sms = PermissionSpecs.role(AppRole.SMS)
        ShadowTelephony.ShadowSms.setDefaultSmsPackage("other")
        assertEquals(PermissionStatus.Denied(false), manager.check(sms))
        ShadowTelephony.ShadowSms.setDefaultSmsPackage(app.packageName)
        assertEquals(PermissionStatus.Granted, manager.check(sms))
        val smsIntent = sms.settingsIntent(app)
        @Suppress("DEPRECATION")
        assertEquals(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT, smsIntent.action)
        @Suppress("DEPRECATION")
        assertEquals(app.packageName, smsIntent.getStringExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME))

        val dialer = PermissionSpecs.role(AppRole.DIALER)
        val telecom = shadowOf(app.getSystemService(Context.TELECOM_SERVICE) as TelecomManager)
        telecom.setDefaultDialerPackage("other")
        assertEquals(PermissionStatus.Denied(false), manager.check(dialer))
        telecom.setDefaultDialerPackage(app.packageName)
        assertEquals(PermissionStatus.Granted, manager.check(dialer))
        val dialerIntent = dialer.settingsIntent(app)
        assertEquals(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER, dialerIntent.action)
        assertEquals(app.packageName, dialerIntent.getStringExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME))

        for (role in listOf(AppRole.BROWSER, AppRole.HOME, AppRole.CALL_SCREENING, AppRole.CALL_REDIRECTION, AppRole.ASSISTANT)) {
            val spec = PermissionSpecs.role(role)
            assertEquals(PermissionStatus.NotApplicable(Build.VERSION.SDK_INT), manager.check(spec))
            assertFalse(spec.isGranted(app))
            assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, spec.settingsIntent(app).action)
        }
    }

    // ---- Health Connect

    @Test
    fun `Health Connect 인자 검사`() {
        val none = assertThrows(IllegalArgumentException::class.java) { PermissionSpecs.healthConnect() }
        assertTrue(none.message!!.contains("read·write"))
        for (bad in listOf("", "steps", "1STEPS", "STEPS-X", "android.permission.health.READ_STEPS", "걸음", "STEPS ")) {
            val e = assertThrows(IllegalArgumentException::class.java) { PermissionSpecs.healthConnect(write = arrayOf("STEPS", bad)) }
            assertTrue(bad, e.message!!.contains("\"$bad\""))
        }
        val long = "A".repeat(10_000)
        assertEquals(listOf("android.permission.health.READ_$long"), PermissionSpecs.healthConnect(arrayOf(long)).permissions(PermissionEnvironment.of(34)))
    }

    @Test
    fun `Health Connect 는 API 34 이상에서만 권한을 요청한다`() {
        val spec = PermissionSpecs.healthConnect(arrayOf("STEPS", "HEART_RATE"), arrayOf("STEPS"))
        assertEquals("health_connect.read_steps+read_heart_rate+write_steps", spec.id)
        for (sdk in 23..33) assertEquals(emptyList<String>(), spec.permissions(PermissionEnvironment.of(sdk)))
        val expected = listOf(
            "android.permission.health.READ_STEPS",
            "android.permission.health.READ_HEART_RATE",
            "android.permission.health.WRITE_STEPS",
        )
        for (sdk in 34..36) assertEquals(expected, spec.permissions(PermissionEnvironment.of(sdk)))
        // 기기 API 로 가른다 — targetSdk 가 낮아도 플랫폼 권한이다
        assertEquals(expected, spec.permissions(PermissionEnvironment.of(34, targetSdkInt = 30)))
        assertEquals(GrantLevel.Partial, spec.evaluate(PermissionEnvironment.of(34)) { it == expected[0] })
    }

    @Test
    fun `Health Connect 백그라운드 읽기는 다른 읽기를 하나라도 받은 뒤 묻는다`() {
        val bg = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        val steps = "android.permission.health.READ_STEPS"
        val env = PermissionEnvironment.of(34)
        val spec = PermissionSpecs.healthConnect(arrayOf("STEPS", "HEALTH_DATA_IN_BACKGROUND"), arrayOf("WEIGHT"))
        assertEquals(listOf(steps, "android.permission.health.WRITE_WEIGHT"), spec.nextRequest(env) { false })
        assertEquals(listOf("android.permission.health.WRITE_WEIGHT", bg), spec.nextRequest(env) { it == steps })
        assertEquals(listOf(bg), spec.nextRequest(env) { it != bg })
        assertEquals(emptyList<String>(), spec.nextRequest(env) { true })
        // 쓰기만 받았으면 백그라운드 읽기는 아직이다
        assertEquals(listOf(steps), spec.nextRequest(env) { it == "android.permission.health.WRITE_WEIGHT" })
        // 백그라운드만 고른 명세는 그것만 묻는다
        assertEquals(listOf(bg), PermissionSpecs.healthConnect(arrayOf("HEALTH_DATA_IN_BACKGROUND")).nextRequest(env) { false })
        // 백그라운드가 없으면 기본 규칙
        assertEquals(listOf(steps), PermissionSpecs.healthConnect(arrayOf("STEPS")).nextRequest(env) { false })
    }

    @Test
    @Config(sdk = [34, 35])
    fun `Health Connect 근거 화면이 없으면 선언 누락을 알리고 디버그 요청은 IllegalStateException`() {
        val spec = PermissionSpecs.healthConnect(arrayOf("STEPS"))
        assertTrue(spec.missingDeclarations(app).single().contains("android.intent.category.HEALTH_PERMISSIONS"))
        val declared = ALL_DECLARED + "android.permission.health.READ_STEPS"
        val registry = FakeRegistry()
        val r = fakeManager(app, FakeDevice(), sdkInt = 34, declared = declared).createRequester(registry, "h", { null }, listOf(spec)) {}
        val e = assertThrows(IllegalStateException::class.java) { r.launch() }
        assertTrue(e.message!!.contains("VIEW_PERMISSION_USAGE"))
        assertTrue(registry.launches.isEmpty())

        val component = ComponentName(app.packageName, "x.Rationale")
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_VIEW_PERMISSION_USAGE).apply { addCategory("android.intent.category.HEALTH_PERMISSIONS") },
        )
        assertEquals(emptyList<String>(), spec.missingDeclarations(app))
        assertTrue(r.launch())
        assertEquals(listOf("android.permission.health.READ_STEPS"), registry.lastPermissions)
    }

    @Test
    @Config(sdk = [33])
    fun `Health Connect API 34 미만은 NotApplicable 이고 선언 확인도 없다`() {
        val spec = PermissionSpecs.healthConnect(arrayOf("STEPS"))
        assertEquals(PermissionStatus.NotApplicable(33), manager.check(spec))
        assertEquals(emptyList<String>(), spec.missingDeclarations(app))
    }

    @Test
    fun `허용된 런타임 명세의 선언 확인은 하지 않는다`() {
        val device = FakeDevice().apply { grant("android.permission.health.READ_STEPS") }
        val r = fakeManager(app, device, sdkInt = 34).createRequester(FakeRegistry(), "g", { null }, listOf(PermissionSpecs.healthConnect(arrayOf("STEPS")))) {}
        // 근거 화면이 없어도 요청할 것이 없으면 막지 않는다
        assertTrue(r.launch())
    }

    // ---- 화면 켜기·오래 걸리는 작업·프로필 연결

    @Test
    @Config(sdk = [34, 35])
    fun `화면 켜기는 API 36 미만 기기에서 제약이 없고 앱 상세 설정을 연다`() {
        assertEquals(PermissionStatus.NotApplicable(Build.VERSION.SDK_INT), manager.check(PermissionSpecs.turnScreenOn))
        assertTrue(PermissionSpecs.turnScreenOn.isGranted(app))
        val intent = PermissionSpecs.turnScreenOn.settingsIntent(app)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
    }

    @Test
    @Config(sdk = [34, 35])
    fun `오래 걸리는 작업은 canRunUserInitiatedJobs 를 따르고 이 앱 화면을 연다`() {
        val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        assertEquals(scheduler.canRunUserInitiatedJobs(), PermissionSpecs.userInitiatedJobs.isGranted(app))
        val intent = PermissionSpecs.userInitiatedJobs.settingsIntent(app)
        assertEquals("android.settings.MANAGE_APP_LONG_RUNNING_JOBS", intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
    }

    @Test
    @Config(sdk = [33])
    fun `오래 걸리는 작업 API 34 미만은 NotApplicable 이고 직접 물으면 거짓`() {
        assertEquals(PermissionStatus.NotApplicable(33), manager.check(PermissionSpecs.userInitiatedJobs))
        assertFalse(PermissionSpecs.userInitiatedJobs.isGranted(app))
    }

    @Test
    @Config(sdk = [30, 34])
    fun `프로필 연결은 canInteractAcrossProfiles 를 따르고 요청할 수 있을 때만 요청 화면을 연다`() {
        val apps = app.getSystemService(Context.CROSS_PROFILE_APPS_SERVICE) as CrossProfileApps
        assertEquals(apps.canInteractAcrossProfiles(), PermissionSpecs.crossProfile.isGranted(app))
        assertFalse(apps.canRequestInteractAcrossProfiles())
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, PermissionSpecs.crossProfile.settingsIntent(app).action)

        val shadow = shadowOf(apps)
        shadow.addTargetUserProfile(UserHandle.getUserHandleForUid(Process.myUid() + 100_000))
        shadow.setHasRequestedInteractAcrossProfiles(true)
        if (apps.canRequestInteractAcrossProfiles()) {
            val intent = PermissionSpecs.crossProfile.settingsIntent(app)
            assertEquals(apps.createRequestInteractAcrossProfilesIntent().action, intent.action)
        }
        shadow.setInteractAcrossProfilesAppOp(AppOpsManager.MODE_ALLOWED)
        assertEquals(apps.canInteractAcrossProfiles(), PermissionSpecs.crossProfile.isGranted(app))
    }

    @Test
    @Config(sdk = [29])
    fun `프로필 연결 API 30 미만은 NotApplicable 이고 직접 물으면 거짓`() {
        assertEquals(PermissionStatus.NotApplicable(29), manager.check(PermissionSpecs.crossProfile))
        assertFalse(PermissionSpecs.crossProfile.isGranted(app))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, PermissionSpecs.crossProfile.settingsIntent(app).action)
    }

    @Test
    fun `새 특수 권한의 선언 권한은 선언 확인과 undeclaredPermissions 에 든다`() {
        val m = fakeManager(app, FakeDevice(), sdkInt = 36, declared = emptySet())
        assertEquals(
            setOf("android.permission.TURN_SCREEN_ON", "android.permission.RUN_USER_INITIATED_JOBS", "android.permission.INTERACT_ACROSS_PROFILES"),
            m.undeclaredPermissions(
                listOf(PermissionSpecs.turnScreenOn, PermissionSpecs.userInitiatedJobs, PermissionSpecs.crossProfile, PermissionSpecs.vpn),
                emptyList(),
            ),
        )
    }
}
