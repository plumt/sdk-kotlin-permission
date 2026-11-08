package net.plumt.permission

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 테스트용 가짜 관리자 — Robolectric 없이(기기 없는 JVM) 돈다 */
class FakePermissionManagerTest {

    private val camera = PermissionSpecs.camera
    private val location = PermissionSpecs.location()

    @Test
    fun `정하지 않은 명세는 기본 상태이고 정한 상태를 돌려준다`() {
        val fake = FakePermissionManager()
        assertEquals(PermissionStatus.Denied(false), fake.check(camera))
        assertEquals(RequirementStatus.Satisfied, fake.check(SystemRequirements.bluetoothEnabled))
        fake.setStatus(camera, PermissionStatus.Granted)
        fake.setStatus(SystemRequirements.bluetoothEnabled, RequirementStatus.Disabled)
        assertEquals(PermissionStatus.Granted, fake.check(camera))
        val all = fake.checkAll(listOf(camera, location), listOf(SystemRequirements.bluetoothEnabled))
        assertFalse(all.allGranted)
        assertFalse(all.allRequirementsSatisfied)
        fake.defaultStatus = PermissionStatus.PermanentlyDenied
        assertEquals(PermissionStatus.PermanentlyDenied, fake.check(location))
        assertEquals(36, fake.environment.sdkInt)
    }

    @Test
    fun `요청하면 정한 결과로 바뀌고 기록이 남는다`() = runBlocking {
        val fake = FakePermissionManager()
        fake.setResultAfterRequest(camera, PermissionStatus.Granted)
        val result = fake.request(camera)
        assertTrue(result is RequestResult.Completed)
        assertTrue(result.status.allGranted)
        assertEquals(PermissionStatus.Granted, fake.check(camera))
        assertEquals(listOf(listOf("camera")), fake.requests)

        fake.setResultAfterRequest(location, PermissionStatus.PermanentlyDenied)
        val direct = fake.request(listOf(location), settingsPolicy = SettingsPolicy.Direct)
        assertTrue(direct.status.anyPermanentlyDenied)
        assertEquals(1, fake.settingsOpenCount)

        fake.hasActivity = false
        assertTrue(fake.request(camera) is RequestResult.NoActivity)
        assertEquals(2, fake.requests.size)

        fake.reset()
        assertTrue(fake.requests.isEmpty())
        assertEquals(PermissionStatus.Denied(false), fake.check(camera))
    }

    @Test
    fun `요청기는 launch 하면 곧바로 콜백한다`() {
        val fake = FakePermissionManager()
        fake.setResultAfterRequest(camera, PermissionStatus.Granted)
        val results = mutableListOf<MultiplePermissionsStatus>()
        val requester = fake.createRequester(FakeRegistry(), "key", { null }, listOf(camera)) { results += it }
        assertTrue(requester.launch())
        assertTrue(results.single().allGranted)
        assertTrue(requester.openAppSettings())
        assertEquals(1, fake.settingsOpenCount)
        requester.unregister()
        assertThrows(IllegalStateException::class.java) { requester.launch() }
        assertThrows(IllegalArgumentException::class.java) { fake.createRequester(FakeRegistry(), " ", { null }, listOf(camera)) { } }
    }

    @Test
    fun `실제 관리자와 같은 인자 검사를 한다`() = runBlocking {
        val fake = FakePermissionManager(PermissionEnvironment.of(sdkInt = 30))
        assertThrows(IllegalArgumentException::class.java) { fake.checkAll(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { fake.request(listOf(camera, camera)) } }
        fake.undeclared = setOf("android.permission.CAMERA")
        assertEquals(setOf("android.permission.CAMERA"), fake.undeclaredPermissions(listOf(camera)))
        assertEquals(30, fake.environment.sdkInt)
        assertTrue(fake.toString().startsWith("FakePermissionManager("))
    }
}
