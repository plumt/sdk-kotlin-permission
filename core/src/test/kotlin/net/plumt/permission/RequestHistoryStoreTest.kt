package net.plumt.permission

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RequestHistoryStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun stores(): List<RequestHistoryStore> =
        listOf(RequestHistoryStore.inMemory(), RequestHistoryStore.sharedPreferences(context, "t"))

    @Test
    fun `처음에는 요청한 적도 영구 거부도 없다`() {
        for (s in stores()) {
            assertFalse(s.hasRequested("a"))
            assertFalse(s.isPermanentlyDenied("a"))
        }
    }

    @Test
    fun `record 는 요청 표시와 영구 거부를 덮어쓴다`() {
        for (s in stores()) {
            s.record("a", permanentlyDenied = true)
            assertTrue(s.hasRequested("a"))
            assertTrue(s.isPermanentlyDenied("a"))
            s.record("a", permanentlyDenied = false)
            assertTrue(s.hasRequested("a"))
            assertFalse(s.isPermanentlyDenied("a"))
            assertFalse(s.hasRequested("b"))
        }
    }

    @Test
    fun `clear 는 전부 지운다`() {
        for (s in stores()) {
            s.record("a", true)
            s.record("b", false)
            s.clear()
            assertFalse(s.hasRequested("a"))
            assertFalse(s.hasRequested("b"))
            assertFalse(s.isPermanentlyDenied("a"))
        }
    }

    @Test
    fun `빈 이름 유니코드 매우 긴 권한 이름도 키로 쓴다`() {
        val long = "p".repeat(10_000)
        for (s in stores()) {
            for (key in listOf("", "권한.한글", "emoji-😀", long)) {
                s.record(key, true)
                assertTrue(key.take(20), s.isPermanentlyDenied(key))
            }
        }
    }

    @Test
    fun `SharedPreferences 저장소는 다시 만들어도 값이 남는다(프로세스 재시작)`() {
        RequestHistoryStore.sharedPreferences(context, "restart").record("a", true)
        val reopened = RequestHistoryStore.sharedPreferences(context, "restart")
        assertTrue(reopened.hasRequested("a"))
        assertTrue(reopened.isPermanentlyDenied("a"))
        // 이름이 다르면 다른 저장소다
        assertFalse(RequestHistoryStore.sharedPreferences(context, "other").hasRequested("a"))
    }

    @Test
    fun `기본 이름의 SharedPreferences 를 쓴다`() {
        RequestHistoryStore.sharedPreferences(context).record("a", false)
        val prefs = context.getSharedPreferences(RequestHistoryStore.DEFAULT_NAME, Context.MODE_PRIVATE)
        assertTrue(prefs.contains("a"))
        assertEquals("net.plumt.permission.history", RequestHistoryStore.DEFAULT_NAME)
    }

    @Test
    fun `빈 이름은 IllegalArgumentException`() {
        for (bad in listOf("", "  ")) {
            val e = assertThrows(IllegalArgumentException::class.java) { RequestHistoryStore.sharedPreferences(context, bad) }
            assertEquals("name 이 비었습니다", e.message)
        }
    }

    @Test
    fun `메모리 저장소는 인스턴스마다 따로다`() {
        val a = RequestHistoryStore.inMemory()
        a.record("x", true)
        assertFalse(RequestHistoryStore.inMemory().hasRequested("x"))
    }

    @Test
    fun `메모리 저장소는 여러 스레드에서 동시에 써도 깨지지 않는다`() {
        val s = RequestHistoryStore.inMemory()
        val threads = (0 until 8).map { t ->
            Thread { repeat(500) { i -> s.record("p$t-$i", i % 2 == 0) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        for (t in 0 until 8) for (i in 0 until 500) {
            assertTrue(s.hasRequested("p$t-$i"))
            assertEquals(i % 2 == 0, s.isPermanentlyDenied("p$t-$i"))
        }
    }
}
