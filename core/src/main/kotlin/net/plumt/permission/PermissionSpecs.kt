@file:SuppressLint("InlinedApi")
// 권한 이름 상수는 문자열이라 낮은 API 에서도 안전하다 — 실제 요청 여부는 각 명세가 API 레벨로 가른다

package net.plumt.permission

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import net.plumt.permission.internal.AccessibilityServiceSpec
import net.plumt.permission.internal.AutoRevokeExemptSpec
import net.plumt.permission.internal.CrossProfileSpec
import net.plumt.permission.internal.DeviceAdminSpec
import net.plumt.permission.internal.HealthConnectSpec
import net.plumt.permission.internal.PictureInPictureSpec
import net.plumt.permission.internal.RoleSpec
import net.plumt.permission.internal.ScreenCaptureSpec
import net.plumt.permission.internal.TurnScreenOnSpec
import net.plumt.permission.internal.UserInitiatedJobsSpec
import net.plumt.permission.internal.VpnSpec
import net.plumt.permission.internal.AllFilesAccessSpec
import net.plumt.permission.internal.Api
import net.plumt.permission.internal.AudioSpec
import net.plumt.permission.internal.BodySensorsSpec
import net.plumt.permission.internal.FullScreenIntentSpec
import net.plumt.permission.internal.GroupSpec
import net.plumt.permission.internal.ManageMediaSpec
import net.plumt.permission.internal.NearbyWifiSpec
import net.plumt.permission.internal.NotificationListenerSpec
import net.plumt.permission.internal.NotificationPolicySpec
import net.plumt.permission.internal.PhoneNumbersSpec
import net.plumt.permission.internal.StorageSpec
import net.plumt.permission.internal.UsageStatsSpec
import net.plumt.permission.internal.WriteSettingsSpec
import net.plumt.permission.internal.BackgroundLocationSpec
import net.plumt.permission.internal.BluetoothSpec
import net.plumt.permission.internal.ExactAlarmSpec
import net.plumt.permission.internal.InstallUnknownAppsSpec
import net.plumt.permission.internal.OverlaySpec
import net.plumt.permission.internal.IgnoreBatteryOptimizationsSpec
import net.plumt.permission.internal.LocationSpec
import net.plumt.permission.internal.MediaSpec
import net.plumt.permission.internal.ReadWriteSpec
import net.plumt.permission.internal.SinglePermissionSpec

/**
 * 내장 권한 명세. 같은 인자로 만든 명세는 서로 같다(`equals`).
 *
 * 앱 매니페스트에는 명세가 요청하는 권한을 앱이 직접 선언한다 — 이 라이브러리는 권한을 선언하지 않는다.
 * 각 함수의 문서에 선언할 권한을 적었다.
 */
public object PermissionSpecs {

    /** 카메라. 선언: `CAMERA` */
    public val camera: RuntimePermissionSpec = SinglePermissionSpec("camera", Manifest.permission.CAMERA)

    /** 마이크. 선언: `RECORD_AUDIO` */
    public val microphone: RuntimePermissionSpec = SinglePermissionSpec("microphone", Manifest.permission.RECORD_AUDIO)

    /**
     * 알림. API 33 이상에서만 런타임 권한이고 그 아래에서는 [PermissionStatus.NotApplicable] 이다.
     * 선언: `POST_NOTIFICATIONS`
     *
     * API 33 미만에서도 사용자는 설정에서 앱 알림을 끌 수 있다. 그 여부는 `NotificationManagerCompat.areNotificationsEnabled()` 로 따로 본다.
     */
    public val notifications: RuntimePermissionSpec =
        SinglePermissionSpec("notifications", Manifest.permission.POST_NOTIFICATIONS, minSdk = Api.TIRAMISU)

    /**
     * 활동 인식(걸음 수 등). API 29 이상에서만 런타임 권한이고 그 아래에서는 [PermissionStatus.NotApplicable] 이다.
     * 선언: `ACTIVITY_RECOGNITION`
     */
    public val activityRecognition: RuntimePermissionSpec =
        SinglePermissionSpec("activity_recognition", Manifest.permission.ACTIVITY_RECOGNITION, minSdk = Api.Q)

    /**
     * 포그라운드 위치.
     *
     * @param precise 참이면 정밀 위치를 원한다 — FINE·COARSE 를 함께 요청하고, 사용자가 대략적 위치만 고르면
     *   [PermissionStatus.Partial] 이다. 거짓이면 COARSE 만 요청하고 그것으로 [PermissionStatus.Granted] 다.
     *   선언: `ACCESS_COARSE_LOCATION`, 정밀이면 `ACCESS_FINE_LOCATION` 도
     */
    public fun location(precise: Boolean = true): RuntimePermissionSpec = LocationSpec(precise)

    /**
     * 백그라운드 위치(포그라운드 위치를 포함한다).
     *
     * API 30 이상에서는 두 단계다 — 첫 요청이 포그라운드 대화상자를 띄우고, 상태가 [PermissionStatus.Partial] 이
     * 된 뒤의 두 번째 요청이 "항상 허용" 설정 화면을 연다. 단계 사이에 근거 안내를 보이는 것이 플랫폼 권장이다.
     * API 29 는 한 번에, 그 아래는 포그라운드 위치만 요청한다.
     * 선언: [location] 의 권한 + `ACCESS_BACKGROUND_LOCATION`
     */
    public fun backgroundLocation(precise: Boolean = true): RuntimePermissionSpec = BackgroundLocationSpec(precise)

    /**
     * 사진·동영상 읽기.
     *
     * API 34 이상에서 앱이 `READ_MEDIA_VISUAL_USER_SELECTED` 를 선언했으면 그것도 함께 요청하고, 사용자가
     * 일부 사진만 고르면 [PermissionStatus.Partial] 이다. 선언하지 않았으면 시스템 호환 동작을 따른다.
     * 선언: `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`(고른 것), `READ_EXTERNAL_STORAGE`(`maxSdkVersion="32"`),
     * 선택으로 `READ_MEDIA_VISUAL_USER_SELECTED`
     *
     * @throws IllegalArgumentException [images] 와 [video] 가 모두 거짓일 때
     */
    public fun media(images: Boolean = true, video: Boolean = false): RuntimePermissionSpec {
        require(images || video) { "images·video 가운데 하나는 참이어야 합니다: images=$images, video=$video" }
        return MediaSpec(images, video)
    }

    /**
     * 연락처. 선언: 고른 것에 따라 `READ_CONTACTS`·`WRITE_CONTACTS`
     *
     * @throws IllegalArgumentException [read] 와 [write] 가 모두 거짓일 때
     */
    public fun contacts(read: Boolean = true, write: Boolean = false): RuntimePermissionSpec {
        require(read || write) { "read·write 가운데 하나는 참이어야 합니다: read=$read, write=$write" }
        return ReadWriteSpec(
            id = "contacts." + listOfNotNull("read".takeIf { read }, "write".takeIf { write }).joinToString("+"),
            read = Manifest.permission.READ_CONTACTS.takeIf { read },
            write = Manifest.permission.WRITE_CONTACTS.takeIf { write },
        )
    }

    /**
     * 캘린더. 선언: 고른 것에 따라 `READ_CALENDAR`·`WRITE_CALENDAR`
     *
     * @throws IllegalArgumentException [read] 와 [write] 가 모두 거짓일 때
     */
    public fun calendar(read: Boolean = true, write: Boolean = false): RuntimePermissionSpec {
        require(read || write) { "read·write 가운데 하나는 참이어야 합니다: read=$read, write=$write" }
        return ReadWriteSpec(
            id = "calendar." + listOfNotNull("read".takeIf { read }, "write".takeIf { write }).joinToString("+"),
            read = Manifest.permission.READ_CALENDAR.takeIf { read },
            write = Manifest.permission.WRITE_CALENDAR.takeIf { write },
        )
    }

    /**
     * 블루투스.
     *
     * API 31 이상은 고른 `BLUETOOTH_SCAN`·`BLUETOOTH_CONNECT`·`BLUETOOTH_ADVERTISE` 를 요청한다.
     * API 30 이하는 연결·광고에 런타임 권한이 없고(설치 시 부여되는 `BLUETOOTH`·`BLUETOOTH_ADMIN` 을
     * `maxSdkVersion="30"` 으로 선언한다), 검색에는 위치 권한(API 29~30 FINE, 그 아래 COARSE)을 요청한다.
     * 요청할 것이 없으면 [PermissionStatus.NotApplicable] 이다.
     *
     * @throws IllegalArgumentException 셋이 모두 거짓일 때
     */
    public fun bluetooth(scan: Boolean = false, connect: Boolean = true, advertise: Boolean = false): RuntimePermissionSpec {
        require(scan || connect || advertise) {
            "scan·connect·advertise 가운데 하나는 참이어야 합니다: scan=$scan, connect=$connect, advertise=$advertise"
        }
        return BluetoothSpec(scan, connect, advertise)
    }

    /**
     * 배터리 최적화 예외(특수 권한). 요청하면 시스템의 예외 요청 화면이 열린다.
     * 선언: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — Google Play 정책상 허용 용도가 제한된다
     */
    public val ignoreBatteryOptimizations: SpecialPermissionSpec = IgnoreBatteryOptimizationsSpec

    /**
     * 정확한 알람 예약(특수 권한, API 31+). 요청하면 "알람 및 리마인더" 설정 화면이 열린다.
     * API 31 미만은 [PermissionStatus.NotApplicable] 이다.
     * 선언: `SCHEDULE_EXACT_ALARM`. 알람·캘린더가 핵심 기능인 앱은 대신 `USE_EXACT_ALARM`(API 33+, 설치 시 부여)을
     * 함께 선언할 수 있고, 그러면 늘 허용으로 판정된다 — Play 정책상 그 용도의 앱만 쓸 수 있다
     */
    public val exactAlarm: SpecialPermissionSpec = ExactAlarmSpec

    /**
     * 다른 앱 위에 표시(특수 권한). 요청하면 "다른 앱 위에 표시" 설정 화면이 열린다.
     * 선언: `SYSTEM_ALERT_WINDOW`
     */
    public val overlay: SpecialPermissionSpec = OverlaySpec

    /**
     * 출처를 알 수 없는 앱 설치(특수 권한, API 26+). 요청하면 이 앱의 "알 수 없는 앱 설치" 설정 화면이 열린다.
     * API 26 미만은 기기 전체 설정이라 [PermissionStatus.NotApplicable] 이다.
     * 선언: `REQUEST_INSTALL_PACKAGES` — Google Play 정책상 허용 용도가 제한된다
     */
    public val installUnknownApps: SpecialPermissionSpec = InstallUnknownAppsSpec

    // ---- 저장소·매체

    /**
     * 오디오 파일 읽기. API 33 이상 `READ_MEDIA_AUDIO`, 그 아래 `READ_EXTERNAL_STORAGE`.
     * 선언: `READ_MEDIA_AUDIO`, `READ_EXTERNAL_STORAGE`(`maxSdkVersion="32"`)
     */
    public val audio: RuntimePermissionSpec = AudioSpec

    /**
     * 공유 저장소 읽기(·쓰기). API 32 이하 `READ_EXTERNAL_STORAGE`, [write] 면 API 29 이하에서 `WRITE_EXTERNAL_STORAGE` 도.
     * API 33 이상은 [PermissionStatus.NotApplicable] — 매체는 [media]·[audio], 그 밖의 파일은 [allFilesAccess] 나 시스템 파일 선택기를 쓴다.
     * 선언: `READ_EXTERNAL_STORAGE`(`maxSdkVersion="32"`), 쓰기면 `WRITE_EXTERNAL_STORAGE`(`maxSdkVersion="29"`)
     */
    public fun storage(write: Boolean = false): RuntimePermissionSpec = StorageSpec(write)

    /**
     * 사진 위치 정보(EXIF) 읽기. API 29 이상만 런타임 권한이고 그 아래는 [PermissionStatus.NotApplicable](제약 없음).
     * 매체 읽기 권한과 함께 쓴다. 선언: `ACCESS_MEDIA_LOCATION`
     */
    public val mediaLocation: RuntimePermissionSpec =
        SinglePermissionSpec("media.location", Manifest.permission.ACCESS_MEDIA_LOCATION, minSdk = Api.Q)

    // ---- 전화·통화 기록·SMS (Google Play 정책상 SMS·통화 기록은 기본 앱 등 허용 용도만 쓸 수 있다)

    /** 전화 상태 읽기. 선언: `READ_PHONE_STATE` */
    public val phoneState: RuntimePermissionSpec = SinglePermissionSpec("phone.state", Manifest.permission.READ_PHONE_STATE)

    /**
     * 전화번호 읽기. API 26 이상 `READ_PHONE_NUMBERS`, 그 아래는 그 권한이 없어 `READ_PHONE_STATE` 를 요청한다.
     * 선언: `READ_PHONE_NUMBERS`, `READ_PHONE_STATE`(`maxSdkVersion="25"`)
     */
    public val phoneNumbers: RuntimePermissionSpec = PhoneNumbersSpec

    /** 전화 걸기. 선언: `CALL_PHONE` */
    public val callPhone: RuntimePermissionSpec = SinglePermissionSpec("phone.call", Manifest.permission.CALL_PHONE)

    /**
     * 전화 받기(`TelecomManager.acceptRingingCall`). API 26 이상만 있고 그 아래는 [PermissionStatus.NotApplicable] —
     * 그 기기에는 앱이 전화를 받는 공개 API 가 없다. 선언: `ANSWER_PHONE_CALLS`
     */
    public val answerPhoneCalls: RuntimePermissionSpec =
        SinglePermissionSpec("phone.answer", Manifest.permission.ANSWER_PHONE_CALLS, minSdk = Api.O)

    /**
     * 통화 기록. 선언: 고른 것에 따라 `READ_CALL_LOG`·`WRITE_CALL_LOG`.
     * Google Play 정책상 기본 전화 앱 등 허용 용도만 쓸 수 있다
     *
     * @throws IllegalArgumentException [read] 와 [write] 가 모두 거짓일 때
     */
    public fun callLog(read: Boolean = true, write: Boolean = false): RuntimePermissionSpec {
        require(read || write) { "read·write 가운데 하나는 참이어야 합니다: read=$read, write=$write" }
        return ReadWriteSpec(
            id = "call_log." + listOfNotNull("read".takeIf { read }, "write".takeIf { write }).joinToString("+"),
            read = Manifest.permission.READ_CALL_LOG.takeIf { read },
            write = Manifest.permission.WRITE_CALL_LOG.takeIf { write },
        )
    }

    /**
     * 발신 전화 가로채기. API 29 에서 폐기됐다(대신 `CallRedirectionService`). 선언: `PROCESS_OUTGOING_CALLS`
     */
    public val processOutgoingCalls: RuntimePermissionSpec =
        SinglePermissionSpec("phone.process_outgoing_calls", @Suppress("DEPRECATION") Manifest.permission.PROCESS_OUTGOING_CALLS)

    /** 음성사서함 추가. 선언: `com.android.voicemail.permission.ADD_VOICEMAIL` */
    public val addVoicemail: RuntimePermissionSpec = SinglePermissionSpec("phone.add_voicemail", Manifest.permission.ADD_VOICEMAIL)

    /** SIP 통화. API 34 에서 폐기됐다. 선언: `USE_SIP` */
    public val useSip: RuntimePermissionSpec = SinglePermissionSpec("phone.use_sip", Manifest.permission.USE_SIP)

    /**
     * 다른 앱에서 시작된 통화 이어받기. API 28 이상만 있고 그 아래는 [PermissionStatus.NotApplicable](기능 없음).
     * 선언: `ACCEPT_HANDOVER`
     */
    public val acceptHandover: RuntimePermissionSpec =
        SinglePermissionSpec("phone.accept_handover", Manifest.permission.ACCEPT_HANDOVER, minSdk = Api.P)

    /**
     * SMS·MMS. 고른 권한을 함께 요청한다. Google Play 정책상 기본 SMS 앱 등 허용 용도만 쓸 수 있다.
     * 선언: 고른 것에 따라 `SEND_SMS`·`RECEIVE_SMS`·`READ_SMS`·`RECEIVE_MMS`·`RECEIVE_WAP_PUSH`
     *
     * @throws IllegalArgumentException 모두 거짓일 때
     */
    public fun sms(
        send: Boolean = true,
        receive: Boolean = false,
        read: Boolean = false,
        receiveMms: Boolean = false,
        receiveWapPush: Boolean = false,
    ): RuntimePermissionSpec {
        val chosen = listOfNotNull(
            ("send" to Manifest.permission.SEND_SMS).takeIf { send },
            ("receive" to Manifest.permission.RECEIVE_SMS).takeIf { receive },
            ("read" to Manifest.permission.READ_SMS).takeIf { read },
            ("receive_mms" to Manifest.permission.RECEIVE_MMS).takeIf { receiveMms },
            ("receive_wap_push" to Manifest.permission.RECEIVE_WAP_PUSH).takeIf { receiveWapPush },
        )
        require(chosen.isNotEmpty()) {
            "send·receive·read·receiveMms·receiveWapPush 가운데 하나는 참이어야 합니다: send=$send, receive=$receive, " +
                "read=$read, receiveMms=$receiveMms, receiveWapPush=$receiveWapPush"
        }
        return GroupSpec("sms." + chosen.joinToString("+") { it.first }, chosen.map { it.second })
    }

    /** 기기의 계정 목록. API 26 이상은 이 권한 없이도 자기 앱 계정은 보인다. 선언: `GET_ACCOUNTS` */
    public val accounts: RuntimePermissionSpec = SinglePermissionSpec("accounts", Manifest.permission.GET_ACCOUNTS)

    // ---- 센서·주변 기기

    /**
     * 신체 센서(심박·산소포화도·피부 온도).
     *
     * API 36 이상(targetSdk 36 이상)은 `BODY_SENSORS` 대신 고른 건강 권한(`android.permission.health.READ_HEART_RATE`·
     * `READ_OXYGEN_SATURATION`·`READ_SKIN_TEMPERATURE`)을 요청하고, 그 아래는 고른 것과 관계없이 `BODY_SENSORS` 하나다.
     * [background] 면 백그라운드 권한(API 33~35 `BODY_SENSORS_BACKGROUND`, 36+ `READ_HEALTH_DATA_IN_BACKGROUND`)을
     * 포그라운드 권한을 받은 뒤 따로 요청한다 — 그때까지 상태는 [PermissionStatus.Partial] 이다. API 32 이하에는 백그라운드 권한이 없다.
     * 선언: `BODY_SENSORS` + 고른 건강 권한(+ 백그라운드 권한)
     *
     * @throws IllegalArgumentException 측정 항목을 하나도 고르지 않았을 때
     */
    public fun bodySensors(
        background: Boolean = false,
        heartRate: Boolean = true,
        oxygenSaturation: Boolean = false,
        skinTemperature: Boolean = false,
    ): RuntimePermissionSpec {
        require(heartRate || oxygenSaturation || skinTemperature) {
            "heartRate·oxygenSaturation·skinTemperature 가운데 하나는 참이어야 합니다: heartRate=$heartRate, " +
                "oxygenSaturation=$oxygenSaturation, skinTemperature=$skinTemperature"
        }
        return BodySensorsSpec(background, heartRate, oxygenSaturation, skinTemperature)
    }

    /**
     * 주변 Wi-Fi 기기(Wi-Fi Direct·Aware·검색). API 33 이상 `NEARBY_WIFI_DEVICES`.
     * 그 아래에는 이 권한이 없다 — [legacyLocation] 이면 Wi-Fi 검색 결과에 필요한 위치 권한(API 29~32 FINE, 그 아래 COARSE)을
     * 요청하고, 거짓이면 [PermissionStatus.NotApplicable] 이다.
     * 선언: `NEARBY_WIFI_DEVICES`(위치를 얻지 않으면 `usesPermissionFlags="neverForLocation"`), 옛 기기용 위치 권한(`maxSdkVersion="32"`)
     */
    public fun nearbyWifiDevices(legacyLocation: Boolean = true): RuntimePermissionSpec = NearbyWifiSpec(legacyLocation)

    /**
     * UWB 거리 측정. API 31 이상만 있고 그 아래는 [PermissionStatus.NotApplicable](기능 없음 —
     * `SystemRequirements.hardware(FEATURE_UWB, minSdk = 31)` 로 함께 확인한다). 선언: `UWB_RANGING`
     */
    public val uwbRanging: RuntimePermissionSpec =
        SinglePermissionSpec("uwb_ranging", Manifest.permission.UWB_RANGING, minSdk = Api.S)

    /**
     * 거리 측정 통합 권한(UWB·블루투스 채널 사운딩·Wi-Fi RTT, API 36+). 그 아래는 [PermissionStatus.NotApplicable] —
     * 기술별 권한([uwbRanging]·위치 등)을 쓴다. 선언: `RANGING`
     */
    public val ranging: RuntimePermissionSpec = SinglePermissionSpec("ranging", Manifest.permission.RANGING, minSdk = Api.BAKLAVA)

    // ---- 특수 권한

    /**
     * 전체 파일 접근(특수 권한, API 30+). 요청하면 이 앱의 "모든 파일 액세스" 설정 화면이 열린다.
     * API 30 미만은 [PermissionStatus.NotApplicable] — 그 기기에서는 `storage(write = true)` 가 같은 일을 한다.
     * 선언: `MANAGE_EXTERNAL_STORAGE` — **Google Play 정책상 파일 관리자 등 허용 용도만, 신고서가 필요하다**
     */
    public val allFilesAccess: SpecialPermissionSpec = AllFilesAccessSpec

    /**
     * 사용 정보 접근(특수 권한). 요청하면 "사용 정보 접근" 설정 화면이 열린다(API 29+ 이 앱 항목).
     * 선언: `PACKAGE_USAGE_STATS`(`tools:ignore="ProtectedPermissions"`)
     */
    public val usageStats: SpecialPermissionSpec = UsageStatsSpec

    /**
     * 알림 접근(특수 권한). 앱이 `NotificationListenerService` 를 `BIND_NOTIFICATION_LISTENER_SERVICE` 로 보호해 선언해야 한다.
     * 요청 직전 선언 확인이 서비스가 없으면 알린다.
     * API 30 이상은 그 서비스의 상세 설정 화면을 바로 연다.
     */
    public val notificationListener: SpecialPermissionSpec = NotificationListenerSpec

    /**
     * 접근성 서비스(특수 권한). 앱이 `AccessibilityService` 를 `BIND_ACCESSIBILITY_SERVICE` 로 보호해 선언해야 한다.
     * 요청하면 접근성 설정 화면이 열린다. **Google Play 정책상 접근성 API 사용 신고가 필요하다**
     */
    public val accessibilityService: SpecialPermissionSpec = AccessibilityServiceSpec

    /**
     * 방해 금지 정책 접근(특수 권한). 요청하면 "방해 금지 액세스" 설정 화면이 열린다.
     * 선언: `ACCESS_NOTIFICATION_POLICY`
     */
    public val notificationPolicy: SpecialPermissionSpec = NotificationPolicySpec

    /**
     * 시스템 설정 쓰기(특수 권한). 요청하면 이 앱의 "시스템 설정 수정" 화면이 열린다.
     * 선언: `WRITE_SETTINGS`(`tools:ignore="ProtectedPermissions"`)
     */
    public val writeSettings: SpecialPermissionSpec = WriteSettingsSpec

    /**
     * 전체 화면 인텐트(특수 권한, API 34+). 요청하면 이 앱의 "전체 화면 알림" 설정 화면이 열린다.
     * API 34 미만은 설치 시 부여돼 [PermissionStatus.NotApplicable] 이다.
     * 선언: `USE_FULL_SCREEN_INTENT` — **Google Play 정책상 전화·알람 앱만 기본 부여된다**
     */
    public val fullScreenIntent: SpecialPermissionSpec = FullScreenIntentSpec

    /**
     * 미디어 관리(특수 권한, API 31+). 확인 대화상자 없이 매체를 고치고 지운다. 요청하면 이 앱의 "미디어 관리" 화면이 열린다.
     * API 31 미만은 [PermissionStatus.NotApplicable]. 선언: `MANAGE_MEDIA`
     */
    public val manageMedia: SpecialPermissionSpec = ManageMediaSpec

    // ---- 앱 접근(설정 → 특수 앱 접근·기본 앱)

    /**
     * 미사용 앱 권한 자동 회수·휴면 예외(특수 권한, API 30+). 허용이면 오래 쓰지 않아도 권한이 회수되지 않는다.
     * 요청하면 이 앱의 "앱 사용 중지 시 권한 삭제"(API 31+ "사용하지 않는 앱 활동 일시중지") 설정이 열린다.
     * API 30 미만은 [PermissionStatus.NotApplicable]. 선언: 없음
     */
    public val autoRevokeExempt: SpecialPermissionSpec = AutoRevokeExemptSpec

    /**
     * 기기 관리자(특수 권한). [admin] 은 앱이 선언한 `DeviceAdminReceiver` 이고, `BIND_DEVICE_ADMIN` 보호와
     * `android.app.device_admin` 메타데이터가 있어야 한다 — 요청 직전 선언 확인이 없으면 알린다.
     * 요청하면 시스템 활성화 화면이 열리고 [explanation] 을 함께 보인다. 선언: `<uses-permission>` 없음
     *
     * @throws IllegalArgumentException [admin] 의 클래스 이름이 비었거나 [explanation] 이 빈 문자열일 때
     */
    public fun deviceAdmin(admin: ComponentName, explanation: String? = null): SpecialPermissionSpec {
        require(admin.className.isNotBlank()) { "admin 의 클래스 이름이 비었습니다: $admin" }
        require(explanation == null || explanation.isNotEmpty()) { "explanation 이 빈 문자열입니다 — 없으면 null 을 넘기세요" }
        return DeviceAdminSpec(admin, explanation)
    }

    /**
     * VPN 연결 동의(특수 권한, `VpnService.prepare`). 앱이 `BIND_VPN_SERVICE` 로 보호한 `VpnService` 를 선언해야 한다.
     * 요청하면 시스템 동의 대화상자가 뜬다. 다른 VPN 앱이 동의를 가져가면 다시 거부가 된다. 선언: `<uses-permission>` 없음
     */
    public val vpn: SpecialPermissionSpec = VpnSpec

    /**
     * 화면 캡처 동의(MediaProjection). 요청하면 시스템 동의 대화상자가 뜨고, 사용자가 허용하면 [onConsent] 가
     * `resultCode`·`data` 를 받는다 — `MediaProjectionManager.getMediaProjection` 에 넘긴다. 동의는 세션마다 받고 저장되지 않아
     * 이 명세의 상태는 늘 [PermissionStatus.Denied] 다. 캡처는 API 29 이상 `mediaProjection` 형식의 포그라운드 서비스 안에서 시작해야 한다
     * (선언: `FOREGROUND_SERVICE`, API 34+ `FOREGROUND_SERVICE_MEDIA_PROJECTION` — 서비스의 몫이라 lint 는 요구하지 않는다).
     */
    public fun screenCapture(onConsent: (resultCode: Int, data: Intent) -> Unit): SpecialPermissionSpec = ScreenCaptureSpec(onConsent)

    /**
     * Picture-in-Picture 허용(특수 권한, API 26+). 요청 API 가 없어 설정 화면으로 보낸다. 기본은 허용이고 사용자가 끄면 거부다.
     * 앱 Activity 에 `android:supportsPictureInPicture="true"` 가 있어야 하고, 기기 기능은 `hardware(FEATURE_PICTURE_IN_PICTURE)` 로 본다.
     * API 26 미만은 [PermissionStatus.NotApplicable]. 선언: 없음
     */
    public val pictureInPicture: SpecialPermissionSpec = PictureInPictureSpec

    /**
     * 기본 앱 역할(특수 권한). API 29 이상은 `RoleManager` 의 요청 대화상자, SMS·전화는 API 23~28 에 옛 기본 앱 변경 화면을 연다.
     * 그 밖의 역할은 API 29 미만에서 [PermissionStatus.NotApplicable] 이다. 선언: 없음 — 역할별 컴포넌트 요건은 [AppRole] 참고
     */
    public fun role(role: AppRole): SpecialPermissionSpec = RoleSpec(role)

    /**
     * Health Connect 권한(API 34+ 플랫폼). [read]·[write] 는 데이터 종류 이름(`"STEPS"`·`"HEART_RATE"`·`"HEALTH_DATA_IN_BACKGROUND"` 등)이고
     * 각각 `android.permission.health.READ_<종류>`·`WRITE_<종류>` 를 요청한다. API 34 미만은 Health Connect 가 별도 앱이라
     * [PermissionStatus.NotApplicable] 이다(Jetpack Health Connect 클라이언트의 몫).
     * 앱은 `VIEW_PERMISSION_USAGE` + `HEALTH_PERMISSIONS` 근거 화면을 선언해야 한다 — 요청 직전 선언 확인이 없으면 알린다.
     * 선언: 요청하는 `android.permission.health.*` 전부
     *
     * @throws IllegalArgumentException 둘 다 비었거나, 종류 이름이 `[A-Z][A-Z0-9_]*` 가 아닐 때
     */
    public fun healthConnect(read: Array<String> = emptyArray(), write: Array<String> = emptyArray()): RuntimePermissionSpec {
        require(read.isNotEmpty() || write.isNotEmpty()) { "read·write 가운데 하나에는 종류가 있어야 합니다" }
        for (type in read + write) {
            require(HealthConnectSpec.TYPE.matches(type)) {
                "Health Connect 데이터 종류는 [A-Z][A-Z0-9_]* 형식의 이름이어야 합니다(예: STEPS): \"$type\""
            }
        }
        return HealthConnectSpec(read.distinct(), write.distinct())
    }

    /**
     * 화면 켜기(특수 권한, API 36+ 앱 작업). `PowerManager.ACQUIRE_CAUSES_WAKEUP` 깨우기 잠금 등으로 화면을 켠다.
     * 공개 설정 화면이 없어 요청하면 앱 상세 설정 화면이 열린다. API 36 미만은 [PermissionStatus.NotApplicable]. 선언: `TURN_SCREEN_ON`
     */
    public val turnScreenOn: SpecialPermissionSpec = TurnScreenOnSpec

    /**
     * 오래 걸리는 사용자 시작 작업(특수 권한, API 34+, `JobInfo.Builder.setUserInitiated`). 요청하면 "오래 걸리는 백그라운드 작업" 설정이 열린다.
     * API 34 미만은 [PermissionStatus.NotApplicable]. 선언: `RUN_USER_INITIATED_JOBS`
     */
    public val userInitiatedJobs: SpecialPermissionSpec = UserInitiatedJobsSpec

    /**
     * 업무·개인 프로필 연결(특수 권한, API 30+). IT 관리자가 허용하고 두 프로필에 앱이 있을 때만 요청 화면이 열리고,
     * 아니면 앱 상세 설정 화면이 열린다. API 30 미만은 [PermissionStatus.NotApplicable]. 선언: `INTERACT_ACROSS_PROFILES`
     */
    public val crossProfile: SpecialPermissionSpec = CrossProfileSpec
}
