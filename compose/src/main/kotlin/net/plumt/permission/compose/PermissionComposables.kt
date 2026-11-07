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
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import net.plumt.permission.MultiplePermissionsStatus
import net.plumt.permission.PermissionConfig
import net.plumt.permission.PermissionManager
import net.plumt.permission.PermissionSpec
import net.plumt.permission.PermissionStatus
import net.plumt.permission.SystemRequirement
import net.plumt.permission.compose.internal.FlowMemory
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
 * 명세는 [PermissionSpec.id] 로 같은지 본다 — 재구성마다 새로 만든 명세 객체여도 id 가 같으면 상태와 등록을 유지한다.
 * 진행 중인 흐름(안내 단계·남은 특수 권한)은 화면 재생성·프로세스 종료 뒤에도 이어진다.
 *
 * @param requirements 함께 켜져 있어야 하는 시스템 기능. 상태는 [PermissionRequestState.requirementStatuses]
 * @param onResult 요청 흐름이 끝날 때마다 받는 최종 상태
 * @throws IllegalStateException `ActivityResultRegistryOwner` 가 없는 곳(Activity 밖)에서 부를 때
 */
@Composable
public fun rememberPermissionState(
    spec: PermissionSpec,
    manager: PermissionManager = rememberPermissionManager(),
    requirements: List<SystemRequirement> = emptyList(),
    onResult: (PermissionStatus) -> Unit = {},
): PermissionState {
    val controller = rememberController(listOf(spec), requirements, manager) { onResult(it[spec]) }
    return remember(controller) { SinglePermissionState(controller) }
}

/**
 * 여러 권한의 상태를 기억한다. 런타임 권한은 한 번의 시스템 요청으로 묻고, 특수 권한은 설정 화면을 차례로 연다.
 * 명세는 [PermissionSpec.id] 로 같은지 본다 — 목록의 id 가 바뀔 때만 상태를 새로 만든다.
 * 시스템 기능만 다루려면 [specs] 를 비우고 [requirements] 만 넘긴다.
 *
 * @throws IllegalArgumentException [specs]·[requirements] 가 모두 비었거나 id 가 겹칠 때
 * @throws IllegalStateException `ActivityResultRegistryOwner` 가 없는 곳에서 부를 때
 */
@Composable
public fun rememberMultiplePermissionsState(
    specs: List<PermissionSpec>,
    manager: PermissionManager = rememberPermissionManager(),
    requirements: List<SystemRequirement> = emptyList(),
    onResult: (MultiplePermissionsStatus) -> Unit = {},
): MultiplePermissionsState {
    val controller = rememberController(specs, requirements, manager, onResult)
    return remember(controller) { MultiplePermissionsStateImpl(controller) }
}

@Composable
private fun rememberController(
    specs: List<PermissionSpec>,
    requirements: List<SystemRequirement>,
    manager: PermissionManager,
    onResult: (MultiplePermissionsStatus) -> Unit,
): PermissionController {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val registry = checkNotNull(LocalActivityResultRegistryOwner.current) {
        "ActivityResultRegistryOwner 가 없습니다. ComponentActivity 의 setContent 안에서 부르세요"
    }.activityResultRegistry
    val savedState = LocalSavedStateRegistryOwner.current.savedStateRegistry
    val key = rememberSaveable { UUID.randomUUID().toString() }
    val memory = rememberSaveable(saver = FlowMemory.Saver) { FlowMemory() }
    val latestOnResult = rememberUpdatedState(onResult)
    // 소비자가 equals 없는 명세를 재구성마다 새로 만들어도 등록을 다시 하지 않도록 id 로 묶는다
    val ids = specs.map { it.id } + requirements.map { "requirement:" + it.id }

    val controller = remember(manager, ids) {
        PermissionController(
            manager = manager,
            specs = specs.toList(),
            activity = { activity },
            openSettings = { manager.openAppSettings(activity ?: context) },
            memory = memory,
            requirements = requirements.toList(),
        )
    }
    SideEffect { controller.onResult = { latestOnResult.value(it) } }

    DisposableEffect(controller, registry, key, savedState) {
        val requester = manager.createRequester(
            registry = registry,
            key = key,
            activity = { activity },
            specs = controller.specs,
            savedStateRegistry = savedState,
            requirements = controller.requirements,
        ) { controller.onRequesterResult(it) }
        controller.attach(requester)
        onDispose {
            requester.unregister()
            if (controller.requester === requester) controller.attach(null)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.onResume() }
    return controller
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
