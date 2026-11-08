package net.plumt.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 내장 명세의 API 레벨별 권한 목록·판정·다음 요청 */
class BuiltInSpecsTest {

    private val allSdks = (23..36).toList()

    private fun env(sdk: Int, declared: Set<String> = ALL_DECLARED) = PermissionEnvironment.of(sdk, declared)

    private fun granted(vararg p: String): (String) -> Boolean = { it in p }

    private val none: (String) -> Boolean = { false }

    // ---- 단순 명세

    @Test
    fun `카메라와 마이크는 모든 API 레벨에서 권한 하나다`() {
        for (sdk in allSdks) {
            assertEquals(listOf(CAMERA), PermissionSpecs.camera.permissions(env(sdk)))
            assertEquals(listOf(MIC), PermissionSpecs.microphone.permissions(env(sdk)))
        }
        assertEquals("camera", PermissionSpecs.camera.id)
        assertEquals("microphone", PermissionSpecs.microphone.id)
    }

    @Test
    fun `알림은 API 33 부터만 런타임 권한이다`() {
        assertEquals(emptyList<String>(), PermissionSpecs.notifications.permissions(env(32)))
        assertEquals(listOf(NOTIFY), PermissionSpecs.notifications.permissions(env(33)))
        for (sdk in allSdks) {
            assertEquals(sdk >= 33, PermissionSpecs.notifications.permissions(env(sdk)).isNotEmpty())
        }
    }

    @Test
    fun `활동 인식은 API 29 부터만 런타임 권한이다`() {
        assertEquals(emptyList<String>(), PermissionSpecs.activityRecognition.permissions(env(28)))
        assertEquals(listOf(ACTIVITY), PermissionSpecs.activityRecognition.permissions(env(29)))
        assertEquals("activity_recognition", PermissionSpecs.activityRecognition.id)
    }

    @Test
    fun `기본 판정은 전부 FULL 일부 PARTIAL 없음 NONE`() {
        val spec = PermissionSpecs.calendar(read = true, write = true)
        val e = env(34)
        assertEquals(GrantLevel.Full, spec.evaluate(e, granted(CAL_R, CAL_W)))
        assertEquals(GrantLevel.Partial, spec.evaluate(e, granted(CAL_R)))
        assertEquals(GrantLevel.None, spec.evaluate(e, none))
        assertEquals(listOf(CAL_W), spec.nextRequest(e, granted(CAL_R)))
        assertEquals(listOf(CAL_R, CAL_W), spec.nextRequest(e, none))
        assertEquals(emptyList<String>(), spec.nextRequest(e, granted(CAL_R, CAL_W)))
    }

    @Test
    fun `빈 권한 목록의 기본 판정은 FULL 이다`() {
        assertEquals(GrantLevel.Full, PermissionSpecs.notifications.evaluate(env(30), none))
    }

    // ---- 연락처·캘린더

    @Test
    fun `연락처와 캘린더는 고른 권한만 담고 id 가 고른 것을 드러낸다`() {
        assertEquals(listOf(CON_R), PermissionSpecs.contacts().permissions(env(34)))
        assertEquals(listOf(CON_R, CON_W), PermissionSpecs.contacts(write = true).permissions(env(34)))
        assertEquals(listOf(CON_W), PermissionSpecs.contacts(read = false, write = true).permissions(env(34)))
        assertEquals("contacts.read", PermissionSpecs.contacts().id)
        assertEquals("contacts.read+write", PermissionSpecs.contacts(write = true).id)
        assertEquals("contacts.write", PermissionSpecs.contacts(read = false, write = true).id)
        assertEquals(listOf(CAL_R), PermissionSpecs.calendar().permissions(env(23)))
        assertEquals(listOf(CAL_W), PermissionSpecs.calendar(read = false, write = true).permissions(env(23)))
        assertEquals("calendar.read+write", PermissionSpecs.calendar(write = true).id)
    }

    @Test
    fun `연락처와 캘린더에 아무것도 고르지 않으면 IllegalArgumentException`() {
        val c = assertThrows(IllegalArgumentException::class.java) { PermissionSpecs.contacts(read = false) }
        assertEquals("read·write 가운데 하나는 참이어야 합니다: read=false, write=false", c.message)
        val k = assertThrows(IllegalArgumentException::class.java) { PermissionSpecs.calendar(read = false) }
        assertEquals("read·write 가운데 하나는 참이어야 합니다: read=false, write=false", k.message)
    }

    // ---- 위치

    @Test
    fun `정밀 위치는 FINE COARSE 를 함께 요청하고 COARSE 만이면 PARTIAL`() {
        val spec = PermissionSpecs.location()
        for (sdk in allSdks) {
            val e = env(sdk)
            assertEquals(listOf(FINE, COARSE), spec.permissions(e))
            assertEquals(GrantLevel.Full, spec.evaluate(e, granted(FINE, COARSE)))
            assertEquals(GrantLevel.Full, spec.evaluate(e, granted(FINE)))
            assertEquals(GrantLevel.Partial, spec.evaluate(e, granted(COARSE)))
            assertEquals(GrantLevel.None, spec.evaluate(e, none))
            // 대략 → 정밀 승격도 둘을 함께 요청한다
            assertEquals(listOf(FINE, COARSE), spec.nextRequest(e, granted(COARSE)))
            assertEquals(listOf(FINE, COARSE), spec.nextRequest(e, none))
            assertEquals(emptyList<String>(), spec.nextRequest(e, granted(FINE, COARSE)))
        }
        assertEquals("location.precise", spec.id)
    }

    @Test
    fun `대략 위치는 COARSE 만 요청하고 COARSE 나 FINE 이면 FULL`() {
        val spec = PermissionSpecs.location(precise = false)
        val e = env(31)
        assertEquals(listOf(COARSE), spec.permissions(e))
        assertEquals(GrantLevel.Full, spec.evaluate(e, granted(COARSE)))
        assertEquals(GrantLevel.Full, spec.evaluate(e, granted(FINE)))
        assertEquals(GrantLevel.None, spec.evaluate(e, none))
        assertEquals(emptyList<String>(), spec.nextRequest(e, granted(COARSE)))
        assertEquals(listOf(COARSE), spec.nextRequest(e, none))
        assertEquals("location.approximate", spec.id)
    }

    // ---- 백그라운드 위치

    @Test
    fun `백그라운드 위치 API 29 미만은 포그라운드 위치와 같다`() {
        val spec = PermissionSpecs.backgroundLocation()
        for (sdk in 23..28) {
            val e = env(sdk)
            assertEquals(listOf(FINE, COARSE), spec.permissions(e))
            assertEquals(GrantLevel.Full, spec.evaluate(e, granted(FINE)))
            assertEquals(GrantLevel.Partial, spec.evaluate(e, granted(COARSE)))
            assertEquals(listOf(FINE, COARSE), spec.nextRequest(e, none))
            assertEquals(emptyList<String>(), spec.nextRequest(e, granted(FINE, COARSE)))
        }
    }

    @Test
    fun `백그라운드 위치 API 29 는 포그라운드와 함께 한 번에 요청한다`() {
        val spec = PermissionSpecs.backgroundLocation()
        val e = env(29)
        assertEquals(listOf(FINE, COARSE, BG), spec.permissions(e))
        assertEquals(listOf(FINE, COARSE, BG), spec.nextRequest(e, none))
        assertEquals(listOf(BG), spec.nextRequest(e, granted(FINE, COARSE)))
        assertEquals(GrantLevel.Full, spec.evaluate(e, granted(FINE, COARSE, BG)))
        assertEquals(GrantLevel.Partial, spec.evaluate(e, granted(FINE, COARSE)))
        assertEquals(GrantLevel.None, spec.evaluate(e, none))
        // 백그라운드만 있고 정밀이 빠진 경우 — 남은 포그라운드를 요청한다
        assertEquals(listOf(FINE, COARSE), spec.nextRequest(e, granted(COARSE, BG)))
    }

    @Test
    fun `백그라운드 위치 API 30 이상은 포그라운드 먼저 그다음 백그라운드만 요청한다`() {
        val spec = PermissionSpecs.backgroundLocation()
        for (sdk in 30..36) {
            val e = env(sdk)
            assertEquals(listOf(FINE, COARSE), spec.nextRequest(e, none))
            assertEquals(listOf(BG), spec.nextRequest(e, granted(FINE, COARSE)))
            // 대략적 위치만 있어도 백그라운드를 먼저 묻는다
            assertEquals(listOf(BG), spec.nextRequest(e, granted(COARSE)))
            // 백그라운드는 있고 정밀이 없으면 정밀 승격을 묻는다
            assertEquals(listOf(FINE, COARSE), spec.nextRequest(e, granted(COARSE, BG)))
            assertEquals(emptyList<String>(), spec.nextRequest(e, granted(FINE, COARSE, BG)))
            assertEquals(GrantLevel.Partial, spec.evaluate(e, granted(COARSE, BG)))
            assertEquals(GrantLevel.Partial, spec.evaluate(e, granted(FINE)))
            assertEquals(GrantLevel.Full, spec.evaluate(e, granted(FINE, BG)))
            assertEquals(GrantLevel.None, spec.evaluate(e, granted(BG)))
        }
        assertEquals("location.background.precise", spec.id)
    }

    @Test
    fun `대략 백그라운드 위치는 COARSE 와 BACKGROUND 로 FULL`() {
        val spec = PermissionSpecs.backgroundLocation(precise = false)
        val e = env(33)
        assertEquals(listOf(COARSE, BG), spec.permissions(e))
        assertEquals(GrantLevel.Full, spec.evaluate(e, granted(COARSE, BG)))
        assertEquals(listOf(COARSE), spec.nextRequest(e, none))
        assertEquals("location.background.approximate", spec.id)
    }

    // ---- 사진·동영상

    @Test
    fun `미디어 API 32 이하는 READ_EXTERNAL_STORAGE`() {
        val spec = PermissionSpecs.media(images = true, video = true)
        for (sdk in 23..32) {
            val e = env(sdk)
            assertEquals(listOf(LEGACY), spec.permissions(e))
            assertEquals(GrantLevel.Full, spec.evaluate(e, granted(LEGACY)))
            assertEquals(GrantLevel.None, spec.evaluate(e, granted(IMAGES)))
            assertEquals(listOf(LEGACY), spec.nextRequest(e, none))
            assertEquals(emptyList<String>(), spec.nextRequest(e, granted(LEGACY)))
        }
    }

    @Test
    fun `미디어 API 33 은 고른 미디어 권한이고 일부면 PARTIAL`() {
        val e = env(33)
        val both = PermissionSpecs.media(images = true, video = true)
        assertEquals(listOf(IMAGES, VIDEO), both.permissions(e))
        assertEquals(GrantLevel.Partial, both.evaluate(e, granted(IMAGES)))
        assertEquals(GrantLevel.Full, both.evaluate(e, granted(IMAGES, VIDEO)))
        // API 33 에는 일부 선택이 없어 USER_SELECTED 를 보지 않는다
        assertEquals(GrantLevel.None, both.evaluate(e, granted(USER_SELECTED)))
        assertEquals(listOf(VIDEO), PermissionSpecs.media(images = false, video = true).permissions(e))
        assertEquals(listOf(IMAGES), PermissionSpecs.media().permissions(e))
    }

    @Test
    fun `미디어 API 34 이상은 선언했을 때만 USER_SELECTED 를 함께 요청하고 그것만이면 PARTIAL`() {
        val spec = PermissionSpecs.media()
        for (sdk in 34..36) {
            val e = env(sdk)
            assertEquals(listOf(IMAGES, USER_SELECTED), spec.permissions(e))
            assertEquals(GrantLevel.Partial, spec.evaluate(e, granted(USER_SELECTED)))
            assertEquals(GrantLevel.Full, spec.evaluate(e, granted(IMAGES)))
            assertEquals(GrantLevel.None, spec.evaluate(e, none))
            // 선택을 넓히려면 USER_SELECTED 를 포함해 다시 요청해야 한다
            assertEquals(listOf(IMAGES, USER_SELECTED), spec.nextRequest(e, granted(USER_SELECTED)))
            assertEquals(emptyList<String>(), spec.nextRequest(e, granted(IMAGES)))
            val undeclared = env(sdk, ALL_DECLARED - USER_SELECTED)
            assertEquals(listOf(IMAGES), spec.permissions(undeclared))
        }
        assertEquals("media.images", spec.id)
        assertEquals("media.images+video", PermissionSpecs.media(video = true).id)
        assertEquals("media.video", PermissionSpecs.media(images = false, video = true).id)
    }

    @Test
    fun `미디어에 아무것도 고르지 않으면 IllegalArgumentException`() {
        val e = assertThrows(IllegalArgumentException::class.java) { PermissionSpecs.media(images = false, video = false) }
        assertEquals("images·video 가운데 하나는 참이어야 합니다: images=false, video=false", e.message)
    }

    // ---- 블루투스

    @Test
    fun `블루투스 API 31 이상은 고른 런타임 권한`() {
        for (sdk in 31..36) {
            val e = env(sdk)
            assertEquals(listOf(BT_CONNECT), PermissionSpecs.bluetooth().permissions(e))
            assertEquals(listOf(BT_SCAN, BT_CONNECT, BT_ADV), PermissionSpecs.bluetooth(true, true, true).permissions(e))
            assertEquals(listOf(BT_SCAN), PermissionSpecs.bluetooth(scan = true, connect = false).permissions(e))
            assertEquals(listOf(BT_ADV), PermissionSpecs.bluetooth(connect = false, advertise = true).permissions(e))
        }
    }

    @Test
    fun `블루투스 API 30 이하는 검색에만 위치 권한이 들고 연결만이면 요청할 것이 없다`() {
        for (sdk in 23..30) {
            val e = env(sdk)
            assertEquals(emptyList<String>(), PermissionSpecs.bluetooth().permissions(e))
            assertEquals(emptyList<String>(), PermissionSpecs.bluetooth(connect = false, advertise = true).permissions(e))
            val scan = PermissionSpecs.bluetooth(scan = true).permissions(e)
            assertEquals(if (sdk >= 29) listOf(FINE) else listOf(COARSE), scan)
        }
    }

    @Test
    fun `블루투스 id 와 인자 검사`() {
        assertEquals("bluetooth.connect", PermissionSpecs.bluetooth().id)
        assertEquals("bluetooth.scan+connect+advertise", PermissionSpecs.bluetooth(true, true, true).id)
        val ex = assertThrows(IllegalArgumentException::class.java) { PermissionSpecs.bluetooth(false, false, false) }
        assertEquals(
            "scan·connect·advertise 가운데 하나는 참이어야 합니다: scan=false, connect=false, advertise=false",
            ex.message,
        )
    }

    // ---- 특수 권한

    @Test
    fun `배터리 최적화 예외는 모든 API 레벨에 적용되고 선언 권한을 밝힌다`() {
        val spec = PermissionSpecs.ignoreBatteryOptimizations
        for (sdk in allSdks) assertTrue(spec.isApplicable(env(sdk)))
        assertEquals("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", spec.manifestPermission)
        assertEquals("battery_optimization.ignore", spec.id)
        assertEquals("IgnoreBatteryOptimizationsSpec", spec.toString())
    }

    // ---- 같음

    @Test
    fun `같은 인자로 만든 명세는 같고 다른 인자는 다르다`() {
        assertEquals(PermissionSpecs.location(), PermissionSpecs.location())
        assertEquals(PermissionSpecs.location().hashCode(), PermissionSpecs.location().hashCode())
        assertNotEquals(PermissionSpecs.location(), PermissionSpecs.location(false))
        assertEquals(PermissionSpecs.media(video = true), PermissionSpecs.media(video = true))
        assertEquals(PermissionSpecs.bluetooth(scan = true), PermissionSpecs.bluetooth(scan = true))
        assertEquals(PermissionSpecs.backgroundLocation(), PermissionSpecs.backgroundLocation())
        assertEquals(PermissionSpecs.contacts(), PermissionSpecs.contacts())
        assertSame(PermissionSpecs.camera, PermissionSpecs.camera)
    }

    @Test
    fun `모든 내장 명세의 id 는 서로 다르다`() {
        val specs = listOf(
            PermissionSpecs.camera, PermissionSpecs.microphone, PermissionSpecs.notifications,
            PermissionSpecs.activityRecognition, PermissionSpecs.location(), PermissionSpecs.location(false),
            PermissionSpecs.backgroundLocation(), PermissionSpecs.backgroundLocation(false), PermissionSpecs.media(),
            PermissionSpecs.media(video = true), PermissionSpecs.contacts(), PermissionSpecs.calendar(),
            PermissionSpecs.bluetooth(), PermissionSpecs.ignoreBatteryOptimizations,
        )
        assertEquals(specs.size, specs.map { it.id }.toSet().size)
    }

    // ---- 환경

    @Test
    fun `환경 of 는 선언 집합을 복사해 보관한다`() {
        val declared = mutableSetOf(CAMERA)
        val e = PermissionEnvironment.of(30, declared)
        declared.clear()
        assertTrue(e.isDeclared(CAMERA))
        assertEquals(false, e.isDeclared(MIC))
        assertEquals(30, e.sdkInt)
        assertEquals(false, PermissionEnvironment.of(1).isDeclared(CAMERA))
        assertTrue(e.toString().contains("sdkInt=30"))
    }

    @Test
    fun `환경 of 는 1 미만 API 레벨을 거부한다`() {
        for (bad in listOf(0, -1, Int.MIN_VALUE)) {
            val ex = assertThrows(IllegalArgumentException::class.java) { PermissionEnvironment.of(bad) }
            assertEquals("sdkInt 는 1 이상이어야 합니다: $bad", ex.message)
        }
        assertEquals(Int.MAX_VALUE, PermissionEnvironment.of(Int.MAX_VALUE).sdkInt)
    }

    companion object {
        const val CAMERA = "android.permission.CAMERA"
        const val MIC = "android.permission.RECORD_AUDIO"
        const val NOTIFY = "android.permission.POST_NOTIFICATIONS"
        const val ACTIVITY = "android.permission.ACTIVITY_RECOGNITION"
        const val FINE = "android.permission.ACCESS_FINE_LOCATION"
        const val COARSE = "android.permission.ACCESS_COARSE_LOCATION"
        const val BG = "android.permission.ACCESS_BACKGROUND_LOCATION"
        const val IMAGES = "android.permission.READ_MEDIA_IMAGES"
        const val VIDEO = "android.permission.READ_MEDIA_VIDEO"
        const val USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        const val LEGACY = "android.permission.READ_EXTERNAL_STORAGE"
        const val CON_R = "android.permission.READ_CONTACTS"
        const val CON_W = "android.permission.WRITE_CONTACTS"
        const val CAL_R = "android.permission.READ_CALENDAR"
        const val CAL_W = "android.permission.WRITE_CALENDAR"
        const val BT_SCAN = "android.permission.BLUETOOTH_SCAN"
        const val BT_CONNECT = "android.permission.BLUETOOTH_CONNECT"
        const val BT_ADV = "android.permission.BLUETOOTH_ADVERTISE"
    }
}
