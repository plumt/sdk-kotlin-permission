package net.plumt.permission.compose

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * [state] 의 [PermissionRequestState.flowStep] 에 맞춰 안내 대화상자를 띄운다.
 * 슬롯을 바꾸면 기본 Material3 대화상자 대신 원하는 UI 를 쓴다 — 슬롯은 확인·닫기 콜백을 받는다.
 */
@Composable
public fun PermissionFlowDialogs(
    state: PermissionRequestState,
    rationale: @Composable (onConfirm: () -> Unit, onDismiss: () -> Unit) -> Unit = { onConfirm, onDismiss ->
        PermissionRationaleDialog(onConfirm = onConfirm, onDismiss = onDismiss)
    },
    settings: @Composable (onConfirm: () -> Unit, onDismiss: () -> Unit) -> Unit = { onConfirm, onDismiss ->
        PermissionSettingsDialog(onConfirm = onConfirm, onDismiss = onDismiss)
    },
) {
    when (state.flowStep) {
        PermissionFlowStep.NONE -> Unit
        PermissionFlowStep.RATIONALE -> rationale(state::confirmFlowStep, state::dismissFlowStep)
        PermissionFlowStep.SETTINGS -> settings(state::confirmFlowStep, state::dismissFlowStep)
    }
}

/** 기본 근거 안내 대화상자. 문구를 넘기지 않으면 라이브러리 기본 문구(영어·한국어)를 쓴다. */
@Composable
public fun PermissionRationaleDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.plumt_permission_rationale_title),
    text: String = stringResource(R.string.plumt_permission_rationale_message),
    confirmLabel: String = stringResource(R.string.plumt_permission_rationale_confirm),
    dismissLabel: String = stringResource(R.string.plumt_permission_dismiss),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

/** 기본 설정 안내 대화상자. 영구 거부일 때 앱 설정 화면으로 보낸다. */
@Composable
public fun PermissionSettingsDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.plumt_permission_settings_title),
    text: String = stringResource(R.string.plumt_permission_settings_message),
    confirmLabel: String = stringResource(R.string.plumt_permission_settings_confirm),
    dismissLabel: String = stringResource(R.string.plumt_permission_dismiss),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}
