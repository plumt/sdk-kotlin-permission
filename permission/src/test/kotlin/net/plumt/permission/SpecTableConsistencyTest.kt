package net.plumt.permission

import net.plumt.permission.lint.Args
import net.plumt.permission.lint.Decl
import net.plumt.permission.lint.Need
import net.plumt.permission.lint.SpecTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 명세의 정본(`declaredPermissions`·`manifestPermission`)과 거기서 파생된 표가 어긋나면 실패한다.
 *
 * - lint 선언표(`SpecTable`) — 멤버 목록, 함수 인자 수, API 23~37 × targetSdk 의 선언 권한·`maxSdkVersion`·targetSdk 조건
 * - README 의 지원 범위 목록 — 모든 공개 멤버와 내장 역할이 적혀 있다
 *
 * 기기 없이 돈다(명세의 선언 판정은 [PermissionEnvironment] 만 본다).
 */
class SpecTableConsistencyTest {

    private val sdks = 23..37

    /** 사진 일부 선택처럼 "앱이 선언했으면" 쓰는 권한까지 보도록 선언 집합을 넓게 둔다 */
    private val declaredEverything = setOf("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")

    private class Case(val member: String, val label: String, val owner: String, val args: Args, val target: Any)

    // ---- 멤버 목록

    private fun publicMembers(type: Class<*>, result: Class<*>): Map<String, Method> = type.declaredMethods
        .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && result.isAssignableFrom(it.returnType) }
        .associateBy { SpecTable.memberName(it.name, it.parameterCount) }

    @Test
    fun `lint 표는 PermissionSpecs·SystemRequirements 의 공개 멤버를 빠짐없이 다룬다`() {
        val specs = publicMembers(PermissionSpecs::class.java, PermissionSpec::class.java)
        val properties = specs.filterValues { it.parameterCount == 0 }.keys
        val functions = specs.filterValues { it.parameterCount > 0 }.keys
        assertEquals("PermissionSpecs 프로퍼티 ↔ SpecTable.PROPERTIES", properties.sorted(), SpecTable.PROPERTIES.sorted())
        assertEquals(
            "PermissionSpecs 함수 ↔ SpecTable.FUNCTIONS + FUNCTIONS_WITHOUT_DECLARATION",
            functions.sorted(),
            (SpecTable.FUNCTIONS + SpecTable.FUNCTIONS_WITHOUT_DECLARATION).sorted(),
        )
        for (function in SpecTable.FUNCTIONS) {
            assertEquals("$function 의 인자 수 ↔ PARAMETER_ORDER", specs.getValue(function).parameterCount, SpecTable.PARAMETER_ORDER.getValue(function).size)
        }
        val requirements = publicMembers(SystemRequirements::class.java, SystemRequirement::class.java).keys
        assertEquals("SystemRequirements 멤버 ↔ SpecTable.REQUIREMENT_MEMBERS", requirements.sorted(), SpecTable.REQUIREMENT_MEMBERS.sorted())
    }

    // ---- 선언

    private fun cases(): List<Case> {
        val result = ArrayList<Case>()
        val specs = publicMembers(PermissionSpecs::class.java, PermissionSpec::class.java)
        for ((member, method) in specs) {
            when {
                method.parameterCount == 0 -> result += Case(member, member, SpecTable.SPECS, Args { _, default -> default }, method.invoke(PermissionSpecs)!!)
                member == "healthConnect" -> for ((read, write) in listOf(listOf("STEPS") to emptyList(), emptyList<String>() to listOf("HEART_RATE"))) {
                    val args = object : Args {
                        override fun flag(name: String, default: Boolean): Boolean = default
                        override fun strings(name: String): List<String> = if (name == "read") read else write
                    }
                    result += Case(member, "$member($read, $write)", SpecTable.SPECS, args, PermissionSpecs.healthConnect(read.toTypedArray(), write.toTypedArray()))
                }
                member in SpecTable.FUNCTIONS -> {
                    val names = SpecTable.PARAMETER_ORDER.getValue(member)
                    for (bits in 0 until (1 shl names.size)) {
                        val values = names.indices.map { bits and (1 shl it) != 0 }
                        val spec = try {
                            method.invoke(PermissionSpecs, *values.toTypedArray())
                        } catch (e: InvocationTargetException) {
                            if (e.cause is IllegalArgumentException) continue else throw e
                        }
                        val chosen = names.zip(values).toMap()
                        result += Case(member, "$member$chosen", SpecTable.SPECS, Args { name, _ -> chosen.getValue(name) }, spec!!)
                    }
                }
            }
        }
        val requirementArgs = Args { _, default -> default }
        for ((member, method) in publicMembers(SystemRequirements::class.java, SystemRequirement::class.java)) {
            val target = when (member) {
                "hardware" -> SystemRequirements.hardware("android.hardware.camera.any")
                "standbyBucket" -> SystemRequirements.standbyBucket()
                "biometricEnrolled" -> SystemRequirements.biometricEnrolled(strong = true)
                "autoTimeEnabled" -> SystemRequirements.autoTimeEnabled(includeTimeZone = true)
                "notificationChannelEnabled" -> SystemRequirements.notificationChannelEnabled("news")
                else -> {
                    assertEquals("인자 있는 요구 조건 $member 의 예시를 이 테스트에 더한다", 0, method.parameterCount)
                    method.invoke(SystemRequirements)!!
                }
            }
            result += Case(member, member, SpecTable.REQUIREMENTS, requirementArgs, target)
        }
        return result
    }

    /** 이 기기·targetSdk 에서 명세가 매니페스트에 있어야 한다고 보는 권한 */
    private fun coreDeclared(target: Any, environment: PermissionEnvironment): Set<String> = when (target) {
        is RuntimePermissionSpec -> target.declaredPermissions(environment).toSet()
        is SpecialPermissionSpec -> if (target.isApplicable(environment)) setOfNotNull(target.manifestPermission) else emptySet()
        is SystemRequirement -> target.declaredPermissions(environment).toSet()
        else -> error("모르는 명세: $target")
    }

    @Test
    fun `lint 표의 선언은 명세의 declaredPermissions 와 같다`() {
        val problems = ArrayList<String>()
        for (case in cases()) {
            val need: Need = SpecTable.lookup(case.owner, case.member, case.args)
                ?: run { problems += "${case.label}: lint 표에 없다"; null }
                ?: continue
            val all: List<Decl> = need.required + need.possible
            val tableNames = all.map { it.name }.toSet()
            val everUsed = HashSet<String>()
            for (sdk in sdks) {
                for (targetSdk in sdks) {
                    val environment = PermissionEnvironment.of(sdk, declaredEverything, targetSdk)
                    val used = coreDeclared(case.target, environment)
                    everUsed += used
                    for (permission in used) {
                        if (permission !in tableNames) problems += "${case.label}: API $sdk·target $targetSdk 에서 쓰는 $permission 이 lint 표에 없다"
                        val decls = all.filter { it.name == permission }
                        // targetSdk 조건이 있는 선언은 그 아래 targetSdk 에서 쓰면 안 된다
                        if (decls.isNotEmpty() && decls.all { it.minTargetSdk != null && targetSdk < it.minTargetSdk!! }) {
                            problems += "${case.label}: target $targetSdk 에서 쓰는 $permission 이 lint 표에서는 target ${decls.first().minTargetSdk} 이상 전용이다"
                        }
                    }
                }
                // maxSdkVersion 은 기기 API 에 걸린다 — targetSdk 가 최신인 앱으로 본다
                val latest = coreDeclared(case.target, PermissionEnvironment.of(sdk, declaredEverything, sdks.last))
                for (permission in latest) {
                    val decls = all.filter { it.name == permission }
                    if (decls.isNotEmpty() && decls.none { it.maxSdk == null || it.maxSdk!! >= sdk }) {
                        problems += "${case.label}: API $sdk 에서 쓰는 $permission 이 lint 표의 maxSdkVersion(${decls.map { it.maxSdk }}) 을 넘는다"
                    }
                }
            }
            for (decl in need.required) {
                if (decl.name !in everUsed) problems += "${case.label}: lint 표가 요구하는 ${decl.name} 을 명세는 어느 API 에서도 쓰지 않는다"
            }
        }
        if (problems.isNotEmpty()) fail("lint 표와 명세가 어긋난다 — 명세(declaredPermissions)가 정본이다:\n" + problems.joinToString("\n"))
    }

    // ---- README

    @Test
    fun `README 의 지원 범위는 모든 공개 멤버와 내장 역할을 적는다`() {
        val readme = File("../README.md").readText()
        val section = readme.substringAfter("## 지원 범위 전체 목록").substringBefore("\n## ")
        val members = publicMembers(PermissionSpecs::class.java, PermissionSpec::class.java).keys +
            publicMembers(SystemRequirements::class.java, SystemRequirement::class.java).keys
        val missing = members.filterNot { section.contains("`$it") }
        assertTrue("README 지원 범위 목록에 없는 멤버: $missing", missing.isEmpty())
        val roles = AppRole.BUILT_IN.map { it.roleName.substringAfterLast('.') }.filterNot { section.contains(it) }
        assertTrue("README 지원 범위 목록에 없는 역할: $roles", roles.isEmpty())
    }
}
