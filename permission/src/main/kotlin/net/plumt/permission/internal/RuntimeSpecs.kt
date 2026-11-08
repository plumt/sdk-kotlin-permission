@file:SuppressLint("InlinedApi")
// 권한 이름 상수는 문자열이라 낮은 API 에서도 안전하다 — 실제 요청 여부는 각 명세가 API 레벨로 가른다

package net.plumt.permission.internal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import net.plumt.permission.GrantLevel
import net.plumt.permission.PartialAccess
import net.plumt.permission.PermissionEnvironment
import net.plumt.permission.RuntimePermissionSpec

// 런타임 권한 명세의 구현 — 시스템 권한 대화상자(`requestPermissions`)로 묻는 것은 모두 이 파일에 둔다.
// 공개 팩토리와 문서는 `PermissionSpecs`, 매니페스트 선언은 `declaredPermissions` 가 정본이고 lint 표는 일치 테스트가 맞춘다.

/** 권한 하나만 다루는 단순 명세. [minSdk] 는 앱 targetSdk 와 기기 API 가운데 낮은 쪽과 견준다 */
internal data class SinglePermissionSpec(
    override val id: String,
    private val permission: String,
    private val minSdk: Int = 1,
) : RuntimePermissionSpec {
    override fun permissions(environment: PermissionEnvironment): List<String> =
        if (environment.effectiveSdk >= minSdk) listOf(permission) else emptyList()
}

/** 읽기·쓰기 짝 권한(연락처·캘린더). 둘 다 요청하면 둘 다 있어야 온전하다 */
internal data class ReadWriteSpec(
    override val id: String,
    private val read: String?,
    private val write: String?,
) : RuntimePermissionSpec {
    override fun permissions(environment: PermissionEnvironment): List<String> = listOfNotNull(read, write)
}

/**
 * 포그라운드 위치. 정밀 위치를 원할 때는 API 31 규칙대로 FINE·COARSE 를 함께 요청하고,
 * 사용자가 "대략적 위치" 만 고르면 [GrantLevel.Partial] 이다.
 */
internal data class LocationSpec(private val precise: Boolean) : RuntimePermissionSpec {
    override val id: String = if (precise) "location.precise" else "location.approximate"

    override fun permissions(environment: PermissionEnvironment): List<String> =
        if (precise) listOf(FINE, COARSE) else listOf(COARSE)

    override fun evaluate(environment: PermissionEnvironment, isGranted: (String) -> Boolean): GrantLevel =
        when {
            isGranted(FINE) -> GrantLevel.Full
            isGranted(COARSE) -> if (precise) GrantLevel.Partial else GrantLevel.Full
            else -> GrantLevel.None
        }

    // 대략 → 정밀 승격도 두 권한을 함께 다시 요청해야 시스템이 선택 대화상자를 띄운다
    override fun nextRequest(environment: PermissionEnvironment, isGranted: (String) -> Boolean): List<String> =
        if (evaluate(environment, isGranted) == GrantLevel.Full) emptyList() else permissions(environment)

    override fun partialAccess(environment: PermissionEnvironment, isGranted: (String) -> Boolean): PartialAccess =
        PartialAccess.APPROXIMATE_LOCATION

    companion object {
        const val FINE = Manifest.permission.ACCESS_FINE_LOCATION
        const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION
    }
}

/**
 * 백그라운드 위치.
 *
 * - API 29 미만: 백그라운드 권한이 따로 없다. 포그라운드 위치가 곧 백그라운드 위치다
 * - API 29: 포그라운드와 함께 한 번에 요청한다("항상 허용" 선택지가 대화상자에 있다)
 * - API 30 이상: 두 단계다. 포그라운드를 먼저 받고, 그다음 요청이 백그라운드 설정 화면을 연다.
 *   함께 요청하면 시스템이 요청 전체를 무시한다
 */
internal data class BackgroundLocationSpec(private val precise: Boolean) : RuntimePermissionSpec {
    private val foreground = LocationSpec(precise)

    override val id: String = if (precise) "location.background.precise" else "location.background.approximate"

    override fun permissions(environment: PermissionEnvironment): List<String> {
        val fg = foreground.permissions(environment)
        return if (environment.sdkInt >= Api.Q) fg + BACKGROUND else fg
    }

    override fun evaluate(environment: PermissionEnvironment, isGranted: (String) -> Boolean): GrantLevel {
        val fg = foreground.evaluate(environment, isGranted)
        if (environment.sdkInt < Api.Q) return fg
        return when {
            fg == GrantLevel.None -> GrantLevel.None
            fg == GrantLevel.Full && isGranted(BACKGROUND) -> GrantLevel.Full
            else -> GrantLevel.Partial
        }
    }

    override fun nextRequest(environment: PermissionEnvironment, isGranted: (String) -> Boolean): List<String> {
        if (evaluate(environment, isGranted) == GrantLevel.Full) return emptyList()
        val fgNext = foreground.nextRequest(environment, isGranted)
        return when {
            environment.sdkInt < Api.Q -> fgNext
            environment.sdkInt == Api.Q -> if (isGranted(BACKGROUND)) fgNext else fgNext + BACKGROUND
            // 대략적 위치만 있어도 백그라운드는 요청할 수 있다 — 정밀 승격보다 백그라운드를 먼저 묻지 않는다
            foreground.evaluate(environment, isGranted) == GrantLevel.None -> fgNext
            !isGranted(BACKGROUND) -> listOf(BACKGROUND)
            else -> fgNext
        }
    }

    // 백그라운드가 빠졌으면 다음 요청이 백그라운드라 그것을 먼저 알린다
    override fun partialAccess(environment: PermissionEnvironment, isGranted: (String) -> Boolean): PartialAccess =
        if (environment.sdkInt >= Api.Q && !isGranted(BACKGROUND)) {
            PartialAccess.FOREGROUND_LOCATION_ONLY
        } else {
            PartialAccess.APPROXIMATE_LOCATION
        }

    companion object {
        const val BACKGROUND = Manifest.permission.ACCESS_BACKGROUND_LOCATION
    }
}

/**
 * 사진·동영상.
 *
 * - API 34 이상: READ_MEDIA_IMAGES/VIDEO, 그리고 앱이 READ_MEDIA_VISUAL_USER_SELECTED 를 선언했으면 그것도 함께
 *   요청한다. 사용자가 "일부 사진 선택" 을 고르면 그것만 허용돼 [GrantLevel.Partial] 이다
 * - API 33: READ_MEDIA_IMAGES/VIDEO
 * - API 32 이하: READ_EXTERNAL_STORAGE
 *
 * API 레벨은 앱 targetSdk 와 기기 API 가운데 낮은 쪽이다 — targetSdk 32 앱은 API 33 기기에서도 READ_EXTERNAL_STORAGE 를 쓴다.
 */
internal data class MediaSpec(private val images: Boolean, private val video: Boolean) : RuntimePermissionSpec {
    override val id: String = "media." + listOfNotNull("images".takeIf { images }, "video".takeIf { video }).joinToString("+")

    private fun mediaPermissions(): List<String> =
        listOfNotNull(IMAGES.takeIf { images }, VIDEO.takeIf { video })

    override fun permissions(environment: PermissionEnvironment): List<String> = when {
        environment.effectiveSdk >= Api.UPSIDE_DOWN_CAKE ->
            mediaPermissions() + listOfNotNull(USER_SELECTED.takeIf { environment.isDeclared(it) })
        environment.effectiveSdk >= Api.TIRAMISU -> mediaPermissions()
        else -> listOf(LEGACY)
    }

    override fun evaluate(environment: PermissionEnvironment, isGranted: (String) -> Boolean): GrantLevel {
        if (environment.effectiveSdk < Api.TIRAMISU) {
            return if (isGranted(LEGACY)) GrantLevel.Full else GrantLevel.None
        }
        val media = mediaPermissions()
        val grantedMedia = media.count(isGranted)
        val userSelected = isUserSelected(environment, isGranted)
        return when {
            grantedMedia == media.size -> GrantLevel.Full
            grantedMedia > 0 || userSelected -> GrantLevel.Partial
            else -> GrantLevel.None
        }
    }

    // 일부 선택 상태에서 선택을 넓히려면 USER_SELECTED 를 포함한 전체를 다시 요청해야 선택 대화상자가 뜬다
    override fun nextRequest(environment: PermissionEnvironment, isGranted: (String) -> Boolean): List<String> =
        if (evaluate(environment, isGranted) == GrantLevel.Full) emptyList() else permissions(environment)

    // 사진은 허용, 동영상은 거부처럼 고른 매체 가운데 일부만 허용이면 일부 선택이 아니다
    override fun partialAccess(environment: PermissionEnvironment, isGranted: (String) -> Boolean): PartialAccess =
        if (isUserSelected(environment, isGranted) && mediaPermissions().none(isGranted)) {
            PartialAccess.SELECTED_MEDIA
        } else {
            PartialAccess.SOME_PERMISSIONS
        }

    private fun isUserSelected(environment: PermissionEnvironment, isGranted: (String) -> Boolean): Boolean =
        environment.effectiveSdk >= Api.UPSIDE_DOWN_CAKE && isGranted(USER_SELECTED)

    companion object {
        const val IMAGES = Manifest.permission.READ_MEDIA_IMAGES
        const val VIDEO = Manifest.permission.READ_MEDIA_VIDEO
        const val USER_SELECTED = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        const val LEGACY = Manifest.permission.READ_EXTERNAL_STORAGE
    }
}

/**
 * 블루투스.
 *
 * - API 31 이상: BLUETOOTH_SCAN·CONNECT·ADVERTISE 가 런타임 권한이다
 * - API 30 이하: 연결·광고용 BLUETOOTH/BLUETOOTH_ADMIN 은 설치 시 부여되는 일반 권한이고,
 *   기기 검색에는 위치 권한이 필요하다(API 29~30 은 FINE, 그 아래는 COARSE)
 *
 * API 레벨은 앱 targetSdk 와 기기 API 가운데 낮은 쪽이다 — targetSdk 30 앱은 API 31 기기에서도 옛 모델을 쓴다.
 */
internal data class BluetoothSpec(
    private val scan: Boolean,
    private val connect: Boolean,
    private val advertise: Boolean,
) : RuntimePermissionSpec {
    override val id: String = "bluetooth." +
        listOfNotNull("scan".takeIf { scan }, "connect".takeIf { connect }, "advertise".takeIf { advertise }).joinToString("+")

    override fun permissions(environment: PermissionEnvironment): List<String> = when {
        environment.effectiveSdk >= Api.S -> listOfNotNull(
            SCAN.takeIf { scan },
            CONNECT.takeIf { connect },
            ADVERTISE.takeIf { advertise },
        )
        !scan -> emptyList()
        environment.effectiveSdk >= Api.Q -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    // API 30 이하는 설치 시 부여되는 BLUETOOTH(검색·광고면 BLUETOOTH_ADMIN 도)를 선언해야 어댑터를 쓸 수 있다
    override fun declaredPermissions(environment: PermissionEnvironment): List<String> =
        if (environment.effectiveSdk >= Api.S) {
            permissions(environment)
        } else {
            listOfNotNull(LEGACY, LEGACY_ADMIN.takeIf { scan || advertise }) + permissions(environment)
        }

    companion object {
        const val SCAN = Manifest.permission.BLUETOOTH_SCAN
        const val CONNECT = Manifest.permission.BLUETOOTH_CONNECT
        const val ADVERTISE = Manifest.permission.BLUETOOTH_ADVERTISE
        const val LEGACY = Manifest.permission.BLUETOOTH
        const val LEGACY_ADMIN = Manifest.permission.BLUETOOTH_ADMIN
    }
}

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
/**
 * Health Connect 권한(API 34+ 플랫폼). 고른 데이터 종류의 `android.permission.health.READ_*`·`WRITE_*` 를 시스템 권한 대화상자로 요청한다.
 * API 34 미만은 Health Connect 가 별도 앱이라 이 명세의 범위 밖이고 NotApplicable 이다.
 * 백그라운드 읽기(`READ_HEALTH_DATA_IN_BACKGROUND`)는 다른 읽기 권한을 하나라도 받은 뒤 따로 요청한다.
 */
internal data class HealthConnectSpec(private val read: List<String>, private val write: List<String>) : RuntimePermissionSpec {
    override val id: String = "health_connect." + (read.map { "read_$it" } + write.map { "write_$it" }).joinToString("+").lowercase()

    private val all: List<String> = (read.map { PREFIX + "READ_" + it } + write.map { PREFIX + "WRITE_" + it }).distinct()

    override fun permissions(environment: PermissionEnvironment): List<String> =
        if (environment.sdkInt >= Api.UPSIDE_DOWN_CAKE) all else emptyList()

    override fun nextRequest(environment: PermissionEnvironment, isGranted: (String) -> Boolean): List<String> {
        val missing = permissions(environment).filterNot(isGranted)
        if (BACKGROUND !in missing) return missing
        val foreground = missing - BACKGROUND
        val readGranted = all.any { it != BACKGROUND && it.startsWith(PREFIX + "READ_") && isGranted(it) }
        return if (readGranted) foreground + BACKGROUND else foreground.ifEmpty { listOf(BACKGROUND) }
    }

    /** 근거 화면(`VIEW_PERMISSION_USAGE` + `HEALTH_PERMISSIONS`)이 없으면 시스템이 대화상자 없이 거부한다 */
    @SuppressLint("QueryPermissionsNeeded")
    override fun missingDeclarations(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Api.UPSIDE_DOWN_CAKE) return emptyList()
        val intent = Intent(Intent.ACTION_VIEW_PERMISSION_USAGE).addCategory(CATEGORY).setPackage(context.packageName)
        @Suppress("DEPRECATION")
        val found = context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        return if (found) {
            emptyList()
        } else {
            listOf(
                "<activity-alias android:permission=\"android.permission.START_VIEW_PERMISSION_USAGE\"> + " +
                    "<intent-filter><action android:name=\"${Intent.ACTION_VIEW_PERMISSION_USAGE}\" />" +
                    "<category android:name=\"$CATEGORY\" /></intent-filter>",
            )
        }
    }

    companion object {
        const val PREFIX = "android.permission.health."
        const val CATEGORY = "android.intent.category.HEALTH_PERMISSIONS"
        const val BACKGROUND = BodySensorsSpec.HEALTH_BACKGROUND
        val TYPE = Regex("[A-Z][A-Z0-9_]*")
    }
}
