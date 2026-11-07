package net.plumt.permission

import net.plumt.permission.internal.AirplaneModeOffRequirement
import net.plumt.permission.internal.AutoTimeRequirement
import net.plumt.permission.internal.BackgroundUnrestrictedRequirement
import net.plumt.permission.internal.BiometricRequirement
import net.plumt.permission.internal.BluetoothEnabledRequirement
import net.plumt.permission.internal.CompanionDeviceRequirement
import net.plumt.permission.internal.DataSaverRequirement
import net.plumt.permission.internal.DeviceSecureRequirement
import net.plumt.permission.internal.DoNotDisturbOffRequirement
import net.plumt.permission.internal.GpsRequirement
import net.plumt.permission.internal.InternetRequirement
import net.plumt.permission.internal.PowerSaveOffRequirement
import net.plumt.permission.internal.StandbyBucketRequirement
import net.plumt.permission.internal.HardwareRequirement
import net.plumt.permission.internal.LocationEnabledRequirement
import net.plumt.permission.internal.NfcEnabledRequirement
import net.plumt.permission.internal.NotificationsEnabledRequirement
import net.plumt.permission.internal.WifiEnabledRequirement

/**
 * 내장 시스템 기능 명세. 권한 명세와 함께 요청기에 넘기면 권한을 받은 뒤 꺼진 기능의 켜기 화면을 차례로 연다.
 */
public object SystemRequirements {

    /**
     * 블루투스 어댑터 켜짐. 꺼져 있으면 `BluetoothAdapter.ACTION_REQUEST_ENABLE` 대화상자를 띄운다.
     * API 31 이상은 켜기 전에 `BLUETOOTH_CONNECT` 를 요청한다. 어댑터가 없으면 `Unsupported(NO_HARDWARE)`.
     * 선언: API 31+ `BLUETOOTH_CONNECT`, API 30 이하 `BLUETOOTH`(`maxSdkVersion="30"`)
     */
    public val bluetoothEnabled: SystemRequirement = BluetoothEnabledRequirement

    /**
     * 위치 서비스 켜짐. 꺼져 있으면 시스템 위치 설정 화면을 연다(Play 서비스 불필요).
     * 위치 하드웨어가 없으면 `Unsupported(NO_HARDWARE)`. 선언: 없음
     */
    public val locationEnabled: SystemRequirement = LocationEnabledRequirement

    /** NFC 켜짐. 꺼져 있으면 NFC 패널(API 29+)이나 NFC 설정 화면을 연다. NFC 가 없으면 `Unsupported(NO_HARDWARE)`. 선언: 없음 */
    public val nfcEnabled: SystemRequirement = NfcEnabledRequirement

    /** Wi-Fi 켜짐. 꺼져 있으면 Wi-Fi 패널(API 29+)이나 Wi-Fi 설정 화면을 연다. 선언: `ACCESS_WIFI_STATE` */
    public val wifiEnabled: SystemRequirement = WifiEnabledRequirement

    /**
     * 이 앱의 알림 켜짐. API 33 미만에서도 사용자가 끌 수 있다. API 33 이상은 먼저 `POST_NOTIFICATIONS` 를 요청하고,
     * 꺼져 있으면 앱 알림 설정 화면을 연다. 선언: API 33+ `POST_NOTIFICATIONS`
     */
    public val notificationsEnabled: SystemRequirement = NotificationsEnabledRequirement

    /**
     * 하드웨어 기능이 있는지(`PackageManager.hasSystemFeature`). 없으면 `Unsupported(NO_HARDWARE)`,
     * 기기 API 가 [minSdk] 미만이면 `Unsupported(API_LEVEL)`. 켜기 요청은 없다.
     * 예: `hardware(PackageManager.FEATURE_CAMERA_ANY)`, `hardware(PackageManager.FEATURE_UWB, minSdk = 31)`
     *
     * @throws IllegalArgumentException [feature] 가 비었거나 [minSdk] 가 1 미만일 때
     */
    public fun hardware(feature: String, minSdk: Int = 1): SystemRequirement {
        require(feature.isNotBlank()) { "feature 가 비었습니다" }
        require(minSdk >= 1) { "minSdk 는 1 이상이어야 합니다: $minSdk" }
        return HardwareRequirement(feature, minSdk)
    }

    // ---- 기기 상태

    /** 비행기 모드 꺼짐. 켜져 있으면 비행기 모드 설정 화면을 연다. 선언: 없음 */
    public val airplaneModeOff: SystemRequirement = AirplaneModeOffRequirement

    /**
     * 기본 네트워크로 인터넷에 닿음(`NET_CAPABILITY_INTERNET` + `VALIDATED` — 연결만 되고 인증 전인 공용 Wi-Fi 는 아니다).
     * 끊겨 있으면 인터넷 연결 패널(API 29+)이나 무선 설정 화면을 연다. 선언: `ACCESS_NETWORK_STATE`
     */
    public val internetAvailable: SystemRequirement = InternetRequirement

    /**
     * 데이터 절약 모드가 이 앱의 백그라운드 데이터를 막지 않음(`RESTRICT_BACKGROUND_STATUS`, API 24+). 막고 있으면 이 앱의
     * "무제한 데이터 사용" 설정 화면을 연다. API 24 미만은 늘 Satisfied. 선언: 없음
     */
    public val dataSaverOff: SystemRequirement = DataSaverRequirement

    /** 절전 모드 꺼짐(`PowerManager.isPowerSaveMode`). 켜져 있으면 절전 모드 설정 화면을 연다. 선언: 없음 */
    public val powerSaveOff: SystemRequirement = PowerSaveOffRequirement

    /**
     * 사용자가 이 앱의 백그라운드 활동을 제한하지 않음(`ActivityManager.isBackgroundRestricted`, API 28+). 제한돼 있으면
     * 앱 상세 설정 화면(배터리 항목)을 연다. API 28 미만은 늘 Satisfied. 선언: 없음
     */
    public val backgroundUnrestricted: SystemRequirement = BackgroundUnrestrictedRequirement

    /**
     * 앱 대기 버킷이 [worstAllowed] 이하(API 28+, 숫자가 작을수록 활발). 조회만 한다 — 넘으면 [RequirementStatus.Unmet] 이고
     * 해결 화면은 없다. API 28 미만은 늘 Satisfied. 값은 `UsageStatsManager.STANDBY_BUCKET_ACTIVE(10)`·`WORKING_SET(20)`·
     * `FREQUENT(30)`·`RARE(40)`·`RESTRICTED(45)`. 기본은 `RARE` 까지(제한 버킷만 Unmet). 선언: 없음
     *
     * @throws IllegalArgumentException [worstAllowed] 가 위 값이 아닐 때
     */
    public fun standbyBucket(worstAllowed: Int = 40): SystemRequirement {
        require(worstAllowed in StandbyBucketRequirement.BUCKETS) {
            "worstAllowed 는 ${StandbyBucketRequirement.BUCKETS.sorted()} 가운데 하나여야 합니다: $worstAllowed"
        }
        return StandbyBucketRequirement(worstAllowed)
    }

    /** 방해 금지 꺼짐. 켜져 있으면 방해 금지 설정 화면을 연다. 선언: 없음 */
    public val doNotDisturbOff: SystemRequirement = DoNotDisturbOffRequirement

    /** 화면 잠금(PIN·패턴·비밀번호) 설정됨(`KeyguardManager.isDeviceSecure`). 없으면 화면 잠금 설정 화면을 연다. 선언: 없음 */
    public val deviceSecure: SystemRequirement = DeviceSecureRequirement

    /**
     * 생체 인증 등록됨(프레임워크 `BiometricManager`, API 29+ — androidx.biometric 에 의존하지 않는다). API 30 이상은 [strong] 이면
     * Class 3, 아니면 Class 2 이상을 묻고 API 29 는 강도를 고를 수 없다. 등록이 없으면 등록 화면(API 30+)이나 보안 설정 화면을 연다.
     * 하드웨어가 없으면 `Unsupported(NO_HARDWARE)`, 일시 불가·보안 업데이트 필요면 [RequirementStatus.Unmet],
     * API 29 미만은 `Unsupported(API_LEVEL)`. 선언: `USE_BIOMETRIC`
     */
    public fun biometricEnrolled(strong: Boolean = false): SystemRequirement = BiometricRequirement(strong)

    /**
     * GPS 공급자 켜짐 — 위치는 켜져 있지만 GPS 만 꺼진 경우를 가른다. GPS 가 없으면 `Unsupported(NO_HARDWARE)`.
     * 꺼져 있으면 위치 설정 화면을 연다. 선언: 없음
     */
    public val gpsEnabled: SystemRequirement = GpsRequirement

    /** 자동 시간 켜짐([includeTimeZone] 이면 자동 시간대도). 꺼져 있으면 날짜·시간 설정 화면을 연다. 선언: 없음 */
    public fun autoTimeEnabled(includeTimeZone: Boolean = false): SystemRequirement = AutoTimeRequirement(includeTimeZone)

    /**
     * 이 앱에 컴패니언 기기 연결이 있음(`CompanionDeviceManager`, API 26+). 연결은 앱이 `associate()` 로 만들고 시스템에 이 화면이 없어
     * 상태만 알린다 — 없으면 [RequirementStatus.Unmet]. 기능이 없으면 `Unsupported(NO_HARDWARE)`, API 26 미만은 `Unsupported(API_LEVEL)`.
     * 선언: 없음
     */
    public val companionDeviceAssociated: SystemRequirement = CompanionDeviceRequirement
}
