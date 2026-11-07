@file:SuppressLint("InlinedApi")
// 권한 이름 상수는 문자열이라 낮은 API 에서도 안전하다 — 실제 요청 여부는 각 명세가 API 레벨로 가른다

package net.plumt.permission.internal

import android.Manifest
import android.annotation.SuppressLint
import net.plumt.permission.PermissionEnvironment
import net.plumt.permission.RuntimePermissionSpec

/** 권한 여럿을 모두 요구하는 명세. [minSdk] 미만(앱 targetSdk 와 기기 API 가운데 낮은 쪽)이면 요청할 것이 없다 */
internal data class GroupSpec(
    override val id: String,
    private val group: List<String>,
    private val minSdk: Int = 1,
) : RuntimePermissionSpec {
    override fun permissions(environment: PermissionEnvironment): List<String> =
        if (environment.effectiveSdk >= minSdk) group else emptyList()
}

/**
 * 전화번호 읽기. API 26 이상은 `READ_PHONE_NUMBERS`, 그 아래는 그 권한이 없어 `READ_PHONE_STATE` 가 번호 읽기를 맡는다.
 */
internal object PhoneNumbersSpec : RuntimePermissionSpec {
    override val id: String = "phone.numbers"

    override fun permissions(environment: PermissionEnvironment): List<String> =
        if (environment.effectiveSdk >= Api.O) {
            listOf(Manifest.permission.READ_PHONE_NUMBERS)
        } else {
            listOf(Manifest.permission.READ_PHONE_STATE)
        }

    override fun toString(): String = "PhoneNumbersSpec"
}

/**
 * 외부 저장소(공유 저장소) 읽기·쓰기.
 *
 * - API 32 이하: `READ_EXTERNAL_STORAGE`
 * - 쓰기는 API 29 이하에서만 `WRITE_EXTERNAL_STORAGE` 를 더한다 — API 30 이상에서는 쓰기 권한이 아무것도 주지 않는다
 * - API 33 이상: 요청할 것이 없다 — 매체는 [MediaSpec]·[AudioSpec], 그 밖의 파일은 전체 파일 접근(특수 권한)이나 시스템 파일 선택기
 */
internal data class StorageSpec(private val write: Boolean) : RuntimePermissionSpec {
    override val id: String = if (write) "storage.read+write" else "storage.read"

    override fun permissions(environment: PermissionEnvironment): List<String> {
        val sdk = environment.effectiveSdk
        return when {
            sdk >= Api.TIRAMISU -> emptyList()
            write && sdk <= Api.Q -> listOf(MediaSpec.LEGACY, WRITE)
            else -> listOf(MediaSpec.LEGACY)
        }
    }

    companion object {
        const val WRITE = Manifest.permission.WRITE_EXTERNAL_STORAGE
    }
}

/** 오디오 파일 읽기. API 33 이상 `READ_MEDIA_AUDIO`, 그 아래 `READ_EXTERNAL_STORAGE` */
internal object AudioSpec : RuntimePermissionSpec {
    override val id: String = "media.audio"

    override fun permissions(environment: PermissionEnvironment): List<String> =
        if (environment.effectiveSdk >= Api.TIRAMISU) listOf(AUDIO) else listOf(MediaSpec.LEGACY)

    override fun toString(): String = "AudioSpec"

    const val AUDIO = Manifest.permission.READ_MEDIA_AUDIO
}

/**
 * 주변 Wi-Fi 기기. API 33 이상 `NEARBY_WIFI_DEVICES`. 그 아래에는 이 권한이 없고 Wi-Fi 검색 결과에 위치 권한이 필요해,
 * [legacyLocation] 이면 위치 권한(API 29~32 FINE, 그 아래 COARSE)을 대신 요청한다.
 */
internal data class NearbyWifiSpec(private val legacyLocation: Boolean) : RuntimePermissionSpec {
    override val id: String = if (legacyLocation) "nearby_wifi" else "nearby_wifi.no_legacy_location"

    override fun permissions(environment: PermissionEnvironment): List<String> {
        val sdk = environment.effectiveSdk
        return when {
            sdk >= Api.TIRAMISU -> listOf(NEARBY_WIFI)
            !legacyLocation -> emptyList()
            sdk >= Api.Q -> listOf(LocationSpec.FINE)
            else -> listOf(LocationSpec.COARSE)
        }
    }

    companion object {
        const val NEARBY_WIFI = Manifest.permission.NEARBY_WIFI_DEVICES
    }
}

/**
 * 신체 센서(심박 등).
 *
 * - API 36 이상(targetSdk 36 이상): `BODY_SENSORS` 가 세분된 건강 권한(`android.permission.health.READ_HEART_RATE` 등)으로
 *   바뀌었다. 고른 항목의 건강 권한을 요청하고, 백그라운드는 `READ_HEALTH_DATA_IN_BACKGROUND`
 * - API 33~35: `BODY_SENSORS`, 백그라운드는 `BODY_SENSORS_BACKGROUND`
 * - API 32 이하: `BODY_SENSORS` 하나 — 백그라운드 권한이 따로 없다
 *
 * 백그라운드 권한은 포그라운드 권한을 받은 뒤 따로 요청한다(백그라운드 위치와 같다).
 */
internal data class BodySensorsSpec(
    private val background: Boolean,
    private val heartRate: Boolean,
    private val oxygenSaturation: Boolean,
    private val skinTemperature: Boolean,
) : RuntimePermissionSpec {
    override val id: String = "body_sensors." + listOfNotNull(
        "heart_rate".takeIf { heartRate },
        "oxygen_saturation".takeIf { oxygenSaturation },
        "skin_temperature".takeIf { skinTemperature },
        "background".takeIf { background },
    ).joinToString("+")

    private fun foreground(environment: PermissionEnvironment): List<String> =
        if (environment.effectiveSdk >= Api.BAKLAVA) {
            listOfNotNull(
                HEART_RATE.takeIf { heartRate },
                OXYGEN_SATURATION.takeIf { oxygenSaturation },
                SKIN_TEMPERATURE.takeIf { skinTemperature },
            )
        } else {
            listOf(BODY_SENSORS)
        }

    private fun backgroundPermission(environment: PermissionEnvironment): String? = when {
        !background -> null
        environment.effectiveSdk >= Api.BAKLAVA -> HEALTH_BACKGROUND
        environment.effectiveSdk >= Api.TIRAMISU -> BODY_SENSORS_BACKGROUND
        else -> null
    }

    override fun permissions(environment: PermissionEnvironment): List<String> =
        foreground(environment) + listOfNotNull(backgroundPermission(environment))

    override fun nextRequest(environment: PermissionEnvironment, isGranted: (String) -> Boolean): List<String> {
        val fgMissing = foreground(environment).filterNot(isGranted)
        if (fgMissing.isNotEmpty()) return fgMissing
        return listOfNotNull(backgroundPermission(environment)).filterNot(isGranted)
    }

    companion object {
        const val BODY_SENSORS = Manifest.permission.BODY_SENSORS
        const val BODY_SENSORS_BACKGROUND = Manifest.permission.BODY_SENSORS_BACKGROUND
        const val HEART_RATE = "android.permission.health.READ_HEART_RATE"
        const val OXYGEN_SATURATION = "android.permission.health.READ_OXYGEN_SATURATION"
        const val SKIN_TEMPERATURE = "android.permission.health.READ_SKIN_TEMPERATURE"
        const val HEALTH_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

        /** 다른 권한과 한 요청에 묶지 않고 따로 요청하는 백그라운드 권한 */
        val SEPARATE_BACKGROUND: Set<String> = setOf(BODY_SENSORS_BACKGROUND, HEALTH_BACKGROUND)
    }
}
