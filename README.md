# sdk-kotlin-permission

Android 런타임 권한을 **확인 → 근거 안내 → 요청 → 결과 → 영구 거부 판정 → 앱 설정 이동 → 돌아오면 재확인**까지
한 흐름으로 다루는 라이브러리다. Compose 없이 쓰는 `core` 와, 그 위에 Compose 상태·대화상자를 얹은 `compose` 로 나뉜다.

| 모듈 | 좌표 | 내용 |
|---|---|---|
| `core` | `net.plumt.permission:core` | 권한 명세·상태 판정·영구 거부 이력·ActivityResult 기반 요청기 |
| `compose` | `net.plumt.permission:compose` | `rememberPermissionState`·`rememberMultiplePermissionsState`, 교체 가능한 Material3 안내 대화상자(영어·한국어) |

```kotlin
dependencies {
    implementation("net.plumt.permission:compose:0.0.2")   // core 를 함께 가져온다
    // Compose 를 쓰지 않으면
    implementation("net.plumt.permission:core:0.0.2")
}
```

## 왜

- `shouldShowRequestPermissionRationale` 은 "처음 요청하기 전" 과 "다시 묻지 않음" 에서 모두 거짓이라 그것만으로는
  영구 거부를 알 수 없다. 이 라이브러리는 요청 **전후의** 근거 안내 값과 시스템이 대화상자를 실제로 띄웠는지(요청한 화면이 멈춘 뒤 결과까지의 시간)를
  보고 판정해 권한별 이력(기본 SharedPreferences)에 남기고, 그 판정을 프로세스가 다시 떠도 이어 간다. 거부 횟수는 세지 않는다
- API 레벨마다 요청할 권한이 다르다(알림 33+, 사진 33+/34+ 일부 선택, 블루투스 31+, 백그라운드 위치 29/30+ 두 단계 …).
  분기는 명세 안에 있고, 호출부는 상태 하나(`PermissionStatus`)만 본다
- 런타임 권한·특수 권한 거의 전부(전화·SMS·통화 기록·신체 센서·주변 기기·저장소·전체 파일 접근·사용 정보·알림 접근·접근성 …)를
  내장 명세로 담고, minSdk 23~36 의 API 분기를 명세 안에서 끝낸다
- 기기 관리자·VPN·화면 캡처 동의·PiP·기본 앱 역할·Health Connect·자동 회수 예외 같은 **앱 접근**도 같은 흐름으로 요청한다([지원 범위 전체 목록](#지원-범위-전체-목록))
- 블루투스 어댑터·위치 서비스·GPS·NFC·Wi-Fi·인터넷·비행기 모드·절전·데이터 절약·방해 금지·화면 잠금·생체 등록처럼 **권한이 있어도 못 쓰게 만드는 기기 상태**를
  권한과 따로 판정하고, 해결 화면이 있으면 연다
- 결과가 영구 거부일 때 앱 설정으로 보낼지를 호출부가 고른다(`SettingsPolicy`)
- ViewModel·UseCase 에서 Activity 없이 `suspend` 로 요청하고, 동시에 들어온 요청은 하나씩 줄을 세운다
- 라이브러리는 권한을 매니페스트에 선언하지 않는다. 대신 **앱 lint 가 빠진 선언을 빌드 오류로 막고 넣을 줄을 고치기로 준다**(core aar 에 lint.jar 포함)
- 새 권한·기능은 인터페이스 하나를 구현해 더한다. Hilt 등 DI 프레임워크에 의존하지 않는다

## 상태

| `PermissionStatus` | 뜻 |
|---|---|
| `Granted` | 전부 허용 |
| `NotApplicable(sdkInt)` | 이 API 레벨에는 요청할 런타임 권한이 없다(쓸 수 있는 상태로 다룬다) |
| `Partial(granted, missing, shouldShowRationale, isMissingPermanentlyDenied, access)` | 일부만 허용. `access` 가 종류를 가른다 — `SELECTED_MEDIA`(사진 일부 선택), `APPROXIMATE_LOCATION`(대략적 위치만), `FOREGROUND_LOCATION_ONLY`(백그라운드 없는 포그라운드 위치), `SOME_PERMISSIONS`(그 밖) |
| `Denied(shouldShowRationale)` | 거부됐지만 대화상자를 다시 띄울 수 있다 |
| `PermanentlyDenied` | "다시 묻지 않음" — 설정 화면에서만 바꿀 수 있다 |

확장 속성 `isUsable`·`isFullyGranted`·`canAskAgain`·`shouldShowRationale`·`needsSettings` 로 분기한다.

`NotApplicable` 은 "이 API 에서 요청할 권한이 없다" 는 뜻이다. 그 API 에 기능 자체가 없는 경우(UWB 의 API 31 미만, 전화 받기의 API 26 미만 등)도
`NotApplicable` 이므로, 기능 유무는 아래 시스템 기능 상태(`SystemRequirements.hardware(…, minSdk)`)로 따로 본다. 각 명세의 동작은 [API 분기 표](#api-분기-표)에 있다.

시스템 기능 상태는 권한 상태와 섞지 않고 `RequirementStatus` 로 따로 본다.

| `RequirementStatus` | 뜻 |
|---|---|
| `Satisfied` | 켜져 있다(쓸 수 있다) |
| `Disabled` | 꺼져 있다. 켜기 화면을 열 수 있다 |
| `PermissionRequired(permissions)` | 꺼져 있고, 켜기 전에 이 런타임 권한이 필요하다(API 31+ 블루투스 켜기의 `BLUETOOTH_CONNECT` 등). 요청기가 먼저 요청한다 |
| `Unsupported(reason)` | 이 기기에서 쓸 수 없다 — `NO_HARDWARE`·`API_LEVEL` |

`MultiplePermissionsStatus` 는 권한(`statuses`)과 시스템 기능(`requirements`)을 함께 담는다. `allGranted` 는 권한만, `isReady` 는 둘 다 본다.

`PermanentlyDenied` 는 시스템이 직접 알려 주지 않아 요청 이력과 근거 안내 값으로 내린 **추정**이다. 추정이 현실과 어긋나지 않게:

- 확인(`check`)할 때 권한이 허용돼 있으면 그 권한의 이력을 지운다. 나중에 설정의 초기화·미사용 앱 자동 초기화로 권한이 "묻기" 로 돌아가면 처음 상태(`Denied(false)`)로 판정한다
- 근거 안내가 서 있으면(대화상자가 뜬다는 뜻) 영구 거부 이력을 지운다
- 거부된 권한은 요청 전·후 근거 안내 값으로 가른다
  - 요청 뒤 참 → 다시 물을 수 있다(`Denied(true)`)
  - 요청 전 참 → 요청 뒤 거짓 → 두 번째 거부·"다시 묻지 않음" 이라 영구 거부
  - 전·후 모두 거짓 → 대화상자가 떴는지로 가른다. 결과 코드와 내용은 두 경우가 같아, 요청한 화면이 멈춘(`ON_PAUSE`) 뒤 결과가 오기까지의 시간을 본다
    - 280ms 미만 → 대화상자 없이 끝났다. 영구 거부
    - 280ms 이상 → 떴다고 볼 만하다. 이력이 없으면 `Denied(false)` 로 두고 다음 요청에서 대화상자를 다시 띄운다(Android 11+ 는 바깥 탭·뒤로가기로 닫은 것을 거부 횟수에 세지 않는다). **이미 영구 거부로 확인한 권한은 그대로 둔다**
    - 2초 이상 → 사람이 대화상자를 본 것이다. 영구 거부 이력도 지운다
    - 화면이 멈추는 것을 못 봤으면 요청 시점부터 재고 첫 기준은 400ms 다. 화면 재생성·프로세스 재시작으로 이어받은 요청은 관측이 없어 지금까지의 판정을 유지한다
    - 경계에서는 영구 거부 쪽으로 기운다. 진짜 영구 거부를 닫힘으로 잘못 보면 사용자가 눌러도 아무 일이 안 생기고, 반대로 틀리면 틀린 설정 안내가 나갈 수 있지만 `launch()` 는 그대로 대화상자를 띄운다([알려진 한계](#알려진-한계))
- 사용자가 그 대화상자에서 선택지를 새로 고른 결과(사진 일부 선택, 대략적 위치, "앱 사용 중에만")는 거부로 세지 않는다
- 실기기(Android 16) 관측 두 가지를 규칙으로 둔다:
  - 사진·동영상 일부 선택이 허용된 동안 나머지 매체 권한은 영구 거부로 보지 않는다. "추가 선택 안함" 으로 근거 안내가 사라져도 다음 요청에서 시스템이 선택 대화상자를 다시 띄운다
  - 대략적 위치를 이미 가진 채 띄운 정밀 승격 대화상자에서 "대략적인 위치 계속 사용" 을 고르면 정밀 위치는 영구 거부다. 그다음 요청은 화면 없이 끝난다(근거 안내 값은 내내 거짓이라, 이미 가진 권한을 끼워 띄운 승격 대화상자의 거절을 영구 거부로 본다)
- 기본 저장소는 이 설치의 `firstInstallTime` 을 함께 적어, 백업 복원·재설치로 다른 설치의 이력이 들어오면 버린다
- `launch()`·`launchFlow()` 는 영구 거부여도 시스템 요청을 먼저 보낸다. 오판이면 대화상자가 뜨고 허용하거나 2초 이상 띄웠다 닫으면 판정이 고쳐진다(2초 안에 닫으면 남는다 — [알려진 한계](#알려진-한계)). 맞으면 시스템이 화면 없이 곧바로 거부한다

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

- `launchFlow()` 는 근거 안내가 권해지면 안내 → 요청, 아니면 곧바로 요청한다(영구 거부여도 — 위 "추정" 참고). 요청 결과가 설정으로만 바꿀 수 있는 상태(`needsSettings`)면 설정 안내 → 앱 설정 화면을 열고, 돌아오면 재판정한 상태를 `onResult` 로 한 번 준다
- `launchRequest()` 는 안내 없이 시스템 요청만 보낸다. 안내를 직접 짜려면 이것과 `status` 를 쓴다
- 화면이 다시 보일 때(ON_RESUME)마다 상태를 다시 판정한다 — 설정 화면에서 돌아온 결과가 여기서 반영된다
- 여러 권한은 `rememberMultiplePermissionsState(listOf(...))` — 런타임 권한은 한 번의 시스템 요청으로 묻는다(API 30+ 백그라운드 위치만 따로)
- 명세는 `id` 로 같은지 본다. 재구성마다 새로 만든 명세 객체여도 `id` 가 같으면 상태·등록이 유지된다
- 진행 중인 흐름(보이던 안내, 남은 특수 권한 설정 화면, 요청 전 근거 안내 값)은 화면 회전·프로세스 종료 뒤에도 이어진다
- 기본 대화상자 `PermissionRationaleDialog`·`PermissionSettingsDialog` 는 문구를 인자로 바꿀 수 있고, 기본 문구는 영어·한국어다

설정 이동은 `launchFlow(settingsPolicy)` 로 고른다.

```kotlin
camera.launchFlow()                          // = SettingsPolicy.PROMPT: 영구 거부면 설정 안내 대화상자 → 확인하면 앱 설정
camera.launchFlow(SettingsPolicy.NEVER)      // 설정으로 보내지 않는다. 결과만 onResult 로
camera.launchFlow(SettingsPolicy.DIRECT)     // 안내 없이 곧바로 앱 설정 → 돌아오면 재판정 결과
```

꺼진 시스템 기능도 함께 다룬다. 권한을 받은 뒤 켜기 화면을 열고, 사용자가 켜지 않고 돌아오면 `flowStep` 이 `REQUIREMENT` 가 되어
"기능 켜기" 안내를 띄운다. 확인하면 켜기 화면을 다시 연다.

```kotlin
val scan = rememberMultiplePermissionsState(
    specs = listOf(PermissionSpecs.bluetooth(scan = true, connect = true)),
    requirements = listOf(SystemRequirements.bluetoothEnabled, SystemRequirements.locationEnabled),
)
when {
    scan.status.isReady -> StartScan()
    scan.requirementStatuses.values.any { it is RequirementStatus.Unsupported } -> Text("이 기기는 블루투스를 지원하지 않습니다")
    else -> Button(onClick = scan::launchFlow) { Text("블루투스 켜기") }
}
PermissionFlowDialogs(scan)   // 근거 안내·설정 안내·기능 켜기 안내
```

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

`launch(SettingsPolicy.DIRECT)` 면 결과가 영구 거부일 때 요청기가 앱 설정 화면을 열고 돌아온 뒤 콜백한다.
안내를 직접 띄우려면 `launch()`(= `NEVER`) 결과를 보고 안내한 뒤 `requester.openAppSettings()` 를 부른다 — 돌아오면 다시 판정한 상태로 콜백한다.
시스템 기능은 `registerPermissionRequester(permissions, specs, requirements = listOf(SystemRequirements.locationEnabled)) { … }` 로 함께 넘긴다.

Fragment 는 `permissions.createRequester(caller = this, activity = { activity }, specs, onResult = …)` 로 등록한다.
`caller` 가 `SavedStateRegistryOwner`(ComponentActivity·Fragment)면 진행 중인 흐름을 저장 상태에 남겨 재생성·프로세스 종료 뒤에도 잇는다 —
같은 화면에서 등록 순서를 매번 같게 둔다(`registerForActivityResult` 와 같은 조건).
수명주기 없이 레지스트리에 직접 붙이려면 `createRequester(registry, key, activity, specs, savedStateRegistry, onResult)` 를 쓰고 다 쓰면 `unregister()` 한다.
`savedStateRegistry` 를 넘기지 않으면 결과는 이어받지만 남은 특수 권한 설정 화면은 건너뛴다.

## ViewModel·UseCase 에서 (suspend)

```kotlin
class App : Application() {
    // Application 에서 만들어 두면 그때부터 Activity 수명주기를 따라간다
    val permissions by lazy { PermissionManager.create(this) }
}

class SettingsViewModel(private val permissions: PermissionManager) : ViewModel() {
    fun enableAlarm() = viewModelScope.launch {
        when (val result = permissions.request(listOf(PermissionSpecs.notifications), settingsPolicy = SettingsPolicy.NEVER)) {
            is RequestResult.Completed -> if (result.status.allGranted) subscribe() else showDenied(result.status)
            is RequestResult.NoActivity -> retryWhenVisible()   // 띄울 화면이 없었다
        }
    }
}
```

- 이미 다 허용·켜짐이면 시스템 화면 없이 곧바로 `Completed` 다(원본의 "확인 → 없으면 요청" 을 한 번에)
- 요청은 지금 시작된(재개된 것 우선) `ComponentActivity` 에 띄운다. 없으면 `NoActivity(지금 상태)`
- 화면 회전으로 Activity 가 다시 만들어지면 새 Activity 에서 결과를 이어받는다. 흐름 도중 화면이 닫히면(회전이 아닌 종료) `NoActivity`
- 프로세스가 종료되면 코루틴과 함께 사라지고 결과는 버려진다 — 다음에 다시 요청한다
- 취소하면 등록을 풀고 줄에서 빠진다. 메인 스레드로 옮겨 실행하므로 어느 디스패처에서 불러도 된다
- 특정 화면에 띄우려면 `request(activity, specs, …)`

## 요청 줄 (직렬화)

같은 `PermissionManager` 의 요청기·`request` 는 한 줄을 쓴다. 시스템은 권한 대화상자를 한 번에 하나만 받아, 겹친 뒤 요청은 빈 결과로 끝나기 때문이다.
앞 흐름(런타임 요청 → 특수 권한 설정 → 기능 켜기 → 설정 이동)이 끝날 때까지 뒤 흐름은 기다렸다가, 차례가 오면 그 시점 상태로 다시 계획한다
(그사이 허용됐으면 시스템 화면 없이 곧바로 콜백한다). 기다리는 동안에도 `isRequesting` 은 참이고, 등록을 풀거나 화면이 끝나면 줄에서 빠진다.
앱 전체가 한 줄을 쓰려면 관리자를 하나만 만들어 공유한다.

## 시스템 기능 (센서·서비스 켜짐)

| `SystemRequirements` | 판정 | 꺼졌을 때 여는 화면 | 앱이 선언할 권한 |
|---|---|---|---|
| `bluetoothEnabled` | 어댑터 켜짐. 어댑터가 없으면 `Unsupported` | `BluetoothAdapter.ACTION_REQUEST_ENABLE` 대화상자. 31+ 는 먼저 `BLUETOOTH_CONNECT` 요청 | 31+ `BLUETOOTH_CONNECT`, 30 이하 `BLUETOOTH`(`maxSdkVersion="30"`) |
| `locationEnabled` | `LocationManager.isLocationEnabled`(28 미만은 위치 모드) | 시스템 위치 설정 화면(Play 서비스 불필요) | 없음 |
| `nfcEnabled` | `NfcAdapter.isEnabled` | 29+ NFC 패널, 그 아래 NFC 설정 | 없음 |
| `wifiEnabled` | `WifiManager.isWifiEnabled` | 29+ Wi-Fi 패널, 그 아래 Wi-Fi 설정 | `ACCESS_WIFI_STATE` |
| `notificationsEnabled` | 이 앱 알림 켜짐(33 미만에서도 사용자가 끌 수 있다) | 앱 알림 설정. 33+ 는 먼저 `POST_NOTIFICATIONS` 요청 | 33+ `POST_NOTIFICATIONS` |
| `hardware(feature, minSdk)` | `hasSystemFeature`, API 미만이면 `Unsupported(API_LEVEL)` | 없음 | 없음 |
| `airplaneModeOff` | `Settings.Global.AIRPLANE_MODE_ON` 이 0 | 비행기 모드 설정 | 없음 |
| `internetAvailable` | 기본 네트워크가 `NET_CAPABILITY_INTERNET` + `VALIDATED`(인증 전 공용 Wi-Fi 는 아님) | 29+ 인터넷 연결 패널, 그 아래 무선 설정 | `ACCESS_NETWORK_STATE` |
| `dataSaverOff` | 24+ `getRestrictBackgroundStatus` 가 `ENABLED` 가 아님(이 앱 예외면 만족), 24 미만은 늘 만족 | 이 앱의 무제한 데이터 사용 화면 | 없음 |
| `powerSaveOff` | `PowerManager.isPowerSaveMode` 가 거짓 | 절전 모드 설정 | 없음 |
| `backgroundUnrestricted` | 28+ `ActivityManager.isBackgroundRestricted` 가 거짓, 28 미만은 늘 만족 | 앱 상세 설정(배터리 항목) | 없음 |
| `standbyBucket(worstAllowed)` | 28+ 대기 버킷이 기준 이하, 28 미만은 늘 만족. **조회만** — 넘으면 `Unmet` | 없음 | 없음 |
| `doNotDisturbOff` | `getCurrentInterruptionFilter` 가 `ALL`(값을 모르면 막지 않음) | 방해 금지 설정(`ACTION_ZEN_MODE_PRIORITY_SETTINGS`) | 없음 |
| `deviceSecure` | `KeyguardManager.isDeviceSecure` | 화면 잠금 설정(`ACTION_SET_NEW_PASSWORD`) | 없음 |
| `biometricEnrolled(strong)` | 29+ 프레임워크 `BiometricManager`(30+ 는 Class 3/2 선택). 등록 없음 `Disabled`, 하드웨어 없음 `Unsupported`, 일시 불가·보안 업데이트 필요 `Unmet`, 29 미만 `Unsupported(API_LEVEL)` | 30+ 생체 등록 화면, 29 보안 설정 | 29+ `USE_BIOMETRIC` |
| `gpsEnabled` | `isProviderEnabled(GPS_PROVIDER)` — 위치는 켜졌지만 GPS 만 꺼진 경우. GPS 없으면 `Unsupported` | 위치 설정 | 없음 |
| `autoTimeEnabled(includeTimeZone)` | `Settings.Global.AUTO_TIME`(+ `AUTO_TIME_ZONE`) | 날짜·시간 설정 | 없음 |
| `companionDeviceAssociated` | 26+ 이 앱의 컴패니언 연결이 있음. **상태만** — 없으면 `Unmet`(연결은 앱이 `associate()` 로 만든다), 기능 없음 `Unsupported` | 없음 | 없음 |

`RequirementStatus.Unmet` 은 "만족하지 않지만 앱이 열 해결 화면이 없다" 는 뜻이다. `isSatisfied`·`canRequestEnable` 모두 거짓이고,
요청기는 그 요구 조건을 건너뛴다. 해결 화면이 있는 것은 `Disabled` 이고 요청기가 그 화면을 연다.

확인은 `permissions.check(SystemRequirements.locationEnabled)`, 요청은 권한과 함께 요청기·`request`·Compose 상태에 넘긴다.

Play 서비스의 앱 안 위치 대화상자(`SettingsClient`)가 필요하면 `SystemRequirement` 를 직접 구현한다. `enableRequest` 는 비동기로 답할 수 있고
`EnableRequest.OfIntentSender` 를 돌려주면 요청기가 `IntentSender` 로 띄운다(이 라이브러리는 Play 서비스에 의존하지 않는다).

```kotlin
class PlayLocationEnabled(private val request: LocationRequest) : SystemRequirement {
    override val id = "location_enabled.play"
    override fun status(context: Context, environment: PermissionEnvironment) =
        SystemRequirements.locationEnabled.status(context, environment)
    override fun enableRequest(context: Context, onReady: (EnableRequest?) -> Unit) {
        LocationServices.getSettingsClient(context)
            .checkLocationSettings(LocationSettingsRequest.Builder().addLocationRequest(request).build())
            .addOnSuccessListener { onReady(null) }
            .addOnFailureListener { e ->
                val resolvable = e as? ResolvableApiException
                onReady(resolvable?.let { EnableRequest.OfIntentSender(IntentSenderRequest.Builder(it.resolution).build()) })
            }
    }
}
```

## 알림 설정 화면

```kotlin
permissions.openNotificationSettings(context)                     // 앱 알림 설정(26+), 그 아래는 앱 상세 설정
permissions.openNotificationSettings(context, channelId = "news")  // 채널 설정(26+)
val intent = permissions.notificationSettingsIntent("news")        // 직접 띄우려면
```

알림 설정 화면을 바꾼 기기에서 열 수 없으면 앱 상세 설정 화면으로 대신 연다.

## 내장 명세

`PermissionSpecs` 가 만든다. **이 라이브러리는 매니페스트에 권한을 선언하지 않는다**(`core`·`compose` 의 매니페스트는 비어 있다) —
앱이 쓰는 권한만 앱이 선언한다. 빠뜨리면 [선언 검사](#매니페스트-선언-검사)가 빌드와 실행 중에 알린다.

### API 분기 표

"기기 API" 는 `min(기기 API, 앱 targetSdk)` 다 — targetSdk 가 낮으면 시스템이 옛 권한 모델을 쓴다(백그라운드 위치·특수 권한은 기기 API).
`NA` 는 `NotApplicable`(요청할 권한 없음, 쓸 수 있는 상태로 다룸), "기능 없음" 은 그 API 에 기능 자체가 없어 `NA` 이지만 `hardware(…, minSdk)` 로 함께 확인하라는 뜻이다.

| 명세 | 23~25 | 26~28 | 29 | 30 | 31~32 | 33 | 34~35 | 36 |
|---|---|---|---|---|---|---|---|---|
| `camera`·`microphone`·`phoneState`·`callPhone`·`addVoicemail`·`useSip`·`accounts`·`contacts`·`calendar`·`callLog`·`sms`·`processOutgoingCalls` | 요청 | 요청 | 요청 | 요청 | 요청 | 요청 | 요청 | 요청 |
| `notifications` | NA | NA | NA | NA | NA | `POST_NOTIFICATIONS` | 같음 | 같음 |
| `activityRecognition` | NA | NA | `ACTIVITY_RECOGNITION` | 같음 | 같음 | 같음 | 같음 | 같음 |
| `location(precise)` | FINE+COARSE / COARSE | 같음 | 같음 | 같음 | 같음(대략만 허용이면 `Partial`) | 같음 | 같음 | 같음 |
| `backgroundLocation(precise)` | 포그라운드만 | 포그라운드만 | 포그라운드+BACKGROUND 한 번에 | 두 단계 | 두 단계 | 두 단계 | 두 단계 | 두 단계 |
| `media(images, video)` | `READ_EXTERNAL_STORAGE` | 같음 | 같음 | 같음 | 같음 | `READ_MEDIA_IMAGES/VIDEO` | + 선언했으면 `READ_MEDIA_VISUAL_USER_SELECTED`(일부 선택은 `Partial`) | 같음 |
| `audio` | `READ_EXTERNAL_STORAGE` | 같음 | 같음 | 같음 | 같음 | `READ_MEDIA_AUDIO` | 같음 | 같음 |
| `storage(write)` | READ(+WRITE) | READ(+WRITE) | READ(+WRITE) | READ | READ | NA | NA | NA |
| `mediaLocation` | NA | NA | `ACCESS_MEDIA_LOCATION` | 같음 | 같음 | 같음 | 같음 | 같음 |
| `phoneNumbers` | `READ_PHONE_STATE` | `READ_PHONE_NUMBERS` | 같음 | 같음 | 같음 | 같음 | 같음 | 같음 |
| `answerPhoneCalls` | NA(기능 없음) | `ANSWER_PHONE_CALLS` | 같음 | 같음 | 같음 | 같음 | 같음 | 같음 |
| `acceptHandover` | NA(기능 없음) | 28 부터 `ACCEPT_HANDOVER` | 같음 | 같음 | 같음 | 같음 | 같음 | 같음 |
| `bluetooth(scan, connect, advertise)` | 검색만 COARSE, 나머지 NA | 같음 | 검색만 FINE | 같음 | `BLUETOOTH_SCAN/CONNECT/ADVERTISE` | 같음 | 같음 | 같음 |
| `nearbyWifiDevices(legacyLocation)` | COARSE / NA | 같음 | FINE / NA | 같음 | 같음 | `NEARBY_WIFI_DEVICES` | 같음 | 같음 |
| `bodySensors(background, …)` | `BODY_SENSORS` | 같음 | 같음 | 같음 | 같음 | + 백그라운드면 `BODY_SENSORS_BACKGROUND`(두 단계) | 같음 | 고른 건강 권한(`health.READ_HEART_RATE` 등) + `health.READ_HEALTH_DATA_IN_BACKGROUND` |
| `uwbRanging` | NA(기능 없음) | 같음 | 같음 | 같음 | `UWB_RANGING` | 같음 | 같음 | 같음 |
| `ranging` | NA(기술별 권한 사용) | 같음 | 같음 | 같음 | 같음 | 같음 | 같음 | `RANGING` |
| `ignoreBatteryOptimizations`·`overlay`·`usageStats`·`notificationPolicy`·`writeSettings`·`notificationListener`·`accessibilityService` (특수) | 설정 화면 | 같음 | 같음 | 같음 | 같음 | 같음 | 같음 | 같음 |
| `installUnknownApps` (특수) | NA(기기 전체 설정) | 설정 화면 | 같음 | 같음 | 같음 | 같음 | 같음 | 같음 |
| `allFilesAccess` (특수) | NA(`storage(write = true)` 가 맡음) | 같음 | 같음 | 설정 화면 | 같음 | 같음 | 같음 | 같음 |
| `exactAlarm` (특수) | NA(제약 없음) | 같음 | 같음 | 같음 | 설정 화면 | 같음 | 같음 | 같음 |
| `manageMedia` (특수) | NA | 같음 | 같음 | 같음 | 설정 화면 | 같음 | 같음 | 같음 |
| `fullScreenIntent` (특수) | NA(설치 시 부여) | 같음 | 같음 | 같음 | 같음 | 같음 | 설정 화면 | 같음 |
| `deviceAdmin(admin)`·`vpn`·`screenCapture(onConsent)` (특수) | 시스템 화면 | 같음 | 같음 | 같음 | 같음 | 같음 | 같음 | 같음 |
| `pictureInPicture` (특수) | NA(기능 없음) | 설정 화면 | 같음 | 같음 | 같음 | 같음 | 같음 | 같음 |
| `role(SMS·DIALER)` (특수) | 옛 기본 앱 변경 화면 | 같음 | `RoleManager` 요청 대화상자 | 같음 | 같음 | 같음 | 같음 | 같음 |
| `role(BROWSER·HOME·CALL_SCREENING·CALL_REDIRECTION·ASSISTANT)` (특수) | NA | 같음 | `RoleManager` 요청 대화상자(보조는 기본 앱 설정) | 같음 | 같음 | 같음 | 같음 | 같음 |
| `autoRevokeExempt`·`crossProfile` (특수) | NA | 같음 | 같음 | 설정 화면 | 같음 | 같음 | 같음 | 같음 |
| `userInitiatedJobs` (특수) | NA | 같음 | 같음 | 같음 | 같음 | 같음 | 설정 화면 | 같음 |
| `turnScreenOn` (특수, 기기·targetSdk) | NA(제약 없음) | 같음 | 같음 | 같음 | 같음 | 같음 | 같음 | 앱 상세 설정 |
| `healthConnect(read, write)` (기기 API) | NA(별도 앱) | 같음 | 같음 | 같음 | 같음 | 같음 | `health.READ_*`·`WRITE_*` | 같음 |

두 단계 명세(`backgroundLocation`·`bodySensors(background = true)`)는 첫 요청이 포그라운드 권한을, 상태가 `Partial` 이 된 뒤 두 번째 요청이
백그라운드 권한을 묻는다. 요청기는 백그라운드 권한을 다른 권한과 한 요청에 묶지 않는다(시스템이 묶인 요청을 무시한다).

### 앱이 선언할 줄

명세를 쓰는 앱의 `AndroidManifest.xml` 에 넣는다. `maxSdkVersion` 이 붙은 줄은 그 API 까지만 쓰이는 옛 권한이다.
카메라·마이크·위치·블루투스·전화 권한은 Play 가 하드웨어 필수로 여기므로, 없는 기기에도 설치되게 하려면
`<uses-feature android:name="android.hardware.camera" android:required="false" />` 처럼 함께 적는다.

| 명세 | 매니페스트 |
|---|---|
| `camera` | `<uses-permission android:name="android.permission.CAMERA" />` |
| `microphone` | `<uses-permission android:name="android.permission.RECORD_AUDIO" />` |
| `notifications`·`SystemRequirements.notificationsEnabled` | `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />` |
| `activityRecognition` | `<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />` |
| `location()` | `<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />` `<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />` (`precise = false` 면 COARSE 만) |
| `backgroundLocation()` | 위 + `<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />` |
| `media(images, video)` | `READ_MEDIA_IMAGES`·`READ_MEDIA_VIDEO`(고른 것) + `<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />`, 선택 `READ_MEDIA_VISUAL_USER_SELECTED` |
| `audio` | `<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />` + `READ_EXTERNAL_STORAGE`(`maxSdkVersion="32"`) |
| `storage(write)` | `READ_EXTERNAL_STORAGE`(`maxSdkVersion="32"`), 쓰기면 `<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="29" />` |
| `mediaLocation` | `<uses-permission android:name="android.permission.ACCESS_MEDIA_LOCATION" />` |
| `contacts`·`calendar`·`callLog` | `READ_/WRITE_CONTACTS`·`READ_/WRITE_CALENDAR`·`READ_/WRITE_CALL_LOG` 가운데 고른 것 |
| `phoneState` | `<uses-permission android:name="android.permission.READ_PHONE_STATE" />` |
| `phoneNumbers` | `<uses-permission android:name="android.permission.READ_PHONE_NUMBERS" />` `<uses-permission android:name="android.permission.READ_PHONE_STATE" android:maxSdkVersion="25" />` |
| `callPhone`·`answerPhoneCalls`·`acceptHandover`·`useSip`·`processOutgoingCalls` | `CALL_PHONE`·`ANSWER_PHONE_CALLS`·`ACCEPT_HANDOVER`·`USE_SIP`·`PROCESS_OUTGOING_CALLS` |
| `addVoicemail` | `<uses-permission android:name="com.android.voicemail.permission.ADD_VOICEMAIL" />` |
| `sms(…)` | `SEND_SMS`·`RECEIVE_SMS`·`READ_SMS`·`RECEIVE_MMS`·`RECEIVE_WAP_PUSH` 가운데 고른 것 |
| `accounts` | `<uses-permission android:name="android.permission.GET_ACCOUNTS" />` |
| `bluetooth(scan, connect, advertise)` | 고른 `BLUETOOTH_SCAN`·`BLUETOOTH_CONNECT`·`BLUETOOTH_ADVERTISE` + `<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />`, 검색·광고면 `BLUETOOTH_ADMIN`(`maxSdkVersion="30"`), 검색이면 `ACCESS_FINE_LOCATION`·`ACCESS_COARSE_LOCATION`(`maxSdkVersion="30"`). 검색 결과로 위치를 얻지 않으면 `BLUETOOTH_SCAN` 에 `android:usesPermissionFlags="neverForLocation"` |
| `SystemRequirements.bluetoothEnabled` | `BLUETOOTH_CONNECT` + `BLUETOOTH`(`maxSdkVersion="30"`) |
| `nearbyWifiDevices()` | `<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />` + `ACCESS_FINE_LOCATION`·`ACCESS_COARSE_LOCATION`(`maxSdkVersion="32"`) |
| `nearbyWifiDevices(legacyLocation = false)` | `<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation" />` |
| `bodySensors(…)` | `BODY_SENSORS` + 고른 `android.permission.health.READ_HEART_RATE`·`READ_OXYGEN_SATURATION`·`READ_SKIN_TEMPERATURE`, 백그라운드면 `BODY_SENSORS_BACKGROUND`·`android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND` |
| `uwbRanging`·`ranging` | `UWB_RANGING`·`RANGING` |
| `SystemRequirements.wifiEnabled` | `<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />` |
| `ignoreBatteryOptimizations` | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| `exactAlarm` | `SCHEDULE_EXACT_ALARM`. 알람·캘린더가 핵심인 앱은 `SCHEDULE_EXACT_ALARM android:maxSdkVersion="32"` + `USE_EXACT_ALARM`(33+, 설치 시 부여 — 늘 허용) |
| `overlay` | `SYSTEM_ALERT_WINDOW` |
| `installUnknownApps` | `REQUEST_INSTALL_PACKAGES` |
| `allFilesAccess` | `MANAGE_EXTERNAL_STORAGE` |
| `usageStats` | `<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions" />` |
| `notificationPolicy` | `ACCESS_NOTIFICATION_POLICY` |
| `writeSettings` | `<uses-permission android:name="android.permission.WRITE_SETTINGS" tools:ignore="ProtectedPermissions" />` |
| `fullScreenIntent` | `USE_FULL_SCREEN_INTENT` |
| `manageMedia` | `MANAGE_MEDIA` |
| `notificationListener` | 권한 없음. `NotificationListenerService` 를 `android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"` 로 보호해 선언 |
| `accessibilityService` | 권한 없음. `AccessibilityService` 를 `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"` 로 보호해 선언 |
| `deviceAdmin(admin)` | 권한 없음. `DeviceAdminReceiver` 를 `android:permission="android.permission.BIND_DEVICE_ADMIN"` 으로 보호하고 `<meta-data android:name="android.app.device_admin" android:resource="@xml/…" />` 와 `android.app.action.DEVICE_ADMIN_ENABLED` 필터를 단다 |
| `vpn` | 권한 없음. `VpnService` 를 `android:permission="android.permission.BIND_VPN_SERVICE"` 로 보호하고 `android.net.VpnService` 필터를 단다 |
| `screenCapture(onConsent)` | 동의에는 없음. 캡처하는 포그라운드 서비스(`foregroundServiceType="mediaProjection"`)에 `FOREGROUND_SERVICE`, 34+ `FOREGROUND_SERVICE_MEDIA_PROJECTION` |
| `pictureInPicture` | 권한 없음. Activity 에 `android:supportsPictureInPicture="true"` |
| `role(…)` | 권한 없음. 역할별 컴포넌트(SMS 앱의 `SMS_DELIVER` 수신기, 전화 앱의 `InCallService`, 홈 앱의 `HOME` 필터 등) |
| `autoRevokeExempt` | 없음 |
| `healthConnect(read, write)` | 요청하는 `android.permission.health.READ_<종류>`·`WRITE_<종류>` 전부 + `VIEW_PERMISSION_USAGE`·`android.intent.category.HEALTH_PERMISSIONS` 필터를 단 `<activity-alias android:permission="android.permission.START_VIEW_PERMISSION_USAGE">`(근거 화면) |
| `turnScreenOn` | `TURN_SCREEN_ON` |
| `userInitiatedJobs` | `RUN_USER_INITIATED_JOBS` |
| `crossProfile` | `INTERACT_ACROSS_PROFILES` |
| `SystemRequirements.internetAvailable` | `ACCESS_NETWORK_STATE` |
| `SystemRequirements.biometricEnrolled()` | `USE_BIOMETRIC` |

다른 라이브러리가 같은 권한을 다른 `maxSdkVersion` 으로 선언했으면 결과는 매니페스트 병합 규칙이 정한다. 병합된 상한이 명세가 쓰는 API 보다
낮으면 그 위 API 에서 권한이 빠지므로, 병합 매니페스트를 확인하고 앱에서 `tools:replace="android:maxSdkVersion"`(또는 `tools:remove`)로 정한다 —
아래 lint 검사는 병합된 상한으로 판정해 이런 경우도 오류로 알린다.
앱이 `tools:node="remove"` 로 뺀 권한은 아래 검사가 빠진 선언으로 본다.

### Google Play 정책 신고 대상

다음 권한은 Play Console 의 권한 신고서·허용 용도 심사를 거친다. 기능에 꼭 필요할 때만 선언한다.

- 백그라운드 위치(`ACCESS_BACKGROUND_LOCATION`) — 위치 권한 신고서와 영상
- 전체 파일 접근(`MANAGE_EXTERNAL_STORAGE`) — 파일 관리자 등 허용 용도만
- SMS·통화 기록(`SEND_SMS`·`READ_SMS`·`RECEIVE_SMS`·`READ_CALL_LOG` 등) — 기본 SMS·전화 앱 등 허용 용도만
- 사진·동영상(`READ_MEDIA_IMAGES`·`READ_MEDIA_VIDEO`) — 사진 선택만이면 시스템 사진 선택기를 쓴다
- 연락처(`READ_CONTACTS`)·건강(`android.permission.health.*`) — 개인정보처리방침·데이터 보안 신고
- 정확한 알람(`USE_EXACT_ALARM`)·전체 화면 알림(`USE_FULL_SCREEN_INTENT`)·배터리 최적화 예외·알 수 없는 앱 설치·접근성 API — 허용 용도만

## 매니페스트 선언 검사

**빌드 중 — lint.** `core` aar 에 lint.jar 가 들어 있어 의존만으로 앱 lint 에 두 검사가 붙는다(따로 받을 아티팩트가 없다).

| 검사 ID | 심각도 | 내용 |
|---|---|---|
| `PlumtPermissionNotDeclared` | 오류 | 코드가 쓰는 명세(`PermissionSpecs.camera`, `PermissionSpecs.location(precise = false)` 처럼 상수 인자까지 읽는다)가 요청하는 권한이 **병합된** 앱 매니페스트에 없다. 고치기가 `maxSdkVersion`·`usesPermissionFlags`·`tools:ignore` 까지 넣은 줄을 앱 매니페스트에 더한다. 앱 minSdk 보다 상한이 낮은 줄은 요구하지 않는다 |
| `PlumtPermissionDeclaredButUnused` | 정보 | 앱 매니페스트가 선언한 권한 가운데 이 표가 아는 것을 어느 명세도 쓰지 않는다. 다른 라이브러리·직접 요청 때문일 수 있어 정보 수준이다 |

여러 모듈 앱(기능 모듈이 명세를 쓰고 앱 모듈이 매니페스트를 가진다)도 앱 모듈의 lint 보고 단계에서 함께 판정한다. 라이브러리 모듈만 검사하면 판정하지 않는다.
심각도는 앱이 `lint { warning += "PlumtPermissionNotDeclared" }`·`lint.xml` 로 바꾸고, 한 자리만 끄려면 `@Suppress("PlumtPermissionNotDeclared")` 를 쓴다.
lint API 는 AGP 9.0(lint 32.0)에 맞춰 만들었고, 더 높은 AGP 에서도 lint 가 하위 호환으로 읽는다.

```
SampleActivity.kt:21: Error: PermissionSpecs.camera 이 요청하는 권한이 병합된 앱 매니페스트에 없습니다: android.permission.CAMERA.
선언하지 않은 권한은 대화상자 없이 곧바로 거부되어 영구 거부처럼 보입니다. 앱 AndroidManifest.xml 에 다음을 넣으세요:
<uses-permission android:name="android.permission.CAMERA" /> [PlumtPermissionNotDeclared from net.plumt.permission:core]
            listOf(PermissionSpecs.camera),
                   ~~~~~~~~~~~~~~~~~~~~~~
```

**실행 중 — 요청 직전.** 선언하지 않은 권한을 요청하면 시스템은 대화상자 없이 거부하고 영구 거부처럼 보인다. 그래서 요청 직전에
`PackageInfo.requestedPermissions` 를 확인해(이 기기에서 `maxSdkVersion` 이 지난 선언은 빠진다) 디버그 가능한 빌드에서는 빠진 권한과
넣을 줄을 `IllegalStateException` 으로 알린다. 알림 접근·접근성은 서비스 선언이 없는 것도 알린다.
`PermissionConfig(manifestCheck = ManifestCheck.ALWAYS_THROW | OFF)` 로 바꾼다. 릴리스 기본값은 확인하지 않는다.
시작 시점이나 계측 테스트에서 한꺼번에 보려면 `permissions.undeclaredPermissions(specs, requirements)` 가 빠진 권한 집합을 준다.

**매니페스트 무오염.** `core`·`compose` 는 `<uses-permission>`·`<queries>`·컴포넌트를 하나도 넣지 않는다. 레포의 확인용 앱(`sample`, 발행 안 함)이
빌드마다 병합 매니페스트를 검사해, 앱이 선언한 권한 밖에 늘어난 것이 있으면 빌드를 실패시킨다. 남는 항목은 모든 androidx 앱에 들어가는
`<앱 id>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`(androidx.core)·`androidx.startup.InitializationProvider`·`androidx.profileinstaller.ProfileInstallReceiver` 뿐이다.

## 확장

새 권한은 인터페이스 하나를 구현한다(앱 전용 권한, Android 13 이하의 Health Connect 앱 권한 등).

```kotlin
object MyAppPermission : RuntimePermissionSpec {
    override val id = "my_app.feature"
    override fun permissions(environment: PermissionEnvironment) = listOf("com.example.permission.FEATURE")
}

/** 결과 데이터가 필요한 동의 화면 — 돌아온 결과를 onSettingsResult 로 받는다 */
class ScreenCaptureWithConfig(private val onConsent: (Int, Intent) -> Unit) : SpecialPermissionSpec {
    override val id = "screen_capture.single_app"
    override val manifestPermission: String? = null
    override fun isApplicable(environment: PermissionEnvironment) = environment.sdkInt >= 34
    override fun isGranted(context: Context) = false
    override fun settingsIntent(context: Context) = context.getSystemService(MediaProjectionManager::class.java)
        .createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
    override fun onSettingsResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) onConsent(resultCode, data)
    }
}
```

| 확장 지점 | 바꾸는 것 |
|---|---|
| `RuntimePermissionSpec` | 요청할 권한(`permissions`), 허용 정도 판정(`evaluate`), 단계별 요청(`nextRequest`), 부분 접근 종류(`partialAccess`), 권한 밖 선언 누락(`missingDeclarations`) |
| `SpecialPermissionSpec` | 설정 화면으로 켜는 특수 권한. `missingDeclarations` 로 서비스 같은 선언 누락을 알리고, `onSettingsResult` 로 연 화면의 결과를 받는다 |
| `SystemRequirement` | 꺼져 있으면 켜야 하는 시스템 기능. `enableRequest` 는 비동기·`IntentSender` 를 지원한다 |
| `SettingsPolicy` | 영구 거부일 때 설정 이동 방식(`NEVER`·`PROMPT`·`DIRECT`) |
| `RequestHistoryStore` | 영구 거부 이력 저장소(기본 SharedPreferences, `inMemory()` 제공). 직접 구현하면 `remove` 로 "요청한 적 없음" 으로 되돌릴 수 있어야 한다 |
| `PermissionConfig.manifestCheck` | 매니페스트 선언 확인 방식 |
| `PermissionFlowDialogs` 의 슬롯 | 근거 안내·설정 안내·기능 켜기 안내 UI |
| `PermissionManager` (인터페이스) | 판정·요청 전체 — 테스트에서 가짜로 바꾼다 |

## 지원 범위 전체 목록

**런타임 권한(`PermissionSpecs`)** — `camera`·`microphone`·`notifications`(33+)·`activityRecognition`(29+)·`location(precise)`·
`backgroundLocation(precise)`·`media(images, video)`·`audio`·`storage(write)`·`mediaLocation`(29+)·`contacts(read, write)`·`calendar(read, write)`·
`phoneState`·`phoneNumbers`·`callPhone`·`answerPhoneCalls`(26+)·`callLog(read, write)`·`processOutgoingCalls`·`addVoicemail`·`useSip`·
`acceptHandover`(28+)·`sms(…)`·`accounts`·`bluetooth(scan, connect, advertise)`·`bodySensors(…)`·`nearbyWifiDevices(legacyLocation)`·
`uwbRanging`(31+)·`ranging`(36+)·`healthConnect(read, write)`(34+)

**특수 권한·앱 접근(`PermissionSpecs`, 설정 → 특수 앱 접근·기본 앱)** — 아래는 Android 16(API 36)의 "특수 앱 접근" 목록과 맞춘 것이다.

| 설정 항목 | 명세 | API |
|---|---|---|
| 모든 파일 액세스 | `allFilesAccess` | 30+ |
| 기기 관리자 앱 | `deviceAdmin(admin, explanation)` | 전부 |
| 다른 앱 위에 표시 | `overlay` | 전부 |
| 방해 금지 모드 액세스 | `notificationPolicy` | 전부 |
| 미디어 관리 앱 | `manageMedia` | 31+ |
| 시스템 설정 수정 | `writeSettings` | 전부 |
| 기기 및 앱 알림(알림 리스너) | `notificationListener` | 전부 |
| PIP 모드 | `pictureInPicture` | 26+ |
| 사용 정보 접근 | `usageStats` | 전부 |
| 출처를 알 수 없는 앱 설치 | `installUnknownApps` | 26+ |
| 알람 및 리마인더 | `exactAlarm` | 31+ |
| 배터리 최적화 | `ignoreBatteryOptimizations` | 전부 |
| 전체 화면 알림 | `fullScreenIntent` | 34+ |
| 오래 걸리는 백그라운드 작업 | `userInitiatedJobs` | 34+ |
| 화면 켜기 | `turnScreenOn` | 36+ |
| 연결된 업무용 앱 및 개인 앱 | `crossProfile` | 30+ |
| 무제한 데이터 | `SystemRequirements.dataSaverOff`(데이터 절약 중 이 앱 예외) | 24+ |
| 접근성(설정 → 접근성) | `accessibilityService` | 전부 |
| 앱 사용 중지 시 권한 삭제·활동 일시중지(앱 정보) | `autoRevokeExempt` | 30+ |
| 기본 앱(SMS·전화·브라우저·홈·발신자 확인·발신 재연결·디지털 어시스턴트) | `role(AppRole.*)` | 29+, SMS·전화는 23+ |
| VPN 연결 동의 | `vpn` | 전부 |
| 화면 캡처 동의 | `screenCapture(onConsent)` | 전부 |

**시스템 상태(`SystemRequirements`)** — `bluetoothEnabled`·`locationEnabled`·`gpsEnabled`·`nfcEnabled`·`wifiEnabled`·`notificationsEnabled`·
`hardware(feature, minSdk)`·`airplaneModeOff`·`internetAvailable`·`dataSaverOff`(24+)·`powerSaveOff`·`backgroundUnrestricted`(28+)·
`standbyBucket(worstAllowed)`(28+, 조회만)·`doNotDisturbOff`·`deviceSecure`·`biometricEnrolled(strong)`(29+)·`autoTimeEnabled(includeTimeZone)`·
`companionDeviceAssociated`(26+, 상태만)

## 다루지 않는 것

| 무엇 | 이유 |
|---|---|
| 서명·시스템·특권 권한(`signature`·`privileged`·`installer` 보호 수준 — `MANAGE_DEVICE_POLICY_*`·`WRITE_SECURE_SETTINGS`·`READ_PRIVILEGED_PHONE_STATE`·`INTERACT_ACROSS_USERS` 등) | 일반 앱은 받을 수 없다. 요청 화면도 없다 |
| 설치 시 부여되는 일반 권한(`INTERNET`·`VIBRATE`·`FOREGROUND_SERVICE*`·`WAKE_LOCK` 등) | 요청 단계가 없다 — 선언만 하면 된다 |
| 카메라·마이크 개인정보 보호 토글(`SensorPrivacyManager`, 31+) | 전역 차단 상태를 읽는 함수(`isSensorPrivacyEnabled`)가 시스템 API 라 공개 SDK 로 읽을 수 없다(공개 API 는 `supportsSensorToggle` 뿐). 차단된 채 카메라·마이크를 쓰면 시스템이 해제 대화상자를 스스로 띄운다 |
| 프리미엄 SMS 액세스·금융 앱 SMS 액세스 | 상태를 읽는 공개 API 가 없다(시스템이 보낼 때 묻는다) |
| VR 도우미 서비스 | 플랫폼이 사실상 폐기했고 켜진 목록을 읽는 공개 API 가 없다 |
| Wi-Fi 제어(`CHANGE_WIFI_STATE` 앱 작업) | 앱 작업 이름과 설정 화면이 공개되지 않았다 |
| 미디어 라우팅 제어(`MEDIA_ROUTING_CONTROL`, 35+) | 컴패니언 기기 프로필을 가진 앱만 받고 확인 API 가 특권이다 |
| 컴패니언 기기 연결 만들기(`associate()`) | 기기 검색 조건·콜백이 앱마다 달라 범위가 크다 — 연결 여부만 `companionDeviceAssociated` 로 알린다 |
| Android 13 이하의 Health Connect, androidx.biometric | 외부 라이브러리·별도 앱이 필요하다. 새 외부 의존을 넣지 않는다 |
| Play 서비스가 API 23~29 에 넣는 미사용 앱 권한 회수 | Play 서비스 API(`PackageManagerCompat.getUnusedAppRestrictionsStatus`)의 몫이다 |
| 역할(`AppRole`)의 `EMERGENCY`·`NOTES`·`WALLET` | 시스템 앱 전용이거나(`EMERGENCY`) 최근 API 에만 있어(`NOTES` 34+, `WALLET` 35+) 이번 범위에서 뺐다 — `SpecialPermissionSpec` 으로 직접 구현할 수 있다 |

## 알려진 한계

- **영구 거부는 대화상자가 떠 있던 시간으로 추정한다**: 영구 거부를 알려 주는 공식 API 가 없다. Android 11+ 는 대화상자를 바깥 탭·뒤로가기로 닫아도 거부 횟수에
  세지 않고 근거 안내 값도 그대로라, 이 경우 영구 거부와는 시간으로만 가른다. 그래서 틀릴 수 있는 경우가 남는다
  - 대화상자를 약 280ms 안에(화면이 멈춘 뒤 결과까지) 닫으면 시스템이 고정하지 않았어도 영구 거부로 볼 수 있다. 앱을 막 띄운 직후 요청하면 대화상자가 늦게 그려져 더 쉽게 일어난다
  - 그렇게 남은 판정은 2초 이상 띄웠다 닫거나 허용할 때까지(또는 근거 안내가 설 때까지) 유지된다. 다음 `launch()` 에서 대화상자가 떠도 2초 안에 닫으면 바로잡히지 않고, 그동안 설정 안내는 틀린 안내일 수 있다. 설정 초기화 뒤 다시 뜬 대화상자도 같다
  - 그래서 **설정 안내는 사용자가 버튼을 눌렀을 때만 띄운다.** 화면에 들어오자마자 `launchFlow()`·`SettingsPolicy.DIRECT` 를 부르면 빠르게 닫은 사용자를 설정 화면으로 보낼 수 있다. 자동으로 요청한다면 `SettingsPolicy.NEVER` 로 결과만 받고, 설정 버튼을 보여 준다
  - 영구 거부 이력이 없는 권한이 시스템에는 고정돼 있고 시스템이 유난히 느려 280ms 를 넘기면 `Denied(false)` 가 한 번 나온다. 다음 요청이 빨리 끝나면 영구 거부로 바로잡힌다
  - 경계값(280ms·2초)은 측정 기기 2대(Android 10·16 실기기)를 기준으로 정했다. 기기가 훨씬 느리거나 앱 시작이 오래 걸리면 분포가 다를 수 있다
- **자동 백업**: 기본 저장소 파일(`shared_prefs/net.plumt.permission.history.xml`)은 앱의 자동 백업에 들어간다. 라이브러리는
  설치 시각이 다른 이력을 버리지만, 백업 규칙(`dataExtractionRules`·`fullBackupContent`)에서 이 파일을 빼 두는 것이 가장 확실하다
- **권한 해제 시 프로세스 종료**: 사용자가 설정에서 런타임 권한을 끄거나 정확한 알람 권한을 끄면 OS 가 앱 프로세스를 종료한다.
  정확한 알람은 예약된 정확한 알람도 모두 취소된다 — 다시 켜졌을 때 `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`
  를 받아 알람을 다시 등록한다. 흐름 상태는 저장 상태로 이어지지만 앱의 메모리 상태는 앱이 복원한다
- **명세 사이에 겹치는 권한**: 이력은 권한 이름으로 남는다. API 29~30 의 `bluetooth(scan = true)` 와 `location()` 은 같은
  `ACCESS_FINE_LOCATION` 을 쓰므로 한쪽의 판정이 다른 쪽에 그대로 보인다(같은 권한이라 시스템 동작도 같다)
- **API 26~27 의 다른 앱 위에 표시**: 설정에서 켜고 돌아온 직후 `canDrawOverlays` 가 한동안 거짓인 플랫폼 결함이 있어
  앱 작업(AppOps) 모드도 함께 본다
- **이력 쓰기는 비동기(`apply`)**: 결과 직후 프로세스가 죽으면 마지막 기록이 빠질 수 있다. 판정이 한 단계 늦어질 뿐 다음 결과로 맞춰진다
- **동시에 두 요청**: 같은 관리자의 요청은 줄을 서서 하나씩 뜬다. 관리자를 여럿 만들었거나 다른 라이브러리가 따로 요청하면 시스템이 뒤 요청을
  빈 결과로 끝낼 수 있고, 라이브러리는 이것을 취소로 보아 이력을 건드리지 않는다. 같은 요청기의 `launch()` 는 진행 중이면 `false` 를 돌려준다
- **API 33 미만 알림**: `NotApplicable` 이지만 사용자가 설정에서 앱 알림을 껐을 수 있다. `SystemRequirements.notificationsEnabled` 로 함께 본다
- **suspend 요청과 프로세스 종료**: 요청 도중 프로세스가 종료되면 기다리던 코루틴도 사라져 그 결과는 버려진다. 화면이 다시 보일 때 다시 요청한다
- **화면 캡처 동의는 상태가 늘 `Denied`**: 동의는 세션마다 받고 시스템이 저장하지 않는다. 결과는 `onConsent` 로만 온다
- **공개 상수가 아닌 설정 화면**: `pictureInPicture`(`android.settings.PICTURE_IN_PICTURE_SETTINGS`)·`userInitiatedJobs`
  (`android.settings.MANAGE_APP_LONG_RUNNING_JOBS`)는 설정 앱의 비공개 화면 이름을 쓴다. 없는 기기는 요청기가 앱 상세 설정 화면으로 대신 연다.
  `turnScreenOn` 은 전용 화면 이름이 없어 처음부터 앱 상세 설정 화면을 연다
- **역할 요건**: 역할마다 갖춰야 할 컴포넌트가 없으면 시스템이 대화상자 없이 요청을 끝낸다 — 라이브러리는 그 요건을 검사하지 않는다
- **특수 권한·시스템 기능은 "다시 묻지 않음" 이 없다**: 켜지 않고 돌아와도 `Denied(false)`·`Disabled` 이고, 다시 요청하면 화면이 다시 열린다
- **API 36 의 신체 센서**: 건강 권한(`android.permission.health.*`)은 API 36·targetSdk 36 에서 시스템 권한 대화상자로 요청한다. 단위 테스트는
  API 36 분기를 `PermissionEnvironment.of(36)` 로 검증한다(Robolectric 4.14 는 API 35 까지 실행)

## 위협 모델·개인정보

- 저장하는 것은 권한 이름과 불리언(요청함·영구 거부), 이 설치의 최초 설치 시각뿐이다. 앱의 비공개 SharedPreferences(`net.plumt.permission.history`)에 둔다.
  진행 중인 요청 흐름(권한 이름·명세 id)은 화면의 저장 상태(Bundle)에 둔다
- 로그를 남기지 않는다. 네트워크를 쓰지 않는다
- 라이브러리 매니페스트는 비어 있다 — 의존만으로 앱 권한 목록이 늘지 않는다(확인용 앱이 빌드마다 검사한다)

## 최소 요구사항

- minSdk 23, compileSdk 36, Java 11 바이트코드
- 런타임 의존: `androidx.activity`(공개 API 에 `ActivityResultCaller`·`ActivityResultRegistry`·`ComponentActivity` 가 드러나 `api` 로 내보낸다),
  `androidx.savedstate`(`SavedStateRegistry` 가 드러나 `api`, `androidx.activity` 가 끌어오는 판과 같다), `androidx.core`,
  `kotlinx-coroutines-android`(`suspend request`. `androidx.activity` 가 이미 끌어오는 판이다).
  `compose` 는 추가로 Compose runtime·ui·material3(BOM 2025.02.00), `activity-compose`, `lifecycle-runtime-compose`
- 모든 함수는 메인 스레드에서 부른다(`suspend request` 는 스스로 메인 스레드로 옮긴다). 판정은 바인더 호출 몇 번이라 메인 스레드에서 불러도 된다
- 빌드 lint 검사는 AGP 9.0 이상(lint API 32)

오류와 실패 신호는 [ERRORS.md](ERRORS.md), 변경 이력은 [CHANGELOG.md](CHANGELOG.md).

## 버전

모듈마다 [SemVer](https://semver.org/lang/ko/) 를 따로 쓰고 태그는 `<모듈>-v<버전>`(예: `core-v0.0.2`, `compose-v0.0.2`)이다.
발행하는 모듈은 `core`·`compose` 둘이다. lint 검사(`lint`)와 확인용 앱(`sample`)은 발행하지 않는 내부 모듈이다.
1.0.0 전까지는 마이너 버전에서도 공개 API 가 바뀔 수 있다. 공개 API 는 `<모듈>/api/*.api` 로 고정하고 CI 가 대조한다.
라인 커버리지 90% 미만이면 `check` 가 실패한다(Kover).

## 라이선스

[Apache License 2.0](LICENSE)
