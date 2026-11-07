# Changelog

모듈별로 적는다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [SemVer](https://semver.org/lang/ko/) 이다.

## core

### [Unreleased]

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

## compose

### [Unreleased]

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
