package net.plumt.permission.internal

import android.content.Context
import androidx.core.content.edit
import net.plumt.permission.PermissionEnvironment
import net.plumt.permission.RequestHistoryStore

/** 값 하나에 두 비트: 요청함(1), 영구 거부(2) */
private const val REQUESTED = 1
private const val PERMANENT = 2

/** 이력을 기록한 설치를 가르는 키 — 권한 이름에는 `#` 이 없어 겹치지 않는다 */
internal const val INSTALL_STAMP_KEY: String = "net.plumt.permission#install"

/** 이 설치의 최초 설치 시각. 업데이트로는 바뀌지 않고 재설치·다른 기기 복원에서 바뀐다 */
internal fun installStamp(context: Context): Long =
    context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime

internal class SharedPreferencesHistoryStore(context: Context, name: String, installStamp: Long) : RequestHistoryStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    init {
        // 다른 설치(백업 복원·재설치)에서 온 이력은 지금 권한 상태와 관계가 없다
        if (!prefs.contains(INSTALL_STAMP_KEY) || prefs.getLong(INSTALL_STAMP_KEY, 0L) != installStamp) {
            prefs.edit {
                clear()
                putLong(INSTALL_STAMP_KEY, installStamp)
            }
        }
    }

    override fun hasRequested(permission: String): Boolean = prefs.getInt(permission, 0) and REQUESTED != 0

    override fun isPermanentlyDenied(permission: String): Boolean = prefs.getInt(permission, 0) and PERMANENT != 0

    override fun record(permission: String, permanentlyDenied: Boolean) {
        prefs.edit { putInt(permission, REQUESTED or (if (permanentlyDenied) PERMANENT else 0)) }
    }

    override fun remove(permission: String) {
        prefs.edit { remove(permission) }
    }

    override fun clear() {
        val stamp = prefs.getLong(INSTALL_STAMP_KEY, 0L)
        prefs.edit {
            clear()
            putLong(INSTALL_STAMP_KEY, stamp)
        }
    }
}

internal class InMemoryHistoryStore : RequestHistoryStore {
    private val values = HashMap<String, Int>()

    override fun hasRequested(permission: String): Boolean = synchronized(values) { (values[permission] ?: 0) and REQUESTED != 0 }

    override fun isPermanentlyDenied(permission: String): Boolean =
        synchronized(values) { (values[permission] ?: 0) and PERMANENT != 0 }

    override fun record(permission: String, permanentlyDenied: Boolean) {
        synchronized(values) { values[permission] = REQUESTED or (if (permanentlyDenied) PERMANENT else 0) }
    }

    override fun remove(permission: String) {
        synchronized(values) { values.remove(permission) }
    }

    override fun clear() {
        synchronized(values) { values.clear() }
    }
}

internal class StaticEnvironment(
    override val sdkInt: Int,
    private val declared: Set<String>,
    override val targetSdkInt: Int = sdkInt,
) : PermissionEnvironment {
    override fun isDeclared(permission: String): Boolean = permission in declared

    override fun toString(): String = "PermissionEnvironment(sdkInt=$sdkInt, targetSdkInt=$targetSdkInt, declared=$declared)"
}
