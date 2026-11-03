package net.plumt.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 상태 확장 속성과 여러 권한 묶음 집계 */
class PermissionStatusTest {

    private val partialAskable = PermissionStatus.Partial(setOf("a"), setOf("b"), shouldShowRationale = true, isMissingPermanentlyDenied = false)
    private val partialBlocked = PermissionStatus.Partial(setOf("a"), setOf("b"), shouldShowRationale = false, isMissingPermanentlyDenied = true)
    private val all: List<PermissionStatus> = listOf(
        PermissionStatus.Granted,
        PermissionStatus.NotApplicable(30),
        partialAskable,
        partialBlocked,
        PermissionStatus.Denied(true),
        PermissionStatus.Denied(false),
        PermissionStatus.PermanentlyDenied,
    )

    @Test
    fun `isUsable 은 허용 비해당 일부 허용에서만 참`() {
        assertEquals(listOf(true, true, true, true, false, false, false), all.map { it.isUsable })
    }

    @Test
    fun `isFullyGranted 는 허용과 비해당에서만 참`() {
        assertEquals(listOf(true, true, false, false, false, false, false), all.map { it.isFullyGranted })
    }

    @Test
    fun `canAskAgain 은 거부와 영구 거부 아닌 일부 허용에서 참`() {
        assertEquals(listOf(false, false, true, false, true, true, false), all.map { it.canAskAgain })
    }

    @Test
    fun `shouldShowRationale 은 거부와 일부 허용의 값을 따른다`() {
        assertEquals(listOf(false, false, true, false, true, false, false), all.map { it.shouldShowRationale })
    }

    @Test
    fun `needsSettings 는 영구 거부와 나머지가 영구 거부된 일부 허용`() {
        assertEquals(listOf(false, false, false, true, false, false, true), all.map { it.needsSettings })
    }

    @Test
    fun `상태 값은 구조로 비교한다`() {
        assertEquals(PermissionStatus.Denied(true), PermissionStatus.Denied(true))
        assertNotEquals(PermissionStatus.Denied(true), PermissionStatus.Denied(false))
        assertEquals(PermissionStatus.NotApplicable(32), PermissionStatus.NotApplicable(32))
        assertEquals(partialAskable, partialAskable.copy())
        assertEquals("Granted", PermissionStatus.Granted.toString())
        assertEquals(32, PermissionStatus.NotApplicable(32).sdkInt)
    }

    // ---- 묶음

    private val a = PermissionSpecs.camera
    private val b = PermissionSpecs.microphone
    private val c = PermissionSpecs.notifications

    @Test
    fun `모두 허용이면 allGranted allUsable`() {
        val s = MultiplePermissionsStatus(mapOf(a to PermissionStatus.Granted, b to PermissionStatus.NotApplicable(30)))
        assertTrue(s.allGranted)
        assertTrue(s.allUsable)
        assertFalse(s.shouldShowRationale)
        assertFalse(s.anyPermanentlyDenied)
        assertFalse(s.onlySettingsCanHelp)
        assertEquals(emptyList<PermissionSpec>(), s.notGranted)
        assertEquals(listOf(a, b), s.specs)
    }

    @Test
    fun `일부 허용이 섞이면 allUsable 만 참`() {
        val s = MultiplePermissionsStatus(mapOf(a to PermissionStatus.Granted, b to partialAskable))
        assertFalse(s.allGranted)
        assertTrue(s.allUsable)
        assertTrue(s.shouldShowRationale)
        assertEquals(listOf(b), s.notGranted)
    }

    @Test
    fun `거부가 하나라도 있으면 allUsable 거짓`() {
        val s = MultiplePermissionsStatus(mapOf(a to PermissionStatus.Granted, b to PermissionStatus.Denied(false)))
        assertFalse(s.allUsable)
        assertFalse(s.onlySettingsCanHelp)
    }

    @Test
    fun `남은 것이 모두 설정으로만 풀리면 onlySettingsCanHelp`() {
        val s = MultiplePermissionsStatus(
            mapOf(a to PermissionStatus.Granted, b to PermissionStatus.PermanentlyDenied, c to partialBlocked),
        )
        assertTrue(s.onlySettingsCanHelp)
        assertTrue(s.anyPermanentlyDenied)
        val mixed = MultiplePermissionsStatus(mapOf(b to PermissionStatus.PermanentlyDenied, c to PermissionStatus.Denied(true)))
        assertFalse(mixed.onlySettingsCanHelp)
        assertTrue(mixed.anyPermanentlyDenied)
        assertTrue(mixed.shouldShowRationale)
    }

    @Test
    fun `get 은 같은 객체나 같은 id 로 찾고 없으면 IllegalArgumentException`() {
        val s = MultiplePermissionsStatus(mapOf(PermissionSpecs.location() to PermissionStatus.Granted))
        assertEquals(PermissionStatus.Granted, s[PermissionSpecs.location()])
        val sameId = object : RuntimePermissionSpec {
            override val id = "location.precise"
            override fun permissions(environment: PermissionEnvironment) = emptyList<String>()
        }
        assertEquals(PermissionStatus.Granted, s[sameId])
        val ex = assertThrows(IllegalArgumentException::class.java) { s[a] }
        assertEquals("이 묶음에 없는 명세입니다: camera (있는 것: [location.precise])", ex.message)
    }

    @Test
    fun `빈 묶음과 id 중복은 IllegalArgumentException`() {
        val empty = assertThrows(IllegalArgumentException::class.java) { MultiplePermissionsStatus(emptyMap()) }
        assertEquals("명세가 하나 이상 있어야 합니다", empty.message)
        val dup = assertThrows(IllegalArgumentException::class.java) {
            MultiplePermissionsStatus(mapOf(FakeSpecialSpec("x") to PermissionStatus.Granted, FakeSpecialSpec("x") to PermissionStatus.Granted))
        }
        assertEquals("같은 id 의 명세가 겹칩니다: [x]", dup.message)
    }

    @Test
    fun `묶음은 내용으로 비교하고 문자열에 id 를 담는다`() {
        val x = MultiplePermissionsStatus(mapOf(a to PermissionStatus.Granted))
        val y = MultiplePermissionsStatus(mapOf(a to PermissionStatus.Granted))
        assertEquals(x, y)
        assertEquals(x.hashCode(), y.hashCode())
        assertNotEquals(x, MultiplePermissionsStatus(mapOf(a to PermissionStatus.PermanentlyDenied)))
        assertNotEquals(x, "x")
        assertEquals("MultiplePermissionsStatus(camera=Granted)", x.toString())
    }

    @Test
    fun `설정과 예외 값`() {
        val config = PermissionConfig()
        assertEquals(null, config.historyStore)
        assertEquals(ManifestCheck.THROW_IF_DEBUGGABLE, config.manifestCheck)
        assertTrue(config.toString().contains("THROW_IF_DEBUGGABLE"))
        val cause = IllegalStateException("c")
        val e = PermissionException("m", cause)
        assertEquals("m", e.message)
        assertEquals(cause, e.cause)
        assertEquals(null, PermissionException("m").cause)
        assertEquals(3, ManifestCheck.entries.size)
        assertEquals(3, GrantLevel.entries.size)
    }
}
