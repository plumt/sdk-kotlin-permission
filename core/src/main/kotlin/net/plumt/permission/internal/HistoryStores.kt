package net.plumt.permission.internal

import android.content.Context
import net.plumt.permission.PermissionEnvironment
import net.plumt.permission.RequestHistoryStore

/** 값 하나에 두 비트: 요청함(1), 영구 거부(2) */
private const val REQUESTED = 1
private const val PERMANENT = 2

internal class SharedPreferencesHistoryStore(context: Context, name: String) : RequestHistoryStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun hasRequested(permission: String): Boolean = prefs.getInt(permission, 0) and REQUESTED != 0

    override fun isPermanentlyDenied(permission: String): Boolean = prefs.getInt(permission, 0) and PERMANENT != 0

    override fun record(permission: String, permanentlyDenied: Boolean) {
        prefs.edit().putInt(permission, REQUESTED or (if (permanentlyDenied) PERMANENT else 0)).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
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

    override fun clear() {
        synchronized(values) { values.clear() }
    }
}

internal class StaticEnvironment(
    override val sdkInt: Int,
    private val declared: Set<String>,
) : PermissionEnvironment {
    override fun isDeclared(permission: String): Boolean = permission in declared

    override fun toString(): String = "PermissionEnvironment(sdkInt=$sdkInt, declared=$declared)"
}
