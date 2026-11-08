package net.plumt.permission

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 0.1.0 에서 더한 범위 — 로컬 네트워크·자동완성·입력기·말풍선·알림 채널 켜짐 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddedScopeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val device = FakeDevice()
    private val manager = fakeManager(app, device)

    @Test
    fun `로컬 네트워크는 targetSdk 37 이상에서만 요청한다`() {
        val spec = PermissionSpecs.localNetwork
        assertEquals("local_network", spec.id)
        assertEquals(emptyList<String>(), spec.permissions(PermissionEnvironment.of(36)))
        // 기기가 37 이어도 targetSdk 36 앱은 INTERNET 으로 묵시 허용 — 요청하지 않는다
        assertEquals(emptyList<String>(), spec.permissions(PermissionEnvironment.of(37, targetSdkInt = 36)))
        assertEquals(listOf("android.permission.ACCESS_LOCAL_NETWORK"), spec.permissions(PermissionEnvironment.of(37)))
    }

    @Test
    fun `자동완성 서비스는 선택 화면을 열고 서비스 선언 누락을 알린다`() {
        val spec = PermissionSpecs.autofillService
        assertFalse(spec.isApplicable(PermissionEnvironment.of(25)))
        assertTrue(spec.isApplicable(PermissionEnvironment.of(26)))
        assertEquals(null, spec.manifestPermission)
        assertEquals(PermissionStatus.Denied(false), manager.check(spec))
        val intent = spec.settingsIntent(app)
        assertEquals(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
        assertTrue(spec.missingDeclarations(app).single().contains("android.permission.BIND_AUTOFILL_SERVICE"))
        assertEquals("AutofillServiceSpec", spec.toString())
    }

    @Test
    fun `입력기는 켜진 입력기 목록에 이 앱이 있으면 허용이다`() {
        val spec = PermissionSpecs.inputMethod
        assertTrue(spec.isApplicable(PermissionEnvironment.of(23)))
        assertEquals(PermissionStatus.Denied(false), manager.check(spec))
        val imm = shadowOf(app.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
        imm.setEnabledInputMethodInfoList(listOf(InputMethodInfo("other.pkg", "other.pkg.Ime", "other", null)))
        assertEquals(PermissionStatus.Denied(false), manager.check(spec))
        imm.setEnabledInputMethodInfoList(listOf(InputMethodInfo(app.packageName, "${app.packageName}.Ime", "mine", null)))
        assertEquals(PermissionStatus.Granted, manager.check(spec))
        assertEquals(Settings.ACTION_INPUT_METHOD_SETTINGS, spec.settingsIntent(app).action)
        assertTrue(spec.missingDeclarations(app).single().contains("android.permission.BIND_INPUT_METHOD"))
    }

    @Test
    fun `말풍선은 API 30 부터 이 앱의 말풍선 설정 화면을 연다`() {
        val spec = PermissionSpecs.bubbles
        assertFalse(spec.isApplicable(PermissionEnvironment.of(29)))
        assertTrue(spec.isApplicable(PermissionEnvironment.of(30)))
        val intent = spec.settingsIntent(app)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals("BubblesSpec", spec.toString())
    }

    @Test
    @Config(sdk = [29])
    fun `말풍선이 없는 기기에서 직접 물으면 제약이 없는 것과 같다`() {
        assertTrue(PermissionSpecs.bubbles.isGranted(app))
        assertEquals(PermissionStatus.NotApplicable(29), fakeManager(app, device, sdkInt = 29).check(PermissionSpecs.bubbles))
    }

    private val notifications get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun `알림 채널 켜짐은 앱 알림·채널 중요도로 판정한다`() {
        device.grant("android.permission.POST_NOTIFICATIONS")
        val requirement = SystemRequirements.notificationChannelEnabled("news")
        assertEquals("notification_channel_enabled:news", requirement.id)
        // 채널을 만들지 않았으면 해결 화면이 없다
        assertEquals(RequirementStatus.Unmet, manager.check(requirement))
        notifications.createNotificationChannel(NotificationChannel("news", "News", NotificationManager.IMPORTANCE_DEFAULT))
        assertEquals(RequirementStatus.Satisfied, manager.check(requirement))
        notifications.createNotificationChannel(NotificationChannel("muted", "Muted", NotificationManager.IMPORTANCE_NONE))
        assertEquals(RequirementStatus.Disabled, manager.check(SystemRequirements.notificationChannelEnabled("muted")))
        var opened: EnableRequest? = null
        SystemRequirements.notificationChannelEnabled("muted").enableRequest(app) { opened = it }
        val channelIntent = (opened as EnableRequest.OfIntent).intent
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, channelIntent.action)
        assertEquals("muted", channelIntent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        // 앱 알림이 꺼졌으면 채널과 관계없이 꺼짐이고, 앱 알림 설정 화면을 연다
        shadowOf(notifications).setNotificationsEnabled(false)
        assertEquals(RequirementStatus.Disabled, manager.check(requirement))
        requirement.enableRequest(app) { opened = it }
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, (opened as EnableRequest.OfIntent).intent.action)
        assertThrows(IllegalArgumentException::class.java) { SystemRequirements.notificationChannelEnabled(" ") }
    }

    @Test
    @Config(sdk = [33])
    fun `알림 채널 켜짐은 API 33 이상에서 POST_NOTIFICATIONS 를 먼저 요구한다`() {
        val m = fakeManager(app, device, sdkInt = 33)
        shadowOf(notifications).setNotificationsEnabled(false)
        val status = m.check(SystemRequirements.notificationChannelEnabled("news"))
        assertEquals(RequirementStatus.PermissionRequired(setOf("android.permission.POST_NOTIFICATIONS")), status)
    }

    @Test
    @Config(sdk = [25])
    fun `API 26 미만은 채널이 없어 앱 알림 켜짐과 같다`() {
        val m = fakeManager(app, device, sdkInt = 25)
        assertEquals(RequirementStatus.Satisfied, m.check(SystemRequirements.notificationChannelEnabled("news")))
        shadowOf(notifications).setNotificationsEnabled(false)
        assertEquals(RequirementStatus.Disabled, m.check(SystemRequirements.notificationChannelEnabled("news")))
    }
}
