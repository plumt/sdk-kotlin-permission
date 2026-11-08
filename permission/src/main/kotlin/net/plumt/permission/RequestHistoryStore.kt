package net.plumt.permission

import android.content.Context
import net.plumt.permission.internal.InMemoryHistoryStore
import net.plumt.permission.internal.SharedPreferencesHistoryStore
import net.plumt.permission.internal.installStamp

/**
 * 권한별 요청 이력 저장소. 영구 거부 판정에 쓴다.
 *
 * `shouldShowRequestPermissionRationale` 은 "처음 요청하기 전" 과 "다시 묻지 않음" 에서 모두 거짓이라
 * 그것만으로는 둘을 가를 수 없다. 그래서 요청할 때 내린 판정(요청 전·후 근거 안내 값과 대화상자가 떴는지)을 여기에 남기고,
 * 확인할 때 읽는다.
 * 프로세스가 다시 떠도 판정이 이어지려면 영속 저장소([sharedPreferences])를 쓴다.
 *
 * 저장하는 것은 권한 이름(`android.permission.CAMERA` 등)과 불리언뿐이다. 사용자 정보는 담지 않는다.
 * 구현은 메인 스레드에서 불리므로 빨리 돌아와야 한다.
 */
public interface RequestHistoryStore {

    /** 이 권한을 한 번이라도 요청해 결과를 받았는지 */
    public fun hasRequested(permission: String): Boolean

    /** 마지막으로 관측한 결과가 "다시 묻지 않음" 이었는지 */
    public fun isPermanentlyDenied(permission: String): Boolean

    /** 요청 결과를 남긴다. 요청한 것으로 표시하고 영구 거부 여부를 덮어쓴다. */
    public fun record(permission: String, permanentlyDenied: Boolean)

    /**
     * 그 권한의 이력을 지워 "요청한 적 없음" 으로 되돌린다. 권한이 허용된 것을 보면 부른다 —
     * 허용된 권한이 나중에 초기화되면(설정의 초기화·미사용 앱 자동 초기화) 처음 상태로 다시 판정해야 한다.
     */
    public fun remove(permission: String)

    /** 이력을 모두 지운다. */
    public fun clear()

    public companion object {
        /** [sharedPreferences] 의 기본 파일 이름 */
        public const val DEFAULT_NAME: String = "net.plumt.permission.history"

        /**
         * SharedPreferences 에 남기는 기본 저장소. 쓰기는 비동기(`apply`)다 — 결과 직후 프로세스가 죽으면
         * 마지막 기록이 빠질 수 있고, 그때 판정은 한 단계 늦어질 뿐 다음 요청 결과로 다시 맞춰진다.
         *
         * 이 설치의 최초 설치 시각(`firstInstallTime`)을 함께 적어 두고, 열 때 값이 다르면 이력을 모두 버린다.
         * 자동 백업(Auto Backup)으로 다른 기기·재설치에 복원된 이력이 새로 시작한 권한 상태를 영구 거부로
         * 오판하지 않게 하려는 것이다.
         *
         * @throws IllegalArgumentException [name] 이 비었을 때
         */
        public fun sharedPreferences(context: Context, name: String = DEFAULT_NAME): RequestHistoryStore {
            require(name.isNotBlank()) { "name 이 비었습니다" }
            val app = context.applicationContext ?: context
            return SharedPreferencesHistoryStore(app, name, installStamp(app))
        }

        /** 프로세스 안에서만 사는 저장소. 테스트·미리보기용 — 앱을 다시 띄우면 영구 거부 판정이 처음으로 돌아간다. */
        public fun inMemory(): RequestHistoryStore = InMemoryHistoryStore()
    }
}
