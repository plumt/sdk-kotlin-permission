# 오류와 실패 신호

라이브러리가 던지는 예외와, 예외 대신 값으로 알리는 실패 신호를 아티팩트별로 적는다.

예외는 세 종류뿐이다. 인자가 틀리면 `IllegalArgumentException`, 호출 순서·설정이 틀리면 `IllegalStateException`,
라이브러리 밖(시스템·소비자가 구현한 명세)이 실패하면 `PermissionException`(원인은 `cause`)이다.
예외 메시지에는 받은 값이 들어간다. 메시지 문구는 바뀔 수 있으니 분기는 예외 타입으로 한다.

## permission

### 예외

| 예외 | 언제 | 호출부가 할 일 |
|---|---|---|
| `IllegalArgumentException` | `PermissionSpecs.media`·`contacts`·`calendar`·`callLog`·`bluetooth`·`sms`·`bodySensors` 에 아무것도 고르지 않았을 때 | 하나 이상 고른다 |
| `IllegalArgumentException` | `SystemRequirements.hardware` 의 `feature` 가 비었거나 `minSdk` 가 1 미만일 때 | 값을 바로잡는다 |
| `IllegalArgumentException` | `SystemRequirements.standbyBucket` 의 `worstAllowed` 가 10·20·30·40·45 가 아닐 때 | `UsageStatsManager.STANDBY_BUCKET_*` 값을 넘긴다 |
| `IllegalArgumentException` | `PermissionSpecs.deviceAdmin` 의 `admin` 클래스 이름이 비었거나 `explanation` 이 빈 문자열일 때 | 수신기 컴포넌트를 넘기고, 설명이 없으면 null |
| `IllegalArgumentException` | `PermissionSpecs.healthConnect` 의 `read`·`write` 가 모두 비었거나, 종류 이름이 `[A-Z][A-Z0-9_]*` 가 아닐 때(권한 전체 이름·소문자·공백 포함) | `"STEPS"` 처럼 종류 이름만 넘긴다 |
| `IllegalArgumentException` | `openNotificationSettings` 의 `channelId` 가 빈 문자열일 때 | 채널 id 를 넘기거나 null 로 앱 화면을 연다 |
| `IllegalArgumentException` | `PermissionEnvironment.of` 의 `sdkInt`·`targetSdkInt` 가 1 미만일 때 | 값을 바로잡는다 |
| `IllegalArgumentException` | `checkAll`·`createRequester`·`request`(suspend·콜백)·`MultiplePermissionsStatus`·`FakePermissionManager` 에 명세·요구 조건이 모두 없거나 `id` 가 겹칠 때. 콜백 `request` 는 호출 즉시 던진다 | 목록을 바로잡는다 |
| `IllegalArgumentException` | `SystemRequirements.notificationChannelEnabled` 의 `channelId` 가 비었을 때 | 채널 id 를 넘긴다 |
| `IllegalArgumentException` | `AppRole(roleName, …)` 의 `roleName` 이 비었거나 `minSdk` 가 29 미만일 때, `PartialAccess(name)`·`UnsupportedReason(name)` 의 `name` 이 비었을 때 | 값을 바로잡는다 |
| `IllegalArgumentException` | `MultiplePermissionsStatus[requirement]` 에 묶음에 없는 요구 조건을 넘겼을 때 | 같은 묶음의 요구 조건으로 찾는다 |
| `IllegalArgumentException` | `createRequester(registry, key, …)` 의 `key` 가 비었을 때 | 키를 넘긴다 |
| `IllegalArgumentException` | `createRequester(registry, key, …, savedStateRegistry, …)` — 같은 `key` 의 요청기가 그 저장소에 이미 등록돼 있을 때 | 요청기마다 다른 키를 쓰거나 앞 요청기를 `unregister()` 한다 |
| `IllegalArgumentException` | `RequestHistoryStore.sharedPreferences` 의 `name` 이 비었을 때 | 이름을 넘긴다 |
| `IllegalArgumentException` | `MultiplePermissionsStatus[spec]` 에 묶음에 없는 명세를 넘겼을 때 | 같은 묶음의 명세로 찾는다 |
| `IllegalStateException` | `PermissionRequester.launch`·`request`(suspend·콜백, 콜백은 호출 즉시) — 요청할 권한(특수 권한의 `manifestPermission`, 시스템 기능의 `declaredPermissions` 포함)이 앱 매니페스트에 없거나 명세의 `missingDeclarations`(알림 접근·접근성·자동완성·입력기·VPN 서비스, 기기 관리자 수신기, Health Connect 근거 화면)가 있고, `ManifestCheck` 가 `ThrowIfDebuggable`(디버그 빌드)이거나 `AlwaysThrow` 일 때. 요청은 나가지 않는다 | 앱 `AndroidManifest.xml` 에 메시지의 `<uses-permission>` 을 더한다 |
| `IllegalStateException` | `unregister()` 뒤에, 또는 수명이 끝난 Activity 에 묶인 요청기로 `launch`·`openAppSettings` 할 때(`FakePermissionManager` 의 요청기도 같다) | 새로 등록한다 |
| `IllegalStateException` | `createRequester(caller, …)` 를 STARTED 뒤에 부를 때(androidx 가 던진다) | `onCreate` 에서 등록한다 |
| `PermissionException` | 소비자가 구현한 명세·요구 조건의 함수(`onSettingsResult`·`missingDeclarations` 포함)가 예외를 던졌을 때 — 원래 예외가 `cause`. 결과 훅에서 던지면 흐름은 끝난다 | 구현을 고친다 |
| `PermissionException` | 내장 특수 권한이 시스템에서 실패했을 때(예: 프로필 연결 요청 화면을 만들다 `SecurityException`) — 원래 예외가 `cause` | 기기 상태 문제다 — 안내 문구로 대신한다 |
| `PermissionException` | 시스템 기능의 켜기 화면(`EnableRequest.OfIntent`)을 열 수 없을 때(`cause` 는 `ActivityNotFoundException`). 흐름은 끝난다 | 기기 문제다 — 안내 문구로 대신한다 |
| `PermissionException` | `launch(SettingsPolicy.Direct)`·`request(…, SettingsPolicy.Direct)`·`openAppSettings()` 가 앱 상세 설정 화면을 열 수 없을 때. 콜백 `request` 에서는 흐름 도중에 나므로 메인 스레드의 처리되지 않은 예외로 올라간다 | 같다 |
| `PermissionException` | `openNotificationSettings` 가 알림 설정 화면도, 대신 여는 앱 상세 설정 화면도 열 수 없을 때(첫 실패는 `suppressed`) | 같다 |
| `PermissionException` | 특수 권한의 설정 화면도, 대신 여는 앱 상세 설정 화면도 열 수 없을 때(`cause` 는 `ActivityNotFoundException`, 첫 실패는 `suppressed`). 런타임 요청 결과 콜백 안에서 이어 열다가 날 수도 있다 | 기기 문제다 — 안내 문구로 대신한다 |
| `PermissionException` | `openAppSettings` 가 앱 상세 설정 화면을 열 수 없을 때 | 같다 |

### 값으로 알리는 신호

| 신호 | 언제 | 호출부가 할 일 |
|---|---|---|
| `launch()` 가 `false` | 같은 요청기의 흐름이 이미 진행 중일 때(줄에서 기다리는 중 포함) | 무시해도 된다. 결과는 진행 중인 흐름의 콜백으로 온다 |
| `openAppSettings()` 가 `false` | 흐름이 진행 중일 때 | 흐름이 끝난 뒤 다시 부른다 |
| `RequestResult.NoActivity(status)` | `request` 가 띄울 시작된 `ComponentActivity` 를 찾지 못했거나, 흐름 도중 화면이 닫혔을 때(회전이 아닌 종료). 콜백 `request` 도 같은 값을 콜백으로 준다 | 화면이 보일 때 다시 요청한다 |
| 콜백이 오지 않음 | 콜백 `request` 의 `RequestHandle.cancel()` 뒤, 또는 프로세스가 종료됐을 때 | 다시 요청한다. 흐름을 프로세스 종료 뒤에도 이으려면 요청기를 쓴다 |
| `RequirementStatus.Disabled` | 시스템 기능이 꺼져 있을 때. 켜기 화면에서 켜지 않고 돌아온 뒤에도 | 다시 `launch` 하면 켜기 화면만 다시 연다 |
| `RequirementStatus.PermissionRequired(permissions)` | 꺼져 있고 켜기에 필요한 권한이 없을 때, 또는 설치 시 부여 권한(`BLUETOOTH`·`ACCESS_WIFI_STATE`)이 선언되지 않았을 때 | 요청기가 권한을 먼저 요청한다. 설치 시 권한이면 매니페스트에 선언한다 |
| `RequirementStatus.Unmet` | 조건을 만족하지 않지만 앱이 열 해결 화면이 없을 때 — `standbyBucket` 기준 초과, `companionDeviceAssociated` 연결 없음, `biometricEnrolled` 의 하드웨어 일시 불가·보안 업데이트 필요, `notificationChannelEnabled` 의 채널을 아직 만들지 않음. 요청기는 건너뛴다 | 상태를 안내한다(대기 버킷은 앱을 쓰면 오른다, 연결은 `associate()`, 채널은 앱이 만든다) |
| `RequirementStatus.PermissionRequired(ACCESS_NETWORK_STATE·USE_BIOMETRIC)` | `internetAvailable`·`biometricEnrolled` 의 상태 확인 권한이 선언되지 않았을 때 | 매니페스트에 선언한다 |
| `RequirementStatus.Unsupported(reason)` | 하드웨어가 없거나(`UnsupportedReason.NO_HARDWARE` — GPS·컴패니언 기능·생체 하드웨어 포함) API 가 낮을 때(`API_LEVEL`). 열린 집합이라 직접 만든 요구 조건은 다른 이유를 낼 수 있다 | 기능을 숨기거나 대체한다 |
| 결과가 요청 전과 같은 상태 | 시스템이 빈 결과를 돌려줬을 때(다른 요청과 겹침·화면 재생성) — 이력을 바꾸지 않는다 | 다시 요청한다 |
| `NotApplicable(sdkInt)` | 이 API 레벨에 요청할 런타임 권한이 없을 때, 또는 특수 권한이 이 기기에 없을 때 | 쓸 수 있는 상태로 다룬다 |
| `Denied(shouldShowRationale = false)` | 처음 요청 전, 또는 처음 요청이 근거 안내 없이 거부됐을 때(떠 있던 대화상자를 바깥 탭·뒤로가기로 닫은 경우 포함 — 몇 번이어도 같다) | 요청한다 |
| `PermanentlyDenied` | 근거 안내가 서 있다가 거부 후 사라졌을 때, 또는 근거 안내 없이 대화상자도 뜨지 않고 곧바로 거부됐을 때(화면이 멈춘 뒤 280ms 미만). 이미 영구 거부인 권한은 2초 이상 떠 있던 대화상자를 닫거나 근거 안내가 서거나 허용돼야 풀린다. 약 280ms 안에 닫은 대화상자는 시스템이 고정하지 않았어도 이 값이 될 수 있다. 공식 API 가 없어 시간으로 내린 추정이다(README 알려진 한계) | 사용자가 버튼을 눌렀을 때 요청을 한 번 보내 보고(대화상자 없이 곧바로 거부되면 진짜), 그래도 그대로면 앱 설정 화면으로 안내한다 |
| `Partial(..., isMissingPermanentlyDenied = true)` | 일부 허용인데 나머지가 영구 거부일 때. 대화상자에서 사진 일부 선택·대략적 위치를 처음 고른 것만으로는 되지 않는다. 대략적 위치를 가진 채 정밀 승격을 거절하면 참, 사진·동영상 일부 선택이 허용된 동안은 늘 거짓 | 쓸 수 있는 만큼 쓰고, 더 필요하면 설정으로 안내한다 |
| `Partial(..., access = SELECTED_MEDIA·APPROXIMATE_LOCATION·FOREGROUND_LOCATION_ONLY)` | 사용자가 선택지를 골랐을 때 | 쓸 수 있는 만큼 쓴다. 다시 요청하면 선택 화면이 다시 뜬다 |
| 이력이 지워짐 | 확인할 때 권한이 허용돼 있거나 근거 안내가 서 있을 때, 2초 이상 떠 있던 대화상자를 닫았을 때, 기본 저장소를 열 때 설치 시각이 다를 때 | 없다 — 판정이 현실과 어긋나지 않게 하려는 동작이다 |
| 특수 권한의 `Denied(false)` | 특수 권한이 꺼져 있을 때 — 특수 권한에는 영구 거부가 없다 | 요청하면 설정 화면이 열린다 |
| `screenCapture` 의 `Denied(false)` | 늘 — 동의는 세션마다 받고 저장되지 않는다 | 동의 결과는 `onConsent` 로 받는다. 취소하면 불리지 않는다 |
| `role(…)` 이 요청 뒤에도 `Denied(false)` | 사용자가 거절했거나, 역할 요건(컴포넌트)이 없어 시스템이 대화상자 없이 끝냈을 때. `ASSISTANT`·`EMERGENCY`·`NOTES`·`WALLET` 은 기본 앱 설정 화면에서 사용자가 골라야 한다 | 역할별 컴포넌트를 선언한다 |
| `autofillService` 의 `Denied(false)` | 사용자가 다른 자동완성 서비스를 골랐거나, 자동완성이 없는 기기일 때 | 다시 요청하면 선택 화면이 열린다 |
| `inputMethod` 가 `Granted` 인데 다른 키보드가 뜸 | 사용 설정만 됐고 기본 키보드로 고르지 않았을 때(이 명세 밖) | 앱이 `InputMethodManager.showInputMethodPicker()` 를 띄운다 |
| `localNetwork` 의 `NotApplicable` | 기기나 앱 targetSdk 가 37 미만일 때 — `INTERNET` 으로 묵시 허용된다 | 쓸 수 있는 상태로 다룬다 |
| 특수 권한 요청이 앱 상세 설정 화면을 엶 | 전용 화면이 없는 기기(PiP·오래 걸리는 작업·자동완성 선택), 화면 켜기, 요청할 수 없는 프로필 연결 | 설정 위치를 안내한다 |

## permission-compose

`permission` 의 예외가 그대로 올라온다. 이 아티팩트만의 것은 아래다.

| 예외 | 언제 | 호출부가 할 일 |
|---|---|---|
| `IllegalStateException` | `remember*State` 를 `ActivityResultRegistryOwner` 가 없는 곳(Activity 밖 미리보기 등)에서 부를 때 | `ComponentActivity.setContent` 안에서 부른다 |
| `IllegalStateException` | 컴포지션에서 벗어난 상태 객체로 `launchRequest`·`launchFlow` 할 때 | 화면에 있는 상태로 부른다 |
| `IllegalStateException` | `launchRequest`·`launchFlow` — 요청 권한이 매니페스트에 없을 때(`permission` 과 같다) | 매니페스트에 선언한다 |
| `IllegalArgumentException` | `remember*State` 의 `specs`·`requirements` 가 모두 비었거나 id 가 겹칠 때 | 목록을 바로잡는다 |

| 신호 | 언제 | 호출부가 할 일 |
|---|---|---|
| `onResult` 가 요청 없이 한 번 불림 | `launchFlow` 가 연 설정 화면에서 돌아왔을 때(ON_RESUME) — 재판정한 상태다 | 요청 결과와 같게 다룬다 |
| `flowStep` 이 `PermissionFlowStep.Settings` | `launchFlow`(`SettingsPolicy.Prompt`) 의 요청 결과에 `needsSettings` 인 상태가 있을 때. 시작 판정에서는 나오지 않는다 | `PermissionFlowDialogs` 로 안내한다 |
| `flowStep` 이 `PermissionFlowStep.Requirement` | `launchFlow` 의 요청 뒤에도 시스템 기능이 `Disabled` 일 때 | 확인하면 켜기 화면을 다시 연다. 닫으면 끝난다 |

## lint

| 검사 | 언제 | 할 일 |
|---|---|---|
| `PlumtPermissionNotDeclared`(오류) | 코드가 쓰는 명세의 권한이 병합된 앱 매니페스트에 없을 때(상한이 짧게 병합된 경우 포함). targetSdk 조건이 있는 선언(`localNetwork` 의 `ACCESS_LOCAL_NETWORK`, targetSdk 37+)은 그 아래 앱에 요구하지 않는다 | 고치기로 줄을 넣거나, 의도면 심각도를 낮추거나 `@Suppress` 한다 |
| `PlumtPermissionDeclaredButUnused`(정보) | 앱 매니페스트가 선언한 권한을 어느 명세도 쓰지 않을 때 | 다른 쓰임이 없으면 지운다 |
| `healthConnect` 인자를 읽지 못함(오류 없음) | `read`·`write` 가 상수 배열이 아닐 때 — 무엇이 필요한지 몰라 오류를 내지 않고 모든 `android.permission.health.*` 선언을 쓰는 것으로 센다 | 상수 배열을 넘기면 검사가 켜진다 |
