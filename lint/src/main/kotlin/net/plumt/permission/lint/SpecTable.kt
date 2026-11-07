package net.plumt.permission.lint

/**
 * 앱 매니페스트에 있어야 하는 `<uses-permission>` 한 줄.
 *
 * @property maxSdk `android:maxSdkVersion` — 그 API 까지만 필요한 선언
 * @property flags `android:usesPermissionFlags`
 * @property protectedPermission 플랫폼 lint 가 ProtectedPermissions 로 막는 권한이라 `tools:ignore` 를 붙인다
 */
internal data class Decl(
    val name: String,
    val maxSdk: Int? = null,
    val flags: String? = null,
    val protectedPermission: Boolean = false,
) {
    fun line(): String = buildString {
        append("<uses-permission android:name=\"").append(name).append('"')
        if (maxSdk != null) append(" android:maxSdkVersion=\"").append(maxSdk).append('"')
        if (flags != null) append(" android:usesPermissionFlags=\"").append(flags).append('"')
        if (protectedPermission) append(" tools:ignore=\"ProtectedPermissions\"")
        append(" />")
    }

    fun encode(): String = listOf(name, maxSdk?.toString().orEmpty(), flags.orEmpty(), if (protectedPermission) "1" else "").joinToString("|")

    companion object {
        fun decode(value: String): Decl? {
            val parts = value.split('|')
            if (parts.size != 4 || parts[0].isEmpty()) return null
            return Decl(parts[0], parts[1].toIntOrNull(), parts[2].ifEmpty { null }, parts[3] == "1")
        }
    }
}

/** 명세가 고른 인자 값. 상수로 읽을 수 없으면 null */
internal interface Args {
    fun flag(name: String, default: Boolean): Boolean?

    /** 문자열 배열 인자. 넘기지 않았으면 빈 목록, 상수로 읽을 수 없으면 null */
    fun strings(name: String): List<String>? = null

    companion object {
        /** 불리언 인자만 읽는 Args */
        operator fun invoke(flag: (name: String, default: Boolean) -> Boolean?): Args = object : Args {
            override fun flag(name: String, default: Boolean): Boolean? = flag(name, default)
        }
    }
}

/**
 * 명세 하나가 요구하는 선언.
 *
 * @property required 반드시 있어야 하는 선언 — 없으면 오류
 * @property possible 인자를 상수로 읽지 못해(또는 선택 선언이라) 필요할 수도 있는 선언 — 오류는 아니고, "쓰지 않는 선언" 판정에서 뺀다
 */
internal class Need(val label: String, val required: List<Decl>, val possible: List<Decl>)

/** `PermissionSpecs`·`SystemRequirements` 의 멤버가 요구하는 매니페스트 선언표. core 의 명세와 같이 고친다 */
internal object SpecTable {
    const val SPECS = "net.plumt.permission.PermissionSpecs"
    const val REQUIREMENTS = "net.plumt.permission.SystemRequirements"

    private const val P = "android.permission."
    private const val READ_EXTERNAL = P + "READ_EXTERNAL_STORAGE"
    private const val FINE = P + "ACCESS_FINE_LOCATION"
    private const val COARSE = P + "ACCESS_COARSE_LOCATION"

    /** Health Connect 권한의 접두어 — 이 접두어의 선언은 모두 이 표가 다룬다 */
    const val HEALTH = P + "health."

    /** 인자를 읽지 못한 healthConnect 쓰임 — 모든 Health Connect 선언을 "쓰는 선언" 으로 센다 */
    const val HEALTH_ANY = HEALTH + "*"

    private class Builder(private val args: Args) {
        val required = ArrayList<Decl>()
        val possible = ArrayList<Decl>()

        fun need(vararg decls: Decl) {
            required += decls
        }

        fun maybe(vararg decls: Decl) {
            possible += decls
        }

        /** 인자가 참이면 필요, 읽을 수 없으면 필요할 수도 있음 */
        fun needIf(arg: String, default: Boolean, vararg decls: Decl) {
            when (args.flag(arg, default)) {
                true -> need(*decls)
                null -> maybe(*decls)
                false -> Unit
            }
        }
    }

    private fun d(name: String, maxSdk: Int? = null, flags: String? = null, protected: Boolean = false) =
        Decl(if (name.contains('.')) name else P + name, maxSdk, flags, protected)

    private val singles: Map<String, List<Decl>> = mapOf(
        "camera" to listOf(d("CAMERA")),
        "microphone" to listOf(d("RECORD_AUDIO")),
        "notifications" to listOf(d("POST_NOTIFICATIONS")),
        "activityRecognition" to listOf(d("ACTIVITY_RECOGNITION")),
        "audio" to listOf(d("READ_MEDIA_AUDIO"), d(READ_EXTERNAL, maxSdk = 32)),
        "mediaLocation" to listOf(d("ACCESS_MEDIA_LOCATION")),
        "phoneState" to listOf(d("READ_PHONE_STATE")),
        "phoneNumbers" to listOf(d("READ_PHONE_NUMBERS"), d("READ_PHONE_STATE", maxSdk = 25)),
        "callPhone" to listOf(d("CALL_PHONE")),
        "answerPhoneCalls" to listOf(d("ANSWER_PHONE_CALLS")),
        "processOutgoingCalls" to listOf(d("PROCESS_OUTGOING_CALLS")),
        "addVoicemail" to listOf(d("com.android.voicemail.permission.ADD_VOICEMAIL")),
        "useSip" to listOf(d("USE_SIP")),
        "acceptHandover" to listOf(d("ACCEPT_HANDOVER")),
        "accounts" to listOf(d("GET_ACCOUNTS")),
        "uwbRanging" to listOf(d("UWB_RANGING")),
        "ranging" to listOf(d("RANGING")),
        "ignoreBatteryOptimizations" to listOf(d("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")),
        "exactAlarm" to listOf(d("SCHEDULE_EXACT_ALARM")),
        "overlay" to listOf(d("SYSTEM_ALERT_WINDOW")),
        "installUnknownApps" to listOf(d("REQUEST_INSTALL_PACKAGES")),
        "allFilesAccess" to listOf(d("MANAGE_EXTERNAL_STORAGE")),
        "usageStats" to listOf(d("PACKAGE_USAGE_STATS", protected = true)),
        "notificationPolicy" to listOf(d("ACCESS_NOTIFICATION_POLICY")),
        "writeSettings" to listOf(d("WRITE_SETTINGS", protected = true)),
        "fullScreenIntent" to listOf(d("USE_FULL_SCREEN_INTENT")),
        "manageMedia" to listOf(d("MANAGE_MEDIA")),
        "turnScreenOn" to listOf(d("TURN_SCREEN_ON")),
        "userInitiatedJobs" to listOf(d("RUN_USER_INITIATED_JOBS")),
        "crossProfile" to listOf(d("INTERACT_ACROSS_PROFILES")),
        // 서비스·수신기 선언이나 동의 화면만 필요한 특수 접근 — uses-permission 은 없다
        "autoRevokeExempt" to emptyList(),
        "vpn" to emptyList(),
        "pictureInPicture" to emptyList(),
        // 서비스 선언이 필요한 특수 권한 — uses-permission 은 없다(런타임 검사가 서비스 누락을 알린다)
        "notificationListener" to emptyList(),
        "accessibilityService" to emptyList(),
    )

    private val requirements: Map<String, List<Decl>> = mapOf(
        "bluetoothEnabled" to listOf(d("BLUETOOTH_CONNECT"), d("BLUETOOTH", maxSdk = 30)),
        "wifiEnabled" to listOf(d("ACCESS_WIFI_STATE")),
        "notificationsEnabled" to listOf(d("POST_NOTIFICATIONS")),
        "locationEnabled" to emptyList(),
        "nfcEnabled" to emptyList(),
        "hardware" to emptyList(),
        "airplaneModeOff" to emptyList(),
        "internetAvailable" to listOf(d("ACCESS_NETWORK_STATE")),
        "dataSaverOff" to emptyList(),
        "powerSaveOff" to emptyList(),
        "backgroundUnrestricted" to emptyList(),
        "standbyBucket" to emptyList(),
        "doNotDisturbOff" to emptyList(),
        "deviceSecure" to emptyList(),
        "biometricEnrolled" to listOf(d("USE_BIOMETRIC")),
        "gpsEnabled" to emptyList(),
        "autoTimeEnabled" to emptyList(),
        "companionDeviceAssociated" to emptyList(),
    )

    /** 이 표가 아는 권한 이름 전부 — "쓰지 않는 선언" 은 이 안에서만 판정한다 */
    val knownPermissions: Set<String> by lazy {
        val all = HashSet<String>()
        (singles.values + requirements.values).forEach { list -> list.forEach { all += it.name } }
        for (member in listOf("location", "backgroundLocation", "media", "contacts", "calendar", "callLog", "bluetooth", "storage", "sms", "bodySensors", "nearbyWifiDevices")) {
            val need = lookup(SPECS, member, Args { _, _ -> null }) ?: continue
            (need.required + need.possible).forEach { all += it.name }
        }
        all
    }

    /** 이 표가 다루는 권한인지 — "쓰지 않는 선언" 은 이 안에서만 판정한다 */
    fun isKnown(name: String): Boolean = name in knownPermissions || name.startsWith(HEALTH)

    /** [owner] 의 [member](프로퍼티 이름 또는 함수 이름)가 요구하는 선언. 모르는 멤버면 null */
    fun lookup(owner: String, member: String, args: Args): Need? {
        val label = "${owner.substringAfterLast('.')}.$member"
        if (owner == REQUIREMENTS) return requirements[member]?.let { Need(label, it, emptyList()) }
        if (owner != SPECS) return null
        singles[member]?.let { list ->
            val possible = when (member) {
                "exactAlarm" -> listOf(d("USE_EXACT_ALARM"))
                else -> emptyList()
            }
            return Need(label, list, possible)
        }
        val b = Builder(args)
        when (member) {
            "location", "backgroundLocation" -> {
                b.need(d(COARSE))
                b.needIf("precise", true, d(FINE))
                if (member == "backgroundLocation") b.need(d("ACCESS_BACKGROUND_LOCATION"))
            }
            "media" -> {
                b.needIf("images", true, d("READ_MEDIA_IMAGES"))
                b.needIf("video", false, d("READ_MEDIA_VIDEO"))
                b.need(d(READ_EXTERNAL, maxSdk = 32))
                // 선택 선언 — 하면 API 34+ 의 사진 일부 선택이 켜진다
                b.maybe(d("READ_MEDIA_VISUAL_USER_SELECTED"))
            }
            "contacts" -> {
                b.needIf("read", true, d("READ_CONTACTS"))
                b.needIf("write", false, d("WRITE_CONTACTS"))
            }
            "calendar" -> {
                b.needIf("read", true, d("READ_CALENDAR"))
                b.needIf("write", false, d("WRITE_CALENDAR"))
            }
            "callLog" -> {
                b.needIf("read", true, d("READ_CALL_LOG"))
                b.needIf("write", false, d("WRITE_CALL_LOG"))
            }
            "bluetooth" -> {
                b.need(d("BLUETOOTH", maxSdk = 30))
                b.needIf("scan", false, d("BLUETOOTH_SCAN"), d("BLUETOOTH_ADMIN", maxSdk = 30), d(FINE, maxSdk = 30), d(COARSE, maxSdk = 30))
                b.needIf("connect", true, d("BLUETOOTH_CONNECT"))
                b.needIf("advertise", false, d("BLUETOOTH_ADVERTISE"), d("BLUETOOTH_ADMIN", maxSdk = 30))
            }
            "storage" -> {
                b.need(d(READ_EXTERNAL, maxSdk = 32))
                b.needIf("write", false, d("WRITE_EXTERNAL_STORAGE", maxSdk = 29))
            }
            "sms" -> {
                b.needIf("send", true, d("SEND_SMS"))
                b.needIf("receive", false, d("RECEIVE_SMS"))
                b.needIf("read", false, d("READ_SMS"))
                b.needIf("receiveMms", false, d("RECEIVE_MMS"))
                b.needIf("receiveWapPush", false, d("RECEIVE_WAP_PUSH"))
            }
            "bodySensors" -> {
                b.need(d("BODY_SENSORS"))
                b.needIf("heartRate", true, d("android.permission.health.READ_HEART_RATE"))
                b.needIf("oxygenSaturation", false, d("android.permission.health.READ_OXYGEN_SATURATION"))
                b.needIf("skinTemperature", false, d("android.permission.health.READ_SKIN_TEMPERATURE"))
                b.needIf(
                    "background",
                    false,
                    d("BODY_SENSORS_BACKGROUND"),
                    d("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"),
                )
            }
            "nearbyWifiDevices" -> when (args.flag("legacyLocation", true)) {
                true -> b.need(d("NEARBY_WIFI_DEVICES"), d(FINE, maxSdk = 32), d(COARSE, maxSdk = 32))
                // 위치를 얻지 않는다고 밝힌 쓰임 — 시스템이 위치 권한 없이 검색 결과를 준다
                false -> b.need(d("NEARBY_WIFI_DEVICES", flags = "neverForLocation"))
                null -> {
                    b.need(d("NEARBY_WIFI_DEVICES"))
                    b.maybe(d(FINE, maxSdk = 32), d(COARSE, maxSdk = 32))
                }
            }
            // 선언할 uses-permission 이 없는 함수 — 기기 관리자 수신기·역할 컴포넌트·포그라운드 서비스는 앱의 몫이다
            "deviceAdmin", "screenCapture", "role" -> Unit
            "healthConnect" -> {
                val read = args.strings("read")
                val write = args.strings("write")
                if (read == null || write == null) b.maybe(Decl(HEALTH_ANY))
                read?.forEach { b.need(d(HEALTH + "READ_" + it)) }
                write?.forEach { b.need(d(HEALTH + "WRITE_" + it)) }
            }
            else -> return null
        }
        return Need(label, b.required.distinct(), b.possible.distinct())
    }

    /** `getCamera` 처럼 컴파일된 프로퍼티 게터 이름을 프로퍼티 이름으로 */
    fun memberName(methodName: String, parameterCount: Int): String =
        if (parameterCount == 0 && methodName.length > 3 && methodName.startsWith("get") && methodName[3].isUpperCase()) {
            methodName[3].lowercaseChar() + methodName.substring(4)
        } else {
            methodName
        }
}
