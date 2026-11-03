# 오류와 실패 신호

라이브러리가 던지는 예외와, 예외 대신 값으로 알리는 실패 신호를 모듈별로 적는다.

예외는 세 종류뿐이다. 인자가 틀리면 `IllegalArgumentException`, 호출 순서·설정이 틀리면 `IllegalStateException`,
라이브러리 밖(시스템·소비자가 구현한 명세)이 실패하면 `PermissionException`(원인은 `cause`)이다.
예외 메시지에는 받은 값이 들어간다. 메시지 문구는 바뀔 수 있으니 분기는 예외 타입으로 한다.

## core

### 예외

| 예외 | 언제 | 호출부가 할 일 |
|---|---|---|
| `IllegalArgumentException` | `PermissionSpecs.media`·`contacts`·`calendar`·`bluetooth` 에 아무것도 고르지 않았을 때 | 하나 이상 고른다 |
| `IllegalArgumentException` | `PermissionEnvironment.of` 의 `sdkInt` 가 1 미만일 때 | 값을 바로잡는다 |
| `IllegalArgumentException` | `checkAll`·`createRequester`·`MultiplePermissionsStatus` 에 명세가 없거나 `id` 가 겹칠 때 | 명세 목록을 바로잡는다 |
| `IllegalArgumentException` | `createRequester(registry, key, …)` 의 `key` 가 비었을 때 | 키를 넘긴다 |
| `IllegalArgumentException` | `RequestHistoryStore.sharedPreferences` 의 `name` 이 비었을 때 | 이름을 넘긴다 |
| `IllegalArgumentException` | `RuntimePermissionSpec`·`SpecialPermissionSpec` 어느 쪽도 구현하지 않은 명세를 넘겼을 때 | 둘 중 하나를 구현한다 |
| `IllegalArgumentException` | `MultiplePermissionsStatus[spec]` 에 묶음에 없는 명세를 넘겼을 때 | 같은 묶음의 명세로 찾는다 |
| `IllegalStateException` | `PermissionRequester.launch` — 요청할 권한(특수 권한의 `manifestPermission` 포함)이 앱 매니페스트에 없고, `ManifestCheck` 가 `THROW_IF_DEBUGGABLE`(디버그 빌드)이거나 `ALWAYS_THROW` 일 때. 요청은 나가지 않는다 | 앱 `AndroidManifest.xml` 에 메시지의 `<uses-permission>` 을 더한다 |
| `IllegalStateException` | `unregister()` 뒤에, 또는 수명이 끝난 Activity 에 묶인 요청기로 `launch` 할 때 | 새로 등록한다 |
| `IllegalStateException` | `createRequester(caller, …)` 를 STARTED 뒤에 부를 때(androidx 가 던진다) | `onCreate` 에서 등록한다 |
| `PermissionException` | 소비자가 구현한 명세의 함수가 예외를 던졌을 때 — 원래 예외가 `cause` | 명세 구현을 고친다 |
| `PermissionException` | 특수 권한의 설정 화면도, 대신 여는 앱 상세 설정 화면도 열 수 없을 때(`cause` 는 `ActivityNotFoundException`, 첫 실패는 `suppressed`). 런타임 요청 결과 콜백 안에서 이어 열다가 날 수도 있다 | 기기 문제다 — 안내 문구로 대신한다 |
| `PermissionException` | `openAppSettings` 가 앱 상세 설정 화면을 열 수 없을 때 | 같다 |

### 값으로 알리는 신호

| 신호 | 언제 | 호출부가 할 일 |
|---|---|---|
| `launch()` 가 `false` | 같은 요청기의 흐름이 이미 진행 중일 때 | 무시해도 된다. 결과는 진행 중인 흐름의 콜백으로 온다 |
| 결과가 요청 전과 같은 상태 | 시스템이 빈 결과를 돌려줬을 때(다른 요청과 겹침·화면 재생성) — 이력을 바꾸지 않는다 | 다시 요청한다 |
| `NotApplicable(sdkInt)` | 이 API 레벨에 요청할 런타임 권한이 없을 때, 또는 특수 권한이 이 기기에 없을 때 | 쓸 수 있는 상태로 다룬다 |
| `Denied(shouldShowRationale = false)` | 처음 요청 전, 또는 처음 요청이 근거 안내 없이 거부됐을 때(대화상자를 닫은 경우 포함) | 요청한다 |
| `PermanentlyDenied` | 근거 안내가 서 있다가 거부 후 사라졌을 때, 또는 이미 요청한 적이 있는데 근거 안내 없이 다시 거부됐을 때 | 앱 설정 화면으로 안내한다 |
| `Partial(..., isMissingPermanentlyDenied = true)` | 일부 허용인데 나머지가 영구 거부일 때 | 쓸 수 있는 만큼 쓰고, 더 필요하면 설정으로 안내한다 |
| 특수 권한의 `Denied(false)` | 특수 권한이 꺼져 있을 때 — 특수 권한에는 영구 거부가 없다 | 요청하면 설정 화면이 열린다 |

## compose

`core` 의 예외가 그대로 올라온다. 이 모듈만의 것은 아래다.

| 예외 | 언제 | 호출부가 할 일 |
|---|---|---|
| `IllegalStateException` | `remember*State` 를 `ActivityResultRegistryOwner` 가 없는 곳(Activity 밖 미리보기 등)에서 부를 때 | `ComponentActivity.setContent` 안에서 부른다 |
| `IllegalStateException` | 컴포지션에서 벗어난 상태 객체로 `launchRequest`·`launchFlow` 할 때 | 화면에 있는 상태로 부른다 |
| `IllegalStateException` | `launchRequest`·`launchFlow` — 요청 권한이 매니페스트에 없을 때(core 와 같다) | 매니페스트에 선언한다 |
