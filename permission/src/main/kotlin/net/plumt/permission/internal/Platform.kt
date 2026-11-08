package net.plumt.permission.internal

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import net.plumt.permission.PermissionEnvironment

/**
 * API 레벨 상수 — 플랫폼 상수를 쓰면 낮은 compileSdk 소비자 쪽 린트가 시끄러워 숫자로 둔다.
 * `Build.VERSION_CODES` 와 같은 이름이고, 이름이 아직 SDK 에 없는 레벨은 Android 버전으로 부른다.
 */
internal object Api {
    const val M = 23
    const val N = 24
    const val O = 26
    const val O_MR1 = 27
    const val P = 28
    const val Q = 29
    const val R = 30
    const val S = 31
    const val TIRAMISU = 33
    const val UPSIDE_DOWN_CAKE = 34
    const val VANILLA_ICE_CREAM = 35
    const val BAKLAVA = 36

    /** Android 17 — compileSdk 36 에는 이 레벨의 상수가 없어 문자열 권한 이름과 함께 숫자로 둔다 */
    const val ANDROID_17 = 37
}

/** targetSdk 가 기기보다 낮으면 시스템은 그 targetSdk 의 권한 모델을 쓴다 */
internal val PermissionEnvironment.effectiveSdk: Int get() = minOf(sdkInt, targetSdkInt)

internal fun Context.packageUri(): Uri = "package:$packageName".toUri()

internal fun appDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

internal fun isPermissionGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * 이 앱의 앱 작업(AppOps) 모드. 특수 권한 판정이 쓰는 유일한 앱 작업 조회다.
 * API 29 이상은 폐기되지 않은 쪽을 쓴다(36 에서 폐기 표시됐지만 대체 함수는 같은 판정을 한다).
 */
internal fun appOpMode(context: Context, op: String): Int {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    return if (Build.VERSION.SDK_INT >= Api.Q) {
        unsafeCheckOp(appOps, op, context.packageName)
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(op, Process.myUid(), context.packageName)
    }
}

@RequiresApi(Api.Q)
private fun unsafeCheckOp(appOps: AppOpsManager, op: String, packageName: String): Int {
    @Suppress("DEPRECATION")
    return appOps.unsafeCheckOpNoThrow(op, Process.myUid(), packageName)
}

/**
 * 앱 작업 모드로 판정하는 특수 권한. 모드가 기본값이면 플랫폼처럼 [permission] 보유로 판정한다.
 */
internal fun appOpAllowed(context: Context, op: String, permission: String): Boolean = when (appOpMode(context, op)) {
    AppOpsManager.MODE_ALLOWED -> true
    AppOpsManager.MODE_DEFAULT -> isPermissionGranted(context, permission)
    else -> false
}

/** 이 앱이 [action] 으로 [bindPermission] 을 요구하는 서비스를 선언했으면 그 컴포넌트. 자기 패키지만 묻어 패키지 가시성 선언이 필요 없다 */
@SuppressLint("QueryPermissionsNeeded")
internal fun declaredService(context: Context, action: String, bindPermission: String): ComponentName? {
    val intent = Intent(action).setPackage(context.packageName)
    @Suppress("DEPRECATION")
    val services = context.packageManager.queryIntentServices(intent, 0)
    val info = services.firstOrNull { it.serviceInfo?.permission == bindPermission }?.serviceInfo ?: return null
    return ComponentName(info.packageName, info.name)
}

/** [declaredService] 가 없을 때 [SpecialPermissionSpec.missingDeclarations] 에 넣을 문장 */
internal fun serviceDeclaration(bindPermission: String, action: String): String =
    "<service android:permission=\"$bindPermission\"> + <intent-filter><action android:name=\"$action\" /></intent-filter>"
