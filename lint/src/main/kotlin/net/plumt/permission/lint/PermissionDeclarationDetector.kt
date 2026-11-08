package net.plumt.permission.lint

import com.android.SdkConstants.ANDROID_URI
import com.android.SdkConstants.ATTR_NAME
import com.android.SdkConstants.TAG_USES_PERMISSION
import com.android.SdkConstants.TAG_USES_PERMISSION_SDK_23
import com.android.SdkConstants.TOOLS_URI
import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.ConstantEvaluator
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.LintFix
import com.android.tools.lint.detector.api.LintMap
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.PartialResult
import com.android.tools.lint.detector.api.Project
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.XmlContext
import com.android.tools.lint.detector.api.XmlScanner
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.toUElement
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.EnumSet

/**
 * 코드가 쓰는 권한 명세(`PermissionSpecs.camera` 등)와 앱 매니페스트 선언을 맞춘다.
 *
 * - 명세가 요청하는 권한이 병합된 앱 매니페스트에 없으면 오류([MISSING]) — 매니페스트에 넣을 줄을 고치기로 준다
 * - 앱 매니페스트가 선언한 권한 가운데 이 표가 아는 것을 어느 명세도 쓰지 않으면 정보([UNUSED])
 *
 * 여러 모듈 앱은 기능 모듈이 명세를 쓰고 앱 모듈이 매니페스트를 가진다. 그래서 모듈마다 쓰임을 부분 결과로 남기고,
 * 앱 모듈의 보고 단계([checkPartialResults])에서 병합된 매니페스트와 견준다.
 */
class PermissionDeclarationDetector : Detector(), SourceCodeScanner, XmlScanner {

    private class Use(val need: Need, val location: Location)

    private class Declared(val name: String, val location: Location)

    // 전역 분석(IDE·단일 모듈)일 때만 쓴다. 부분 분석은 LintMap 으로 넘긴다
    private val uses = ArrayList<Use>()
    private val declared = ArrayList<Declared>()

    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(UCallExpression::class.java, USimpleNameReferenceExpression::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler = object : UElementHandler() {
        override fun visitCallExpression(node: UCallExpression) {
            val method = node.resolve() ?: return
            val owner = method.containingClass?.qualifiedName ?: return
            val member = SpecTable.memberName(method.name, method.parameterList.parametersCount)
            record(
                context,
                node,
                owner,
                member,
                object : Args {
                    override fun flag(name: String, default: Boolean): Boolean? =
                        argument(context, node, method, member, name) { it as? Boolean } ?: default.takeIf { !passed(context, node, method, member, name) }

                    override fun strings(name: String): List<String>? =
                        if (!passed(context, node, method, member, name)) emptyList() else argument(context, node, method, member, name, ::stringList)
                },
            )
        }

        override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression) {
            // 프로퍼티 읽기(PermissionSpecs.camera) — 호출식의 이름 부분은 위에서 다룬다
            if (node.uastParent is UCallExpression) return
            val resolved = node.resolve() ?: return
            // 소스 명세는 게터(PsiMethod), 컴파일된 라이브러리는 필드나 Kotlin 프로퍼티로 풀린다
            val (owner, member) = when (resolved) {
                is PsiMethod -> {
                    if (resolved.parameterList.parametersCount != 0) return
                    (resolved.containingClass?.qualifiedName ?: return) to SpecTable.memberName(resolved.name, 0)
                }
                is PsiField -> (resolved.containingClass?.qualifiedName ?: return) to resolved.name
                else -> {
                    val name = (resolved as? PsiNamedElement)?.name ?: return
                    val owner = resolved.toUElement()?.getContainingUClass()?.qualifiedName ?: return
                    owner to name
                }
            }
            val target = node.getParentOfType<UQualifiedReferenceExpression>(strict = true)?.takeIf { it.selector == node } ?: node
            record(context, target, owner, member, Args { _, default -> default })
        }
    }

    private fun record(
        context: JavaContext,
        node: UElement,
        owner: String,
        member: String,
        args: Args,
    ) {
        if (owner != SpecTable.SPECS && owner != SpecTable.REQUIREMENTS) return
        val need = SpecTable.lookup(owner, member, args) ?: return
        // 판정은 나중(앱 보고 단계)에 하므로 억제(@Suppress·//noinspection)는 쓰인 자리에서 지금 본다
        if (context.driver.isSuppressed(context, MISSING, node)) return
        val location = context.getLocation(node)
        if (context.isGlobalAnalysis()) {
            uses += Use(need, location)
        } else {
            val map = context.getPartialResults(MISSING).map()
            val index = map.getInt(KEY_COUNT, 0) ?: 0
            map.put(KEY_COUNT, index + 1)
            map.put("$KEY_LABEL$index", need.label)
            map.put("$KEY_REQUIRED$index", need.required.joinToString(";") { it.encode() })
            map.put("$KEY_POSSIBLE$index", need.possible.joinToString(";") { it.encode() })
            map.put("$KEY_LOCATION$index", location)
        }
    }

    private fun argumentExpression(context: JavaContext, call: UCallExpression, method: PsiMethod, member: String, name: String): UExpression? {
        val index = SpecTable.PARAMETER_ORDER[member]?.indexOf(name)?.takeIf { it >= 0 } ?: return null
        val mapping = context.evaluator.computeArgumentMapping(call, method)
        return mapping.entries.firstOrNull { method.parameterList.getParameterIndex(it.value) == index }?.key
    }

    /** 그 인자를 넘겼는지 — 넘기지 않았으면 기본값이다 */
    private fun passed(context: JavaContext, call: UCallExpression, method: PsiMethod, member: String, name: String): Boolean =
        argumentExpression(context, call, method, member, name) != null

    /** 인자를 상수로 읽는다. 넘기지 않았거나 읽을 수 없으면 null */
    private fun <T> argument(
        context: JavaContext,
        call: UCallExpression,
        method: PsiMethod,
        member: String,
        name: String,
        convert: (Any?) -> T?,
    ): T? {
        val expression = argumentExpression(context, call, method, member, name) ?: return null
        return convert(ConstantEvaluator.evaluate(null, expression))
    }

    /** 상수 평가 결과가 문자열 배열이면 목록으로 */
    private fun stringList(value: Any?): List<String>? = when (value) {
        is Array<*> -> value.map { it as? String ?: return null }
        is Collection<*> -> value.map { it as? String ?: return null }
        else -> null
    }

    // ---- 앱 매니페스트의 선언(쓰지 않는 선언 판정용)

    override fun getApplicableElements(): Collection<String> = listOf(TAG_USES_PERMISSION, TAG_USES_PERMISSION_SDK_23)

    override fun visitElement(context: XmlContext, element: Element) {
        if (context.project.isLibrary) return
        val name = element.getAttributeNS(ANDROID_URI, ATTR_NAME).takeIf { it.isNotEmpty() } ?: return
        if (!SpecTable.isKnown(name)) return
        if (context.driver.isSuppressed(context, UNUSED, element)) return
        val location = context.getLocation(element)
        if (context.isGlobalAnalysis()) {
            declared += Declared(name, location)
        } else {
            // 쓰임과 같은 부분 결과에 남긴다 — 보고 단계는 이슈 하나의 결과로 두 판정을 함께 한다
            val map = context.getPartialResults(MISSING).map()
            val index = map.getInt(KEY_DECLARED_COUNT, 0) ?: 0
            map.put(KEY_DECLARED_COUNT, index + 1)
            map.put("$KEY_DECLARED$index", name)
            map.put("$KEY_DECLARED_LOCATION$index", location)
        }
    }

    // ---- 판정

    override fun afterCheckRootProject(context: Context) {
        if (context.isGlobalAnalysis()) report(context, uses, declared)
    }

    override fun checkPartialResults(context: Context, partialResults: PartialResult) {
        if (partialResults.issue == MISSING) {
            val all = ArrayList<Use>()
            for (map in partialResults.maps()) all += readUses(map)
            val declaredAll = ArrayList<Declared>()
            for (map in partialResults.maps()) declaredAll += readDeclared(map)
            report(context, all, declaredAll)
        }
    }

    private fun readUses(map: LintMap): List<Use> {
        val count = map.getInt(KEY_COUNT, 0) ?: 0
        return (0 until count).mapNotNull { i ->
            val location = map.getLocation("$KEY_LOCATION$i") ?: return@mapNotNull null
            val required = map.getString("$KEY_REQUIRED$i", "").orEmpty().split(';').mapNotNull(Decl::decode)
            val possible = map.getString("$KEY_POSSIBLE$i", "").orEmpty().split(';').mapNotNull(Decl::decode)
            Use(Need(map.getString("$KEY_LABEL$i", "").orEmpty(), required, possible), location)
        }
    }

    private fun readDeclared(map: LintMap): List<Declared> {
        val count = map.getInt(KEY_DECLARED_COUNT, 0) ?: 0
        return (0 until count).mapNotNull { i ->
            val name = map.getString("$KEY_DECLARED$i", null) ?: return@mapNotNull null
            val location = map.getLocation("$KEY_DECLARED_LOCATION$i") ?: return@mapNotNull null
            Declared(name, location)
        }
    }

    private fun report(context: Context, uses: List<Use>, declared: List<Declared>) {
        val app = context.mainProject
        // 라이브러리만 검사할 때는 앱 매니페스트를 모른다 — 앱 모듈에서 판정한다
        if (app.isLibrary) return
        val manifest = app.mergedManifest ?: return
        val present = presentDeclarations(manifest)
        val minSdk = app.minSdkVersion.featureLevel
        val targetSdk = app.targetSdkVersion.featureLevel
        val reported = HashSet<String>()
        for (use in uses) {
            val missing = use.need.required
                .filter { it.maxSdk == null || it.maxSdk >= minSdk }
                .filter { it.minTargetSdk == null || targetSdk >= it.minTargetSdk }
                .filterNot { isDeclared(it, present) }
            if (missing.isEmpty()) continue
            val key = use.location.file.path + ":" + use.location.start?.offset + ":" + missing.joinToString { it.name }
            if (!reported.add(key)) continue
            val lines = missing.joinToString("\n") { it.line() }
            context.report(
                MISSING,
                use.location,
                "`${use.need.label}` 이 요청하는 권한이 병합된 앱 매니페스트에 없습니다: " +
                    missing.joinToString { "`${it.name}`" } +
                    ". 선언하지 않은 권한은 대화상자 없이 곧바로 거부되어 영구 거부처럼 보입니다. 앱 `AndroidManifest.xml` 에 다음을 넣으세요:\n$lines",
                manifestFix(context, app, missing),
            )
        }
        if (uses.isEmpty()) return
        val used = HashSet<String>()
        for (use in uses) (use.need.required + use.need.possible).forEach { used += it.name }
        val anyHealth = SpecTable.HEALTH_ANY in used
        for (declaration in declared) {
            if (declaration.name in used) continue
            if (anyHealth && declaration.name.startsWith(SpecTable.HEALTH)) continue
            context.report(
                UNUSED,
                declaration.location,
                "`${declaration.name}` 을 선언했지만 코드의 어느 권한 명세도 이 권한을 쓰지 않습니다. " +
                    "다른 라이브러리나 직접 요청에 쓰지 않는다면 지워 앱이 요청하는 권한을 줄이세요.",
            )
        }
    }

    private fun presentDeclarations(manifest: Document): Map<String, List<Int?>> {
        val result = HashMap<String, MutableList<Int?>>()
        for (tag in listOf(TAG_USES_PERMISSION, TAG_USES_PERMISSION_SDK_23)) {
            val nodes = manifest.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as? Element ?: continue
                val name = element.getAttributeNS(ANDROID_URI, ATTR_NAME).takeIf { it.isNotEmpty() } ?: continue
                val max = element.getAttributeNS(ANDROID_URI, "maxSdkVersion").toIntOrNull()
                result.getOrPut(name) { ArrayList() } += max
            }
        }
        return result
    }

    /** 선언이 그 줄이 필요한 API 범위를 덮는지 — 상한 없는 선언은 무엇이든 덮는다 */
    private fun isDeclared(decl: Decl, present: Map<String, List<Int?>>): Boolean {
        val maxes = present[decl.name] ?: return false
        return maxes.any { it == null || (decl.maxSdk != null && it >= decl.maxSdk) }
    }

    /** 앱의 소스 매니페스트 `<manifest>` 여는 태그 바로 뒤에 줄을 넣는 고치기. 매니페스트를 찾지 못하면 null */
    private fun manifestFix(context: Context, app: Project, missing: List<Decl>): LintFix? {
        val file: File = app.manifestFiles.firstOrNull() ?: return null
        val text = context.client.readFile(file).toString()
        // 레포의 정규식은 Android 엔진과도 같은 뜻인 좁은 문법만 쓴다(AndroidRegexCompatTest) — \b 대신 공백 문자를 적는다
        val open = Regex("<manifest([ \t\r\n][^>]*)?>").find(text) ?: return null
        if (open.value.endsWith("/>")) return null
        val indent = Regex("\\n([ \\t]+)<").find(text, open.range.last)?.groupValues?.get(1) ?: "    "
        val insert = missing.joinToString("") { "\n" + indent + it.line() }
        val fixes = ArrayList<LintFix>()
        fixes += LintFix.create()
            .name("앱 매니페스트에 선언 추가")
            .replace()
            .range(Location.create(file, text, open.range.last + 1, open.range.last + 1))
            .with(insert)
            .build()
        if (missing.any { it.protectedPermission } && !text.contains(TOOLS_URI)) {
            val tagEnd = open.range.first + "<manifest".length
            fixes += LintFix.create()
                .replace()
                .range(Location.create(file, text, tagEnd, tagEnd))
                .with(" xmlns:tools=\"$TOOLS_URI\"")
                .build()
        }
        return if (fixes.size == 1) {
            fixes.single()
        } else {
            LintFix.create().name("앱 매니페스트에 선언 추가").composite(*fixes.toTypedArray())
        }
    }

    companion object {
        private const val KEY_COUNT = "count"
        private const val KEY_LABEL = "label#"
        private const val KEY_REQUIRED = "required#"
        private const val KEY_POSSIBLE = "possible#"
        private const val KEY_LOCATION = "location#"
        private const val KEY_DECLARED = "declared#"
        private const val KEY_DECLARED_COUNT = "declared.count"
        private const val KEY_DECLARED_LOCATION = "declared.location#"

        private val IMPLEMENTATION = Implementation(
            PermissionDeclarationDetector::class.java,
            EnumSet.of(Scope.JAVA_FILE, Scope.MANIFEST),
            Scope.JAVA_FILE_SCOPE,
            Scope.MANIFEST_SCOPE,
        )

        /** 명세가 요청하는 권한이 앱 매니페스트에 없다 */
        @JvmField
        val MISSING: Issue = Issue.create(
            id = "PlumtPermissionNotDeclared",
            briefDescription = "권한 명세가 요청하는 권한이 매니페스트에 없음",
            explanation = """
                `net.plumt.permission` 의 권한 명세는 권한을 매니페스트에 선언하지 않는다 — 앱이 쓰는 권한만 앱이 선언한다. \
                명세가 요청하는 권한이 병합된 앱 매니페스트에 없으면 시스템은 대화상자 없이 곧바로 거부하고, \
                그 결과는 영구 거부와 구별되지 않는다. 고치기는 필요한 `<uses-permission>` 줄(`maxSdkVersion`·`usesPermissionFlags` 포함)을 \
                앱 매니페스트에 넣는다. 의도한 것이면 `lint.xml` 이나 `lintOptions` 로 심각도를 낮춘다.
            """,
            category = Category.CORRECTNESS,
            priority = 8,
            severity = Severity.ERROR,
            implementation = IMPLEMENTATION,
        )

        /** 선언했는데 어느 명세도 쓰지 않는 권한 */
        @JvmField
        val UNUSED: Issue = Issue.create(
            id = "PlumtPermissionDeclaredButUnused",
            briefDescription = "선언했지만 권한 명세가 쓰지 않는 권한",
            explanation = """
                앱 매니페스트가 선언한 권한 가운데 `net.plumt.permission` 의 명세가 다루는 것인데 코드의 어느 명세도 쓰지 않는다. \
                다른 라이브러리나 직접 요청 때문에 선언했을 수 있어 기본은 정보 수준이다. 쓰지 않는 권한은 지워 앱이 받는 권한을 줄인다.
            """,
            category = Category.CORRECTNESS,
            priority = 3,
            severity = Severity.INFORMATIONAL,
            implementation = IMPLEMENTATION,
        )
    }
}
