package net.plumt.permission

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 새 런타임 명세 — API 23~36 분기, targetSdk 분기, 단계 요청, 인자 검사 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoreRuntimeSpecsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val all = 23..36

    private fun RuntimePermissionSpec.at(sdk: Int, target: Int = sdk) = permissions(PermissionEnvironment.of(sdk, targetSdkInt = target))

    private fun p(name: String) = "android.permission.$name"

    @Test
    fun `언제나 같은 권한 하나를 요청하는 명세`() {
        val cases = mapOf(
            PermissionSpecs.phoneState to p("READ_PHONE_STATE"),
            PermissionSpecs.callPhone to p("CALL_PHONE"),
            PermissionSpecs.processOutgoingCalls to p("PROCESS_OUTGOING_CALLS"),
            PermissionSpecs.addVoicemail to "com.android.voicemail.permission.ADD_VOICEMAIL",
            PermissionSpecs.useSip to p("USE_SIP"),
            PermissionSpecs.accounts to p("GET_ACCOUNTS"),
        )
        for ((spec, permission) in cases) for (sdk in all) assertEquals(spec.id, listOf(permission), spec.at(sdk))
    }

    @Test
    fun `최소 API 미만은 요청할 것이 없는 명세`() {
        val cases = listOf(
            Triple(PermissionSpecs.answerPhoneCalls, 26, p("ANSWER_PHONE_CALLS")),
            Triple(PermissionSpecs.acceptHandover, 28, p("ACCEPT_HANDOVER")),
            Triple(PermissionSpecs.mediaLocation, 29, p("ACCESS_MEDIA_LOCATION")),
            Triple(PermissionSpecs.uwbRanging, 31, p("UWB_RANGING")),
            Triple(PermissionSpecs.ranging, 36, p("RANGING")),
        )
        for ((spec, min, permission) in cases) {
            for (sdk in all) {
                val expected = if (sdk >= min) listOf(permission) else emptyList()
                assertEquals("${spec.id}@$sdk", expected, spec.at(sdk))
            }
            // targetSdk 가 낮으면 옛 모델 — 요청할 것이 없다
            assertEquals(emptyList<String>(), spec.at(36, target = min - 1))
        }
    }

    @Test
    fun `전화번호는 API 26 부터 READ_PHONE_NUMBERS 그 아래는 READ_PHONE_STATE`() {
        for (sdk in all) {
            val expected = if (sdk >= 26) p("READ_PHONE_NUMBERS") else p("READ_PHONE_STATE")
            assertEquals(listOf(expected), PermissionSpecs.phoneNumbers.at(sdk))
        }
        assertEquals(listOf(p("READ_PHONE_STATE")), PermissionSpecs.phoneNumbers.at(34, target = 25))
        assertEquals("PhoneNumbersSpec", PermissionSpecs.phoneNumbers.toString())
    }

    @Test
    fun `통화 기록은 고른 권한만 요청하고 아무것도 고르지 않으면 IllegalArgumentException`() {
        assertEquals(listOf(p("READ_CALL_LOG")), PermissionSpecs.callLog().at(23))
        assertEquals(listOf(p("READ_CALL_LOG"), p("WRITE_CALL_LOG")), PermissionSpecs.callLog(write = true).at(36))
        assertEquals(listOf(p("WRITE_CALL_LOG")), PermissionSpecs.callLog(read = false, write = true).at(30))
        assertEquals("call_log.read+write", PermissionSpecs.callLog(write = true).id)
        val e = assertThrows(IllegalArgumentException::class.java) { PermissionSpecs.callLog(read = false) }
        assertTrue(e.message!!.contains("read=false, write=false"))
    }

    @Test
    fun `SMS 는 고른 권한을 고른 순서대로 묶는다`() {
        assertEquals(listOf(p("SEND_SMS")), PermissionSpecs.sms().at(23))
        val everything = PermissionSpecs.sms(send = true, receive = true, read = true, receiveMms = true, receiveWapPush = true)
        assertEquals(
            listOf(p("SEND_SMS"), p("RECEIVE_SMS"), p("READ_SMS"), p("RECEIVE_MMS"), p("RECEIVE_WAP_PUSH")),
            everything.at(36),
        )
        assertEquals("sms.send+receive+read+receive_mms+receive_wap_push", everything.id)
        assertEquals("sms.read", PermissionSpecs.sms(send = false, read = true).id)
        assertEquals(PermissionSpecs.sms(), PermissionSpecs.sms())
        val e = assertThrows(IllegalArgumentException::class.java) { PermissionSpecs.sms(send = false) }
        assertTrue(e.message!!.contains("send=false"))
    }

    @Test
    fun `저장소는 API 32 이하 읽기 API 29 이하 쓰기 API 33 이상 없음`() {
        for (sdk in all) {
            val read = if (sdk <= 32) listOf(p("READ_EXTERNAL_STORAGE")) else emptyList()
            assertEquals(read, PermissionSpecs.storage().at(sdk))
            val write = when {
                sdk <= 29 -> listOf(p("READ_EXTERNAL_STORAGE"), p("WRITE_EXTERNAL_STORAGE"))
                sdk <= 32 -> listOf(p("READ_EXTERNAL_STORAGE"))
                else -> emptyList()
            }
            assertEquals("write@$sdk", write, PermissionSpecs.storage(write = true).at(sdk))
        }
        // targetSdk 29 앱은 API 34 기기에서도 옛 모델
        assertEquals(listOf(p("READ_EXTERNAL_STORAGE"), p("WRITE_EXTERNAL_STORAGE")), PermissionSpecs.storage(true).at(34, target = 29))
        assertNotEquals(PermissionSpecs.storage().id, PermissionSpecs.storage(true).id)
    }

    @Test
    fun `오디오는 API 33 부터 READ_MEDIA_AUDIO`() {
        for (sdk in all) {
            val expected = if (sdk >= 33) p("READ_MEDIA_AUDIO") else p("READ_EXTERNAL_STORAGE")
            assertEquals(listOf(expected), PermissionSpecs.audio.at(sdk))
        }
        assertEquals(listOf(p("READ_EXTERNAL_STORAGE")), PermissionSpecs.audio.at(34, target = 32))
        assertEquals("AudioSpec", PermissionSpecs.audio.toString())
    }

    @Test
    fun `주변 Wi-Fi 기기는 API 33 부터 NEARBY_WIFI_DEVICES 그 아래는 위치 또는 없음`() {
        for (sdk in all) {
            val legacy = when {
                sdk >= 33 -> p("NEARBY_WIFI_DEVICES")
                sdk >= 29 -> p("ACCESS_FINE_LOCATION")
                else -> p("ACCESS_COARSE_LOCATION")
            }
            assertEquals(listOf(legacy), PermissionSpecs.nearbyWifiDevices().at(sdk))
            val none = if (sdk >= 33) listOf(p("NEARBY_WIFI_DEVICES")) else emptyList()
            assertEquals(none, PermissionSpecs.nearbyWifiDevices(legacyLocation = false).at(sdk))
        }
        assertNotEquals(PermissionSpecs.nearbyWifiDevices().id, PermissionSpecs.nearbyWifiDevices(false).id)
    }

    // ---- 신체 센서

    private val heart = "android.permission.health.READ_HEART_RATE"
    private val oxygen = "android.permission.health.READ_OXYGEN_SATURATION"
    private val skin = "android.permission.health.READ_SKIN_TEMPERATURE"
    private val healthBackground = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    @Test
    fun `신체 센서 API 36 은 건강 권한 그 아래는 BODY_SENSORS`() {
        for (sdk in all) {
            val expected = if (sdk >= 36) listOf(heart) else listOf(p("BODY_SENSORS"))
            assertEquals(expected, PermissionSpecs.bodySensors().at(sdk))
        }
        val three = PermissionSpecs.bodySensors(oxygenSaturation = true, skinTemperature = true)
        assertEquals(listOf(heart, oxygen, skin), three.at(36))
        assertEquals(listOf(p("BODY_SENSORS")), three.at(35))
        // targetSdk 35 앱은 API 36 기기에서도 BODY_SENSORS
        assertEquals(listOf(p("BODY_SENSORS")), three.at(36, target = 35))
        assertEquals(listOf(oxygen), PermissionSpecs.bodySensors(heartRate = false, oxygenSaturation = true).at(36))
    }

    @Test
    fun `신체 센서 백그라운드는 API 33~35 BODY_SENSORS_BACKGROUND 36 건강 백그라운드 32 이하 없음`() {
        val spec = PermissionSpecs.bodySensors(background = true)
        for (sdk in all) {
            val expected = when {
                sdk >= 36 -> listOf(heart, healthBackground)
                sdk >= 33 -> listOf(p("BODY_SENSORS"), p("BODY_SENSORS_BACKGROUND"))
                else -> listOf(p("BODY_SENSORS"))
            }
            assertEquals("bg@$sdk", expected, spec.at(sdk))
        }
        assertEquals("body_sensors.heart_rate+background", spec.id)
    }

    @Test
    fun `신체 센서 백그라운드는 포그라운드를 받은 뒤 따로 요청하고 그사이는 Partial`() {
        val spec = PermissionSpecs.bodySensors(background = true)
        val env = PermissionEnvironment.of(34)
        val granted = mutableSetOf<String>()
        assertEquals(listOf(p("BODY_SENSORS")), spec.nextRequest(env) { it in granted })
        assertEquals(GrantLevel.None, spec.evaluate(env) { it in granted })
        granted += p("BODY_SENSORS")
        assertEquals(listOf(p("BODY_SENSORS_BACKGROUND")), spec.nextRequest(env) { it in granted })
        assertEquals(GrantLevel.Partial, spec.evaluate(env) { it in granted })
        granted += p("BODY_SENSORS_BACKGROUND")
        assertEquals(emptyList<String>(), spec.nextRequest(env) { it in granted })
        assertEquals(GrantLevel.Full, spec.evaluate(env) { it in granted })
        // 백그라운드가 없는 API 는 한 번에 끝난다
        assertEquals(emptyList<String>(), spec.nextRequest(PermissionEnvironment.of(32)) { it == p("BODY_SENSORS") })
    }

    @Test
    fun `신체 센서 측정 항목을 하나도 고르지 않으면 IllegalArgumentException`() {
        val e = assertThrows(IllegalArgumentException::class.java) { PermissionSpecs.bodySensors(heartRate = false) }
        assertTrue(e.message!!.contains("heartRate=false"))
    }

    @Test
    fun `신체 센서 백그라운드 요청은 다른 권한과 다른 요청으로 나간다`() {
        val device = FakeDevice().apply { grant(p("BODY_SENSORS")) }
        val registry = FakeRegistry()
        val declared = ALL_DECLARED + setOf(p("BODY_SENSORS"), p("BODY_SENSORS_BACKGROUND"))
        val m = fakeManager(app, device, sdkInt = 34, declared = declared)
        val r = m.createRequester(registry, "b", { null }, listOf(PermissionSpecs.camera, PermissionSpecs.bodySensors(background = true))) {}
        r.launch()
        assertEquals(listOf(p("CAMERA")), registry.lastPermissions)
        registry.respondPermissions(mapOf(p("CAMERA") to true))
        assertEquals(listOf(p("BODY_SENSORS_BACKGROUND")), registry.lastPermissions)
    }

    @Test
    fun `새 명세의 id 는 서로와 기존 명세와 겹치지 않는다`() {
        val specs = listOf(
            PermissionSpecs.audio, PermissionSpecs.storage(), PermissionSpecs.storage(true), PermissionSpecs.mediaLocation,
            PermissionSpecs.phoneState, PermissionSpecs.phoneNumbers, PermissionSpecs.callPhone, PermissionSpecs.answerPhoneCalls,
            PermissionSpecs.callLog(), PermissionSpecs.processOutgoingCalls, PermissionSpecs.addVoicemail, PermissionSpecs.useSip,
            PermissionSpecs.acceptHandover, PermissionSpecs.sms(), PermissionSpecs.accounts, PermissionSpecs.bodySensors(),
            PermissionSpecs.nearbyWifiDevices(), PermissionSpecs.uwbRanging, PermissionSpecs.ranging,
            PermissionSpecs.camera, PermissionSpecs.microphone, PermissionSpecs.media(), PermissionSpecs.contacts(),
            PermissionSpecs.allFilesAccess, PermissionSpecs.usageStats, PermissionSpecs.notificationListener,
            PermissionSpecs.accessibilityService, PermissionSpecs.notificationPolicy, PermissionSpecs.writeSettings,
            PermissionSpecs.fullScreenIntent, PermissionSpecs.manageMedia,
        )
        assertEquals(specs.size, specs.map { it.id }.toSet().size)
    }

    @Test
    fun `새 런타임 명세는 매니페스트 미선언이면 디버그 빌드에서 IllegalStateException`() {
        val registry = FakeRegistry()
        val m = fakeManager(app, FakeDevice(), sdkInt = 34, declared = emptySet())
        val r = m.createRequester(registry, "s", { null }, listOf(PermissionSpecs.sms(read = true))) {}
        val e = assertThrows(IllegalStateException::class.java) { r.launch() }
        assertTrue(e.message!!.contains("android.permission.READ_SMS"))
        assertTrue(registry.launches.isEmpty())
    }
}
