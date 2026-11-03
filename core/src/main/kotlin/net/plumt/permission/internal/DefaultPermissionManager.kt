package net.plumt.permission.internal

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import net.plumt.permission.GrantLevel
import net.plumt.permission.ManifestCheck
import net.plumt.permission.MultiplePermissionsStatus
import net.plumt.permission.PermissionConfig
import net.plumt.permission.PermissionEnvironment
import net.plumt.permission.PermissionException
import net.plumt.permission.PermissionManager
import net.plumt.permission.PermissionRequester
import net.plumt.permission.PermissionSpec
import net.plumt.permission.PermissionStatus
import net.plumt.permission.RequestHistoryStore
import net.plumt.permission.RuntimePermissionSpec
import net.plumt.permission.SpecialPermissionSpec

/** 매니페스트 선언은 실행 중에 바뀌지 않으므로 처음 물을 때 한 번 읽는다 */
internal class PackageEnvironment(private val context: Context, override val sdkInt: Int) : PermissionEnvironment {
    private val declared: Set<String> by lazy {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.toSet().orEmpty()
    }

    override fun isDeclared(permission: String): Boolean = permission in declared

    override fun toString(): String = "PermissionEnvironment(sdkInt=$sdkInt)"
}

internal class DefaultPermissionManager(
    private val context: Context,
    private val history: RequestHistoryStore,
    private val manifestCheck: ManifestCheck,
    override val environment: PermissionEnvironment,
    private val isDebuggable: Boolean,
    private val isHeld: (String) -> Boolean,
    private val rationale: (Activity, String) -> Boolean,
) : PermissionManager {

    override fun check(spec: PermissionSpec, activity: Activity?): PermissionStatus = when (spec) {
        is RuntimePermissionSpec -> runtimeStatus(spec, activity)
        is SpecialPermissionSpec -> specialStatus(spec)
        else -> throw IllegalArgumentException(
            "명세는 RuntimePermissionSpec 이나 SpecialPermissionSpec 을 구현해야 합니다: ${spec::class.java.name}",
        )
    }

    override fun checkAll(specs: List<PermissionSpec>, activity: Activity?): MultiplePermissionsStatus {
        validateSpecs(specs)
        return MultiplePermissionsStatus(specs.associateWith { check(it, activity) })
    }

    private fun runtimeStatus(spec: RuntimePermissionSpec, activity: Activity?): PermissionStatus {
        val all = guarded(spec) { spec.permissions(environment) }
        if (all.isEmpty()) return PermissionStatus.NotApplicable(environment.sdkInt)
        val level = guarded(spec) { spec.evaluate(environment, isHeld) }
        if (level == GrantLevel.FULL) return PermissionStatus.Granted
        val pending = pendingOf(spec)
        val showRationale = activity != null && pending.any { rationale(activity, it) }
        val permanent = !showRationale && pending.any { history.isPermanentlyDenied(it) }
        return when (level) {
            GrantLevel.PARTIAL -> PermissionStatus.Partial(
                granted = all.filter(isHeld).toSet(),
                missing = all.filterNot(isHeld).toSet(),
                shouldShowRationale = showRationale,
                isMissingPermanentlyDenied = permanent,
            )
            else -> when {
                showRationale -> PermissionStatus.Denied(shouldShowRationale = true)
                permanent -> PermissionStatus.PermanentlyDenied
                else -> PermissionStatus.Denied(shouldShowRationale = false)
            }
        }
    }

    private fun specialStatus(spec: SpecialPermissionSpec): PermissionStatus = when {
        !guarded(spec) { spec.isApplicable(environment) } -> PermissionStatus.NotApplicable(environment.sdkInt)
        guarded(spec) { spec.isGranted(context) } -> PermissionStatus.Granted
        else -> PermissionStatus.Denied(shouldShowRationale = false)
    }

    internal fun pendingOf(spec: RuntimePermissionSpec): List<String> =
        guarded(spec) { spec.nextRequest(environment, isHeld) }.distinct()

    internal fun shouldShowRationale(activity: Activity, permission: String): Boolean = rationale(activity, permission)

    internal fun isGranted(permission: String): Boolean = isHeld(permission)

    internal val historyStore: RequestHistoryStore get() = history

    internal val appContext: Context get() = context

    /** 요청 직전 매니페스트 선언 확인. 빠졌으면 [IllegalStateException] */
    internal fun verifyDeclared(permissions: Collection<String>) {
        val enforce = when (manifestCheck) {
            ManifestCheck.OFF -> false
            ManifestCheck.ALWAYS_THROW -> true
            ManifestCheck.THROW_IF_DEBUGGABLE -> isDebuggable
        }
        if (!enforce) return
        val missing = permissions.filterNot(environment::isDeclared).distinct()
        check(missing.isEmpty()) {
            "앱 매니페스트에 선언되지 않은 권한을 요청하려 합니다: $missing. " +
                "선언하지 않은 권한은 대화상자 없이 거부되어 영구 거부처럼 보입니다. " +
                "앱의 AndroidManifest.xml 에 " +
                missing.joinToString(" ") { "<uses-permission android:name=\"$it\" />" } +
                " 를 추가하세요(이 라이브러리는 권한을 선언하지 않습니다). " +
                "확인을 끄려면 PermissionConfig(manifestCheck = ManifestCheck.OFF) 를 쓰세요."
        }
    }

    override fun appSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    override fun openAppSettings(context: Context) {
        val intent = appSettingsIntent()
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw PermissionException("앱 상세 설정 화면을 열 수 없습니다: ${intent.action}", e)
        }
    }

    override fun createRequester(
        caller: ActivityResultCaller,
        activity: () -> Activity?,
        specs: List<PermissionSpec>,
        onResult: (MultiplePermissionsStatus) -> Unit,
    ): PermissionRequester {
        validateSpecs(specs)
        val requester = DefaultPermissionRequester(this, specs.toList(), activity, onResult)
        val runtime = caller.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            requester.onRuntimeResult(it)
        }
        val settings = caller.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            requester.onSettingsResult()
        }
        requester.attach(runtime, settings)
        return requester
    }

    override fun createRequester(
        registry: ActivityResultRegistry,
        key: String,
        activity: () -> Activity?,
        specs: List<PermissionSpec>,
        onResult: (MultiplePermissionsStatus) -> Unit,
    ): PermissionRequester {
        require(key.isNotBlank()) { "key 가 비었습니다" }
        validateSpecs(specs)
        val requester = DefaultPermissionRequester(this, specs.toList(), activity, onResult)
        val runtime = registry.register("$key#runtime", ActivityResultContracts.RequestMultiplePermissions()) {
            requester.onRuntimeResult(it)
        }
        val settings = registry.register("$key#settings", ActivityResultContracts.StartActivityForResult()) {
            requester.onSettingsResult()
        }
        requester.attach(runtime, settings)
        return requester
    }

    override fun toString(): String = "PermissionManager(environment=$environment, manifestCheck=$manifestCheck)"

    companion object {
        fun create(context: Context, config: PermissionConfig): DefaultPermissionManager {
            val app = context.applicationContext ?: context
            return DefaultPermissionManager(
                context = app,
                history = config.historyStore ?: RequestHistoryStore.sharedPreferences(app),
                manifestCheck = config.manifestCheck,
                environment = PackageEnvironment(app, Build.VERSION.SDK_INT),
                isDebuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                isHeld = { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED },
                rationale = { activity, permission -> ActivityCompat.shouldShowRequestPermissionRationale(activity, permission) },
            )
        }
    }
}

internal fun validateSpecs(specs: List<PermissionSpec>) {
    require(specs.isNotEmpty()) { "명세가 하나 이상 있어야 합니다" }
    val duplicated = specs.groupBy { it.id }.filterValues { it.size > 1 }.keys
    require(duplicated.isEmpty()) { "같은 id 의 명세가 겹칩니다: $duplicated" }
}

/** 소비자가 구현한 명세의 예외를 [PermissionException] 으로 감싼다 */
internal inline fun <T> guarded(spec: PermissionSpec, block: () -> T): T = try {
    block()
} catch (e: PermissionException) {
    throw e
} catch (e: RuntimeException) {
    throw PermissionException("명세가 실패했습니다: ${runCatching { spec.id }.getOrDefault(spec::class.java.name)}", e)
}
