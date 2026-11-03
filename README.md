# sdk-kotlin-permission

Android 런타임 권한을 **확인 → 근거 안내 → 요청 → 결과 → 영구 거부 판정 → 앱 설정 이동 → 돌아오면 재확인**까지
한 흐름으로 다루는 라이브러리다. Compose 없이 쓰는 `core` 와, 그 위에 Compose 상태·대화상자를 얹은 `compose` 로 나뉜다.

| 모듈 | 좌표 | 내용 |
|---|---|---|
| `core` | `net.plumt.permission:core` | 권한 명세·상태 판정·영구 거부 이력·ActivityResult 기반 요청기 |
| `compose` | `net.plumt.permission:compose` | `rememberPermissionState`·`rememberMultiplePermissionsState`, 교체 가능한 Material3 안내 대화상자(영어·한국어) |

```kotlin
dependencies {
    implementation("net.plumt.permission:compose:0.0.1")   // core 를 함께 가져온다
    // Compose 를 쓰지 않으면
    implementation("net.plumt.permission:core:0.0.1")
}
```

## 왜

- `shouldShowRequestPermissionRationale` 은 "처음 요청하기 전" 과 "다시 묻지 않음" 에서 모두 거짓이라 그것만으로는
  영구 거부를 알 수 없다. 이 라이브러리는 요청 **전후의** 근거 안내 값과 권한별 요청 이력(기본 SharedPreferences)을
  함께 보고 판정하고, 그 판정을 프로세스가 다시 떠도 이어 간다
- API 레벨마다 요청할 권한이 다르다(알림 33+, 사진 33+/34+ 일부 선택, 블루투스 31+, 백그라운드 위치 29/30+ 두 단계 …).
  분기는 명세 안에 있고, 호출부는 상태 하나(`PermissionStatus`)만 본다
- 새 권한은 인터페이스 하나를 구현해 더한다. Hilt 등 DI 프레임워크에 의존하지 않는다

## 상태

| `PermissionStatus` | 뜻 |
|---|---|
| `Granted` | 전부 허용 |
| `NotApplicable(sdkInt)` | 이 API 레벨에는 요청할 런타임 권한이 없다(쓸 수 있는 상태로 다룬다) |
| `Partial(granted, missing, shouldShowRationale, isMissingPermanentlyDenied)` | 일부만 허용 — 대략적 위치만, 사진 일부 선택만, 백그라운드 없는 포그라운드 위치만 |
| `Denied(shouldShowRationale)` | 거부됐지만 대화상자를 다시 띄울 수 있다 |
| `PermanentlyDenied` | "다시 묻지 않음" — 설정 화면에서만 바꿀 수 있다 |

확장 속성 `isUsable`·`isFullyGranted`·`canAskAgain`·`shouldShowRationale`·`needsSettings` 로 분기한다.

## Compose

```kotlin
@Composable
fun CameraScreen() {
    val camera = rememberPermissionState(PermissionSpecs.camera)

    when {
        camera.status.isUsable -> CameraPreview()
        else -> Button(onClick = camera::launchFlow) { Text("카메라 켜기") }
    }
    // 근거 안내·설정 안내 대화상자. 슬롯을 넘기면 원하는 UI 로 바꾼다
    PermissionFlowDialogs(camera)
}
```

- `launchFlow()` 는 근거 안내가 권해지면 안내 → 요청, 영구 거부면 설정 안내 → 앱 설정 화면을 연다. 요청 결과가 영구 거부면 설정 안내를 띄운다
- `launchRequest()` 는 안내 없이 시스템 요청만 보낸다. 안내를 직접 짜려면 이것과 `status` 를 쓴다
- 화면이 다시 보일 때(ON_RESUME)마다 상태를 다시 판정한다 — 설정 화면에서 돌아온 결과가 여기서 반영된다
- 여러 권한은 `rememberMultiplePermissionsState(listOf(...))` — 런타임 권한은 한 번의 시스템 요청으로 묻는다
- 기본 대화상자 `PermissionRationaleDialog`·`PermissionSettingsDialog` 는 문구를 인자로 바꿀 수 있고, 기본 문구는 영어·한국어다

```kotlin
PermissionFlowDialogs(
    state = camera,
    rationale = { onConfirm, onDismiss -> MyRationaleSheet(onConfirm, onDismiss) },
    settings = { onConfirm, onDismiss -> PermissionSettingsDialog(onConfirm, onDismiss, text = "카메라를 쓰려면 설정에서 허용해 주세요") },
)
```

## Compose 없이(Activity·Fragment)

```kotlin
class MainActivity : ComponentActivity() {
    private val permissions by lazy { PermissionManager.create(this) }
    private lateinit var requester: PermissionRequester

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // STARTED 전에 등록한다(registerForActivityResult 규칙)
        requester = registerPermissionRequester(permissions, listOf(PermissionSpecs.camera)) { result ->
            when (result[PermissionSpecs.camera]) {
                PermissionStatus.PermanentlyDenied -> permissions.openAppSettings(this)
                else -> render(result)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render(permissions.checkAll(listOf(PermissionSpecs.camera), this))   // 설정에서 돌아온 경우
    }

    private fun onCameraClick() {
        if (permissions.check(PermissionSpecs.camera, this).shouldShowRationale) showRationale { requester.launch() }
        else requester.launch()
    }
}
```

Fragment 는 `permissions.createRequester(caller = this, activity = { activity }, specs, onResult)` 로 등록한다.
수명주기 없이 레지스트리에 직접 붙이려면 `createRequester(registry, key, …)` 를 쓰고 다 쓰면 `unregister()` 한다.

## 내장 명세

`PermissionSpecs` 가 만든다. **이 라이브러리는 매니페스트에 권한을 선언하지 않는다** — 앱이 쓰는 권한만 앱이 선언한다.

| 명세 | API 레벨별 요청 | 앱이 선언할 권한 |
|---|---|---|
| `camera` | 언제나 | `CAMERA` |
| `microphone` | 언제나 | `RECORD_AUDIO` |
| `notifications` | 33+ (아래는 `NotApplicable`) | `POST_NOTIFICATIONS` |
| `activityRecognition` | 29+ (아래는 `NotApplicable`) | `ACTIVITY_RECOGNITION` |
| `location(precise)` | FINE+COARSE(정밀) 또는 COARSE. 정밀을 원하는데 대략만 허용이면 `Partial` | `ACCESS_FINE_LOCATION`·`ACCESS_COARSE_LOCATION` |
| `backgroundLocation(precise)` | 29 미만 포그라운드만, 29 한 번에, **30+ 두 단계**(포그라운드 → `Partial` → 두 번째 요청이 "항상 허용" 화면) | 위 + `ACCESS_BACKGROUND_LOCATION` |
| `media(images, video)` | 32 이하 `READ_EXTERNAL_STORAGE`, 33 `READ_MEDIA_*`, 34+ 선언했으면 `READ_MEDIA_VISUAL_USER_SELECTED` 도(일부 선택은 `Partial`) | 고른 `READ_MEDIA_IMAGES`/`VIDEO`, `READ_EXTERNAL_STORAGE`(`maxSdkVersion="32"`), 선택 `READ_MEDIA_VISUAL_USER_SELECTED` |
| `contacts(read, write)` | 언제나 | `READ_CONTACTS`·`WRITE_CONTACTS` |
| `calendar(read, write)` | 언제나 | `READ_CALENDAR`·`WRITE_CALENDAR` |
| `bluetooth(scan, connect, advertise)` | 31+ `BLUETOOTH_SCAN`/`CONNECT`/`ADVERTISE`. 30 이하는 검색만 위치 권한(29~30 FINE, 아래 COARSE), 연결·광고는 `NotApplicable` | 31+ 고른 것, 30 이하 `BLUETOOTH`·`BLUETOOTH_ADMIN`(`maxSdkVersion="30"`) |
| `ignoreBatteryOptimizations` (특수) | 요청하면 예외 요청 설정 화면 | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — **Google Play 정책상 허용 용도가 제한된다** |
| `exactAlarm` (특수) | 31+ "알람 및 리마인더" 설정 화면, 31 미만 `NotApplicable` | `SCHEDULE_EXACT_ALARM`. 알람·캘린더 앱은 `USE_EXACT_ALARM`(33+, 설치 시 부여)을 함께 선언할 수 있고 그러면 늘 허용 — Play 정책상 그 용도만 |
| `overlay` (특수) | "다른 앱 위에 표시" 설정 화면 | `SYSTEM_ALERT_WINDOW` |
| `installUnknownApps` (특수) | 26+ 이 앱의 "알 수 없는 앱 설치" 설정 화면, 26 미만 `NotApplicable`(기기 전체 설정) | `REQUEST_INSTALL_PACKAGES` — **Google Play 정책상 허용 용도가 제한된다** |

**선언 확인**: 선언하지 않은 권한을 요청하면 시스템은 대화상자 없이 거부하고 영구 거부처럼 보인다. 그래서 요청 직전에
앱 매니페스트를 확인해, 디버그 가능한 빌드에서는 빠진 권한을 이름과 함께 `IllegalStateException` 으로 알린다.
`PermissionConfig(manifestCheck = ManifestCheck.ALWAYS_THROW | OFF)` 로 바꾼다. 릴리스 기본값은 확인하지 않는다.

## 확장

새 권한은 인터페이스 하나를 구현한다.

```kotlin
object BodySensors : RuntimePermissionSpec {
    override val id = "body_sensors"
    override fun permissions(environment: PermissionEnvironment) = listOf(Manifest.permission.BODY_SENSORS)
}

object ManageAllFiles : SpecialPermissionSpec {
    override val id = "manage_all_files"
    override val manifestPermission = Manifest.permission.MANAGE_EXTERNAL_STORAGE
    override fun isApplicable(environment: PermissionEnvironment) = environment.sdkInt >= 30
    override fun isGranted(context: Context) = Environment.isExternalStorageManager()
    override fun settingsIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
}
```

| 확장 지점 | 바꾸는 것 |
|---|---|
| `RuntimePermissionSpec` | 요청할 권한(`permissions`), 허용 정도 판정(`evaluate`), 단계별 요청(`nextRequest`) |
| `SpecialPermissionSpec` | 설정 화면으로 켜는 특수 권한 |
| `RequestHistoryStore` | 영구 거부 이력 저장소(기본 SharedPreferences, `inMemory()` 제공) |
| `PermissionConfig.manifestCheck` | 매니페스트 선언 확인 방식 |
| `PermissionFlowDialogs` 의 슬롯 | 근거 안내·설정 안내 UI |
| `PermissionManager` (인터페이스) | 판정·요청 전체 — 테스트에서 가짜로 바꾼다 |

## 알려진 한계

- **대화상자를 띄우지 않고 닫은 경우**: Android 11+ 에서 사용자가 대화상자를 바깥 터치·뒤로 가기로 닫으면 거부로 오고
  근거 안내 값도 바뀌지 않는다. 처음 요청이면 `Denied(false)` 로 두지만, 같은 일이 **연달아 두 번** 일어나면 영구 거부로 본다.
  이때도 `launch()` 는 시스템 요청을 다시 보내므로 대화상자가 뜨면 판정이 바로잡힌다
- **설정에서 권한을 초기화한 경우**: 영구 거부 이력은 다음 요청 결과가 올 때까지 남는다. 그래서 `launch()` 는 영구 거부여도
  시스템 요청을 보낸다. 설정 화면으로 보낼지는 호출부(또는 `launchFlow()`)가 정한다
- **동시에 두 요청**: 시스템은 한 번에 한 요청만 받는다. 뒤 요청은 빈 결과로 끝나고, 라이브러리는 이것을 취소로 보아 이력을 건드리지 않는다.
  같은 요청기의 `launch()` 는 진행 중이면 `false` 를 돌려준다
- **API 33 미만 알림**: `NotApplicable` 이지만 사용자가 설정에서 앱 알림을 껐을 수 있다. `NotificationManagerCompat.areNotificationsEnabled()` 로 따로 본다

## 위협 모델·개인정보

- 저장하는 것은 권한 이름과 불리언(요청함·영구 거부)뿐이다. 앱의 비공개 SharedPreferences(`net.plumt.permission.history`)에 둔다
- 로그를 남기지 않는다. 네트워크를 쓰지 않는다
- 라이브러리 매니페스트는 비어 있다 — 의존만으로 앱 권한 목록이 늘지 않는다

## 최소 요구사항

- minSdk 23, compileSdk 36, Java 11 바이트코드
- 런타임 의존: `androidx.activity`(공개 API 에 `ActivityResultCaller`·`ActivityResultRegistry`·`ComponentActivity` 가 드러나 `api` 로 내보낸다), `androidx.core`.
  `compose` 는 추가로 Compose runtime·ui·material3(BOM 2025.02.00), `activity-compose`, `lifecycle-runtime-compose`
- 모든 함수는 메인 스레드에서 부른다. 판정은 바인더 호출 몇 번이라 메인 스레드에서 불러도 된다

오류와 실패 신호는 [ERRORS.md](ERRORS.md), 변경 이력은 [CHANGELOG.md](CHANGELOG.md).

## 버전

모듈마다 [SemVer](https://semver.org/lang/ko/) 를 따로 쓰고 태그는 `<모듈>-v<버전>`(예: `core-v0.0.1`, `compose-v0.0.1`)이다.
1.0.0 전까지는 마이너 버전에서도 공개 API 가 바뀔 수 있다. 공개 API 는 `<모듈>/api/*.api` 로 고정하고 CI 가 대조한다.
라인 커버리지 90% 미만이면 `check` 가 실패한다(Kover).

## 라이선스

[Apache License 2.0](LICENSE)
