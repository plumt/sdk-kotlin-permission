package net.plumt.permission.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

/** core aar 의 lint.jar 로 앱 lint 에 더해지는 검사 */
class PermissionIssueRegistry : IssueRegistry() {
    override val issues: List<Issue> = listOf(PermissionDeclarationDetector.MISSING, PermissionDeclarationDetector.UNUSED)

    override val api: Int = CURRENT_API

    // 부분 분석(LintMap)과 checkPartialResults 를 쓰는 가장 낮은 lint API
    override val minApi: Int = 10

    override val vendor: Vendor = Vendor(
        vendorName = "net.plumt.permission",
        identifier = "net.plumt.permission:core",
        feedbackUrl = "https://github.com/plumt/sdk-kotlin-permission/issues",
    )
}
