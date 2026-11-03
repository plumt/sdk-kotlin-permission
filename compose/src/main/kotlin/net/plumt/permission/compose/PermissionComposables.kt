package net.plumt.permission.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import net.plumt.permission.MultiplePermissionsStatus
import net.plumt.permission.PermissionConfig
import net.plumt.permission.PermissionManager
import net.plumt.permission.PermissionSpec
import net.plumt.permission.PermissionStatus
import net.plumt.permission.compose.internal.MultiplePermissionsStateImpl
import net.plumt.permission.compose.internal.PermissionController
import net.plumt.permission.compose.internal.SinglePermissionState
import java.util.UUID

/**
 * 이 컴포지션에서 쓸 [PermissionManager] 를 만든다. [config] 는 처음 만들 때 한 번만 읽는다.
 * 앱 전체에서 하나를 쓰려면 직접 만든 인스턴스를 각 `remember*State` 에 넘긴다.
 */
@Composable
public fun rememberPermissionManager(config: PermissionConfig = PermissionConfig()): PermissionManager {
    val context = LocalContext.current.applicationContext
    return remember(context) { PermissionManager.create(context, config) }
}

/**
 * 권한 하나의 상태를 기억한다. 화면이 다시 보일 때(ON_RESUME)마다 다시 판정한다.
 *
 * @param onResult 요청 흐름이 끝날 때마다 받는 최종 상태
 * @throws IllegalStateException `ActivityResultRegistryOwner` 가 없는 곳(Activity 밖)에서 부를 때
 */
@Composable
public fun rememberPermissionState(
    spec: PermissionSpec,
    manager: PermissionManager = rememberPermissionManager(),
    onResult: (PermissionStatus) -> Unit = {},
): PermissionState {
    val controller = rememberController(listOf(spec), manager) { onResult(it[spec]) }
    return remember(controller) { SinglePermissionState(controller) }
}

/**
 * 여러 권한의 상태를 기억한다. 런타임 권한은 한 번의 시스템 요청으로 묻고, 특수 권한은 설정 화면을 차례로 연다.
 *
 * @throws IllegalArgumentException [specs] 가 비었거나 id 가 겹칠 때
 * @throws IllegalStateException `ActivityResultRegistryOwner` 가 없는 곳에서 부를 때
 */
@Composable
public fun rememberMultiplePermissionsState(
    specs: List<PermissionSpec>,
    manager: PermissionManager = rememberPermissionManager(),
    onResult: (MultiplePermissionsStatus) -> Unit = {},
): MultiplePermissionsState {
    val controller = rememberController(specs, manager, onResult)
    return remember(controller) { MultiplePermissionsStateImpl(controller) }
}

@Composable
private fun rememberController(
    specs: List<PermissionSpec>,
    manager: PermissionManager,
    onResult: (MultiplePermissionsStatus) -> Unit,
): PermissionController {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val registry = checkNotNull(LocalActivityResultRegistryOwner.current) {
        "ActivityResultRegistryOwner 가 없습니다. ComponentActivity 의 setContent 안에서 부르세요"
    }.activityResultRegistry
    val key = rememberSaveable { UUID.randomUUID().toString() }
    val latestOnResult = rememberUpdatedState(onResult)

    val controller = remember(manager, specs) {
        PermissionController(
            manager = manager,
            specs = specs,
            activity = { activity },
            openSettings = { manager.openAppSettings(activity ?: context) },
        )
    }
    SideEffect { controller.onResult = { latestOnResult.value(it) } }

    DisposableEffect(controller, registry, key) {
        val requester = manager.createRequester(registry, key, { activity }, specs) { controller.onRequesterResult(it) }
        controller.requester = requester
        onDispose {
            requester.unregister()
            if (controller.requester === requester) controller.requester = null
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.refresh() }
    return controller
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
