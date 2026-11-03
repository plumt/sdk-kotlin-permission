@file:SuppressLint("InlinedApi")
// 권한 이름 상수는 문자열이라 낮은 API 에서도 안전하다 — 실제 요청 여부는 각 명세가 API 레벨로 가른다

package net.plumt.permission

import android.Manifest
import android.annotation.SuppressLint
import net.plumt.permission.internal.Api
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
}
