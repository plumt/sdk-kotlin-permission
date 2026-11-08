# Changelog

아티팩트별로 적는다(0.0.x 의 `core`·`compose` 는 0.1.0 에서 `permission`·`permission-compose` 로 이름이 바뀌었다). 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [SemVer](https://semver.org/lang/ko/) 이다.

## permission

### [Unreleased]

### [0.1.0] - 2026-11-08

#### 깨짐
- 좌표가 바뀌었다 — `net.plumt.permission:core` → `net.plumt.permission:permission`. 태그는 `permission-v<버전>`, 모듈 디렉터리도 `permission`
- 공개 `enum` 을 없앴다
  - 닫힌 집합은 `sealed` 로: `SettingsPolicy.NEVER·PROMPT·DIRECT` → `SettingsPolicy.Never·Prompt·Direct`,
    `ManifestCheck.THROW_IF_DEBUGGABLE·ALWAYS_THROW·OFF` → `ManifestCheck.ThrowIfDebuggable·AlwaysThrow·Off`, `GrantLevel.FULL·PARTIAL·NONE` → `GrantLevel.Full·Partial·None`
  - 값이 늘 수 있는 열린 집합은 일반 클래스로(상수 이름은 그대로): `PartialAccess`·`UnsupportedReason`·`AppRole`. `values()`·`entries`·`ordinal`·`valueOf` 가 없어졌고,
    `when` 에는 `else` 를 둔다. `AppRole` 은 `name` 대신 `roleName` 으로 가른다
- 공개 `data class` 를 일반 클래스(+`equals`/`hashCode`/`toString`)로 바꿨다 — `PermissionStatus.NotApplicable·Partial·Denied`, `RequestResult.Completed·NoActivity`,
  `RequirementStatus.PermissionRequired·Unsupported` 의 `copy`·`componentN` 이 없어졌다(구조 분해 선언을 쓰던 곳은 속성을 읽는다)
- `PermissionSpec` 이 `sealed interface` 가 됐다 — 직접 구현하던 명세는 `RuntimePermissionSpec`·`SpecialPermissionSpec` 을 구현한다(그전에도 그 밖의 구현은 `IllegalArgumentException` 이었다)
- `PermissionManager` 에 콜백 `request(activity, specs, …, onResult)` 가 늘었다 — 직접 구현한 가짜는 구현해야 한다(대신 `FakePermissionManager` 를 쓴다)
- 공개 인터페이스의 기본 구현을 Java 기본 메서드로만 낸다(`-jvm-default=no-compatibility`) — `*$DefaultImpls` 클래스가 없어졌다(Java 에서 부르던 경우만 해당)
- `turnScreenOn` 의 적용 여부가 앱 targetSdk 와 무관해졌다 — API 36 기기면 targetSdk 35 앱에도 `NotApplicable` 이 아니다(판정과 적용 여부를 같은 기준(기기 API)으로 맞췄다)
- 진행 중 흐름의 저장 상태가 설정 이동 정책을 이름으로 남긴다 — 0.0.x 가 남긴 흐름을 0.1.0 이 이어받으면 정책은 `Never` 로 읽는다

#### 추가
- 콜백 요청 `PermissionManager.request(activity, specs, requirements, settingsPolicy) { result -> }` 와 취소 핸들 `RequestHandle` — suspend 요청을 관리자의 메인 스레드 코루틴에서 돌린다.
  등록 시점 제약이 없고, 결과는 메인 스레드로 한 번 온다. 인자·매니페스트 실수는 호출 즉시 던진다
- 권한 하나 편의 오버로드 — `request(spec)`·`request(activity, spec)`(suspend), `request(activity, spec) { }`(콜백)
- 테스트용 `FakePermissionManager` — 상태·요청 뒤 결과를 정하고 요청 기록을 본다. 기기·Robolectric 없이 돈다
- 역할 `AppRole.EMERGENCY`(29+)·`NOTES`(34+)·`WALLET`(35+)과 `AppRole.minSdk`, 새 플랫폼 역할을 만드는 `AppRole(roleName, requestable, minSdk)`
- 런타임 권한 `PermissionSpecs.localNetwork`(Android 17 / API 37, targetSdk 37+ 의 `ACCESS_LOCAL_NETWORK`)
- 특수 권한 `PermissionSpecs.autofillService`(26+, 자동완성 서비스 선택)·`inputMethod`(입력기 사용 설정)·`bubbles`(30+, 앱 말풍선 허용)
- 요구 조건 `SystemRequirements.notificationChannelEnabled(channelId)` — 앱 알림·채널 중요도·채널 그룹 차단으로 판정하고, 채널을 만들지 않았으면 `Unmet`
- `RuntimePermissionSpec.declaredPermissions(environment)` — 설치 시 부여되는 짝 권한까지 담은 선언 정본(기본은 `permissions`). 블루투스는 API 30 이하 `BLUETOOTH`·`BLUETOOTH_ADMIN` 을 더한다
- `PartialAccess(name)`·`UnsupportedReason(name)` 생성자 — 직접 만든 명세·요구 조건이 새 값을 쓴다
- lint 선언표와 명세의 일치 테스트(API 23~37 × targetSdk), README 지원 범위 목록의 일치 테스트 — 어긋나면 빌드가 실패한다

#### 바뀜
- `undeclaredPermissions` 가 `declaredPermissions` 를 본다 — API 30 이하에서 `bluetooth` 의 `BLUETOOTH`·`BLUETOOTH_ADMIN` 누락도 알린다
- lint: targetSdk 조건이 있는 선언(`ACCESS_LOCAL_NETWORK`)은 앱 targetSdk 37 이상에서만 요구한다. `bubbles`·`autofillService`·`inputMethod`·`notificationChannelEnabled` 를 표에 더했다
- 특수 권한 구현을 기준대로 나눴다 — 설정 토글(`SettingsSpecs`)과 앱 컴포넌트·시스템 동의(`ComponentSpecs`), 런타임 권한은 `RuntimeSpecs` 한 곳. 앱 작업 조회 함수를 하나로 합쳤다(공개 API 변화 없음)
- 파일 단위 `@SuppressLint("NewApi")` 를 없애고 API 분기를 `@RequiresApi` 함수로 격리했다(동작 변화 없음)
- README 의 사용법을 `PermissionManager` 하나(suspend·콜백·Compose)로 모으고 요청기는 "고급" 으로 옮겼다. API 분기 표에 API 37 열을 더했다


### [0.0.2] - 2026-11-07

#### 수정
- 대화상자를 바깥 탭·뒤로가기로 두 번 닫으면 `PermanentlyDenied` 로 판정하던 문제. Android 11+ 는 이것을 거부 횟수에 세지 않아
  세 번째 요청에 대화상자가 다시 떴다. 영구 거부를 거부 횟수가 아니라 요청 전·후 근거 안내 값과 대화상자가 실제로 떴는지로 판정한다.
  대화상자 없이 곧바로 끝난 거부는 첫 요청이어도 영구 거부다. 공개 API 변화 없음
- 위 수정의 회귀: 진짜 영구 거부를 다시 요청하면 `Denied(false)` 로 뒤집히고 이력이 지워져 설정 안내·`DIRECT` 설정 이동이 안 되던 문제.
  대화상자 없이 끝나는 요청도 투명한 권한 화면이 창 포커스를 잠깐 뺏어(실기기 Android 10·16) 포커스로는 가를 수 없었다.
  요청한 화면이 멈춘 뒤 결과까지의 시간으로 판정을 바꿨고(280ms 미만 영구 거부), 이미 확인한 영구 거부 이력은 2초 이상 떠 있던 대화상자나
  근거 안내·허용이 아니면 지우지 않는다. 공개 API 변화 없음

#### 문서
- 영구 거부 추정의 한계를 README 에 적었다. 약 280ms 안에 닫으면 영구 거부로 볼 수 있고 2초 이상 띄웠다 닫거나 허용할 때까지 유지된다. "다음 `launch()` 에서 대화상자가 뜨면 바로잡힌다" 는 문장은 관측과 달라 고쳤다. 코드 변경 없음

#### 추가
- 런타임 권한 명세 — `audio`(API 33+ `READ_MEDIA_AUDIO`)·`storage(write)`(API 32 이하 읽기, 29 이하 쓰기)·`mediaLocation`(29+)·
  `phoneState`·`phoneNumbers`(26+ `READ_PHONE_NUMBERS`, 아래 `READ_PHONE_STATE`)·`callPhone`·`answerPhoneCalls`(26+)·`callLog(read, write)`·
  `processOutgoingCalls`·`addVoicemail`·`useSip`·`acceptHandover`(28+)·`sms(…)`·`accounts`·
  `bodySensors(background, heartRate, oxygenSaturation, skinTemperature)`(33~35 `BODY_SENSORS_BACKGROUND`, 36+ 건강 권한, 백그라운드 두 단계)·
  `nearbyWifiDevices(legacyLocation)`(33+, 아래 위치 권한)·`uwbRanging`(31+)·`ranging`(36+)
- 특수 권한 명세 — `allFilesAccess`(30+)·`usageStats`·`notificationListener`·`accessibilityService`·`notificationPolicy`·`writeSettings`·
  `fullScreenIntent`(34+)·`manageMedia`(31+)
- `SpecialPermissionSpec.missingDeclarations` — 서비스 같은 권한 밖 선언 누락을 요청 직전 확인에 더한다(알림 접근·접근성)
- 시스템 기능 — `SystemRequirement`·`RequirementStatus`(`Satisfied`·`Disabled`·`PermissionRequired`·`Unsupported`)·`UnsupportedReason`·
  `EnableRequest`(`OfIntent`·`OfIntentSender`), 내장 `SystemRequirements`(`bluetoothEnabled`·`locationEnabled`·`nfcEnabled`·`wifiEnabled`·
  `notificationsEnabled`·`hardware(feature, minSdk)`). 요청기가 권한 뒤에 켜기 화면을 열고, 다시 `launch` 하면 꺼진 것만 다시 연다
- `MultiplePermissionsStatus.requirements`·`isReady`·`allRequirementsSatisfied`·`pendingRequirements`·`anyNeedsSettings`, `get(requirement)`
- `PermissionManager.check(requirement)`·`checkAll(specs, requirements, activity)`·`undeclaredPermissions(specs, requirements)`
- 설정 이동 정책 `SettingsPolicy`(`NEVER`·`PROMPT`·`DIRECT`) — `PermissionRequester.launch(settingsPolicy)`, `PermissionRequester.openAppSettings()`
- 화면 없이 부르는 `suspend PermissionManager.request(specs, requirements, settingsPolicy)`·`request(activity, …)` 와 결과 `RequestResult`
  (`Completed`·`NoActivity`). 관리자가 `Application` 의 Activity 수명주기를 따라가 띄울 화면을 찾고, 회전 뒤 새 Activity 에서 결과를 이어받는다
- 요청 줄 — 같은 관리자의 요청기·`request` 가 한 번에 하나씩 돈다(뒤 요청이 빈 결과로 끝나지 않는다)
- 알림 설정 화면 — `notificationSettingsIntent(channelId)`·`openNotificationSettings(context, channelId)`(채널별, 화면이 없으면 앱 상세 설정)
- 빌드 lint 검사(aar 의 lint.jar) — `PlumtPermissionNotDeclared`(오류, 매니페스트 줄을 넣는 고치기)·`PlumtPermissionDeclaredButUnused`(정보)
- 앱 접근 특수 권한 명세 — `autoRevokeExempt`(30+)·`deviceAdmin(admin, explanation)`·`vpn`·`screenCapture(onConsent)`·`pictureInPicture`(26+)·
  `role(AppRole)`(29+ `RoleManager`, SMS·전화는 23~28 옛 기본 앱 화면)·`turnScreenOn`(36+)·`userInitiatedJobs`(34+)·`crossProfile`(30+), 역할 `AppRole`
- Health Connect 런타임 명세 `healthConnect(read, write)`(34+ 플랫폼 권한, 백그라운드 읽기는 다른 읽기 뒤에)
- 기기 상태 요구 조건 — `airplaneModeOff`·`internetAvailable`·`dataSaverOff`(24+)·`powerSaveOff`·`backgroundUnrestricted`(28+)·
  `standbyBucket(worstAllowed)`(28+, 조회만)·`doNotDisturbOff`·`deviceSecure`·`biometricEnrolled(strong)`(29+)·`gpsEnabled`·
  `autoTimeEnabled(includeTimeZone)`·`companionDeviceAssociated`(26+, 상태만)
- `RequirementStatus.Unmet` — 만족하지 않지만 해결 화면이 없는 상태. 요청기는 건너뛴다
- `SpecialPermissionSpec.onSettingsResult(resultCode, data)` — 연 화면의 결과를 명세가 받는다(대신 연 앱 설정 화면의 결과는 넘기지 않는다). 진행 중 흐름의 저장 상태에 함께 남는다
- `RuntimePermissionSpec.missingDeclarations(context)` — 권한 밖 선언 누락을 요청 직전 확인에 더한다(아직 요청할 것이 있는 명세만)
- lint 선언표에 새 명세·요구 조건을 더했다. 문자열 배열 인자(`healthConnect`)를 상수로 읽고, 읽지 못하면 모든 `android.permission.health.*` 선언을 쓰는 것으로 센다

#### 변경
- `createRequester(…)`·`registerPermissionRequester(…)` 에 `requirements` 인자(기본 빈 목록)를 더했다. 명세가 비어도 요구 조건이 있으면 등록된다
- `PermissionRequester`·`PermissionManager` 인터페이스에 함수가 늘었다 — 직접 구현한 가짜는 새 함수를 구현해야 한다
- 요청 직전 선언 확인이 시스템 기능의 선언 권한(`declaredPermissions`)과 특수 권한의 `missingDeclarations` 도 본다
- 런처가 붙기 전에 이전 결과가 오면(재생성 직후 재등록) 붙은 뒤에 흐름을 잇는다
- `RequirementStatus` 에 `Unmet` 이 늘었다 — `when` 으로 모든 경우를 가르는 호출부는 가지를 더해야 한다

### [0.0.1] - 발행 안 함

#### 추가
- 권한 명세 `PermissionSpec` — 런타임 권한 `RuntimePermissionSpec`(권한 목록·허용 정도·단계별 요청), 특수 권한 `SpecialPermissionSpec`(설정 화면)
- 내장 명세 `PermissionSpecs` — 카메라·마이크·알림·활동 인식·위치(정밀/대략)·백그라운드 위치(API 29/30+ 두 단계)·사진·동영상(API 34+ 일부 선택)·연락처·캘린더·블루투스(API 31+ / 레거시)·배터리 최적화 예외·정확한 알람(API 31+)·다른 앱 위에 표시·알 수 없는 앱 설치(API 26+)
- 상태 `PermissionStatus` — `Granted`·`NotApplicable`·`Partial`·`Denied`·`PermanentlyDenied`, 묶음 `MultiplePermissionsStatus`
- 영구 거부 판정 — 요청 전후 근거 안내 값과 권한별 요청 이력 `RequestHistoryStore`(기본 SharedPreferences, 메모리 구현 제공)
- `PermissionManager` — 확인·여러 권한 확인·앱 설정 화면 열기·ActivityResult 기반 요청기 `PermissionRequester`(Activity·Fragment·레지스트리 직접 등록)
- 요청 직전 매니페스트 선언 확인 `ManifestCheck`(기본: 디버그 빌드에서 `IllegalStateException`)
- 외부 실패를 알리는 `PermissionException`
- 부분 접근 종류 `PartialAccess`(`SELECTED_MEDIA`·`APPROXIMATE_LOCATION`·`FOREGROUND_LOCATION_ONLY`·`SOME_PERMISSIONS`) — `Partial.access`, `RuntimePermissionSpec.partialAccess`
- `PermissionEnvironment.targetSdkInt` — 사진·알림·블루투스·활동 인식은 `min(기기 API, targetSdk)` 로 분기
- `RequestHistoryStore.remove` — 권한 하나의 이력을 "요청한 적 없음" 으로 되돌린다
- 진행 중인 요청 흐름의 저장·복원 — `createRequester(caller, …)` 는 `SavedStateRegistryOwner` 면 자동으로, `createRequester(registry, key, …, savedStateRegistry, …)` 는 넘긴 저장소에

#### 판정 보정 (공개 전 리뷰 반영)
- 확인할 때 허용된 권한의 이력은 지우고, 근거 안내가 서 있으면 영구 거부 이력을 지운다 — 설정의 초기화·미사용 앱 자동 초기화 뒤 영구 거부로 굳지 않는다
- 기본 저장소가 설치 시각을 함께 적어, 자동 백업 복원·재설치로 들어온 다른 설치의 이력을 버린다
- 사진 일부 선택·대략적 위치처럼 같은 명세의 다른 권한이 허용된 결과는 거부로 세지 않는다(근거 안내가 사라진 경우만 영구 거부)
- API 30 이상에서 백그라운드 위치를 다른 권한과 한 요청에 묶지 않고 따로 요청한다
- API 26~27 의 다른 앱 위에 표시는 `canDrawOverlays` 가 거짓이어도 AppOps 모드가 허용이면 허용으로 본다

#### 실기기 검증 반영
- 수정: 사진 "추가 선택 안함" 뒤 `isMissingPermanentlyDenied = true` 로 오판하던 문제 — 일부 선택(`READ_MEDIA_VISUAL_USER_SELECTED`)이
  허용된 동안 `READ_MEDIA_IMAGES`·`VIDEO` 는 영구 거부로 보지 않는다(시스템이 요청마다 선택 대화상자를 다시 띄운다)
- 수정: 대략적 위치를 가진 채 정밀 승격을 "대략적인 위치 계속 사용" 으로 거절한 뒤 영구 거부를 놓치던 문제 — 선택지 판정을
  "이 대화상자에서 새로 허용된 권한" 으로 좁혔다. 요청 전부터 허용돼 있던 권한은 선택의 증거가 아니다. 요청 전 허용 상태는 저장 상태에도 남긴다

## permission-compose

### [Unreleased]

### [0.1.0] - 2026-11-08

#### 깨짐
- 좌표가 바뀌었다 — `net.plumt.permission:compose` → `net.plumt.permission:permission-compose`(`permission` 0.1.0 을 `api` 로 가져온다). 태그는 `permission-compose-v<버전>`
- `PermissionFlowStep` 이 `sealed interface` 가 됐다 — `NONE·RATIONALE·SETTINGS·REQUIREMENT` → `None·Rationale·Settings·Requirement`
- `permission` 0.1.0 의 깨짐(`SettingsPolicy` 등)이 그대로 드러난다

#### 바뀜
- 흐름 저장 상태의 단계·정책을 고정 번호로 남긴다 — 번호가 0.0.x 의 순서 번호와 같아 옛 저장 상태도 읽는다
- 상태는 `permission` 의 같은 요청 흐름(요청기) 위에 안내 단계만 더한 어댑터임을 문서에 밝혔다(동작 변화 없음)


### [0.0.2] - 2026-11-07

#### 추가
- `PermissionRequestState.launchFlow(settingsPolicy)` — `NEVER`(설정 안내 없음)·`PROMPT`(기본, 안내 후 이동)·`DIRECT`(곧바로 이동)
- 시스템 기능 — `rememberPermissionState`·`rememberMultiplePermissionsState` 의 `requirements` 인자, `PermissionRequestState.requirementStatuses`,
  흐름 단계 `PermissionFlowStep.REQUIREMENT`(꺼진 기능 켜기 재요청), `PermissionFlowDialogs` 의 `requirement` 슬롯과 기본 `PermissionRequirementDialog`(영어·한국어)

#### 변경
- `PermissionFlowStep` 에 `REQUIREMENT` 가 늘었다 — `when` 으로 모두 가르던 코드는 분기를 더한다
- 흐름 저장 상태에 설정 이동 정책을 함께 남긴다(옛 형식도 읽는다)

### [0.0.1] - 발행 안 함

#### 추가
- `rememberPermissionState`·`rememberMultiplePermissionsState`·`rememberPermissionManager` — ON_RESUME 재확인, `launchRequest`·`launchFlow`(근거 안내 → 요청 → 영구 거부면 설정 안내)
- `PermissionFlowDialogs` 와 교체 가능한 슬롯, 기본 Material3 대화상자 `PermissionRationaleDialog`·`PermissionSettingsDialog`(영어·한국어 문구)

#### 판정 보정 (공개 전 리뷰 반영)
- `launchFlow()` 는 영구 거부여도 설정 안내 대신 먼저 시스템 요청을 보낸다 — 오판이면 대화상자를 허용하거나 2초 이상 띄웠다 닫은 결과로 바로잡힌다
- 요청 결과가 나머지가 영구 거부인 일부 허용(`needsSettings`)이어도 설정 안내를 띄운다
- 흐름이 연 설정 화면에서 돌아오면(ON_RESUME) 재판정한 상태를 `onResult` 로 한 번 준다
- 명세를 `id` 로 비교한다 — `equals` 없는 명세를 재구성마다 만들어도 등록이 다시 만들어지지 않는다
- 흐름 상태(보이던 안내·진행 여부)와 요청기의 진행 상태를 화면 재생성·프로세스 종료 뒤에도 잇는다
