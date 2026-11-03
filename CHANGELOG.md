# Changelog

모듈별로 적는다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [SemVer](https://semver.org/lang/ko/) 이다.

## core

### [Unreleased]

### [0.0.1] - 미발행

#### 추가
- 권한 명세 `PermissionSpec` — 런타임 권한 `RuntimePermissionSpec`(권한 목록·허용 정도·단계별 요청), 특수 권한 `SpecialPermissionSpec`(설정 화면)
- 내장 명세 `PermissionSpecs` — 카메라·마이크·알림·활동 인식·위치(정밀/대략)·백그라운드 위치(API 29/30+ 두 단계)·사진·동영상(API 34+ 일부 선택)·연락처·캘린더·블루투스(API 31+ / 레거시)·배터리 최적화 예외·정확한 알람(API 31+)·다른 앱 위에 표시·알 수 없는 앱 설치(API 26+)
- 상태 `PermissionStatus` — `Granted`·`NotApplicable`·`Partial`·`Denied`·`PermanentlyDenied`, 묶음 `MultiplePermissionsStatus`
- 영구 거부 판정 — 요청 전후 근거 안내 값과 권한별 요청 이력 `RequestHistoryStore`(기본 SharedPreferences, 메모리 구현 제공)
- `PermissionManager` — 확인·여러 권한 확인·앱 설정 화면 열기·ActivityResult 기반 요청기 `PermissionRequester`(Activity·Fragment·레지스트리 직접 등록)
- 요청 직전 매니페스트 선언 확인 `ManifestCheck`(기본: 디버그 빌드에서 `IllegalStateException`)
- 외부 실패를 알리는 `PermissionException`

## compose

### [Unreleased]

### [0.0.1] - 미발행

#### 추가
- `rememberPermissionState`·`rememberMultiplePermissionsState`·`rememberPermissionManager` — ON_RESUME 재확인, `launchRequest`·`launchFlow`(근거 안내 → 요청 → 영구 거부면 설정 안내)
- `PermissionFlowDialogs` 와 교체 가능한 슬롯, 기본 Material3 대화상자 `PermissionRationaleDialog`·`PermissionSettingsDialog`(영어·한국어 문구)
