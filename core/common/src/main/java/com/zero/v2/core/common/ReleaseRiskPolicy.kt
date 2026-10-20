package com.zero.v2.core.common

/**
 * 动态模块发布风险判定（E2 / 设计§5/§E + 用户安全策略方向）。
 *
 * **用户方向**：Debian 工作站内 LLM 自主工作不受限（不设额外审批门阻塞 LLM 迭代）；
 * 模块迭代须有安全设置降低错误编码损害运行的风险（staging/active 分离与验证、回滚 + 风险提示）；
 * 规则明确但避免过多限制（FORBIDDEN 仅窄危险模式，NEEDS_PROPOSAL 标记不阻塞，LOW_RISK 默认）；
 * 稳定安全基建即可。
 *
 * 设计§E：低风险必须有可执行边界（声明式/纯计算/只读/无新增外部副作用/依赖固定/额度内）；
 * 任意 Python 与 supervisor 同 UID，venv/proot 不构成权限隔离——故 FORBIDDEN 仅阻明确危险模式，
 * 其余一律 NEEDS_PROPOSAL（journal 留痕 + 用户可见，不阻塞 LLM 自主迭代）。
 *
 * 纯逻辑（[classify]）→ JVM 可单测。
 */
object ReleaseRiskPolicy {

    /** 风险裁决。 */
    enum class Verdict { LOW_RISK, NEEDS_PROPOSAL, FORBIDDEN }

    data class Result(val verdict: Verdict, val reason: String)

    /**
     * 判定候选模块风险。
     *
     * - FORBIDDEN：明确危险模式（rm -rf / rmtree 系统路径 / 路径穿越 / 混淆 subprocess 导入）——阻塞 activate。
     * - NEEDS_PROPOSAL：宽副作用（shell/网络/系统路径写）——journal 留痕 + 用户可见，不阻塞（LLM 自主）。
     * - LOW_RISK：未命中静态风险模式；发布仍须经过实际验证与灰度，不能据此证明无副作用。
     */
    fun classify(manifest: ModuleReleaseManifest, code: String): Result {
        // FORBIDDEN：明确危险模式（窄——只阻无可争议的破坏性/逃逸）
        FORBIDDEN_PATTERNS.firstOrNull { it.regex.containsMatchIn(code) }?.let {
            return Result(Verdict.FORBIDDEN, "明确危险模式：${it.label}（阻塞 activate——防破坏系统/数据）")
        }
        // NEEDS_PROPOSAL：宽副作用（标记不阻塞——LLM 自主迭代，journal 留痕供用户可见）
        NEEDS_PROPOSAL_PATTERNS.firstOrNull { it.regex.containsMatchIn(code) }?.let {
            return Result(Verdict.NEEDS_PROPOSAL, "宽副作用：${it.label}（标记不阻塞，journal 留痕——用户可见）")
        }
        return Result(Verdict.LOW_RISK, "未命中静态风险模式；仍需候选验证与灰度，不能据此证明无副作用")
    }

    /** 是否可自动 activate（LOW_RISK + NEEDS_PROPOSAL 可——后者留痕不阻塞；FORBIDDEN 不可）。 */
    fun canAutoActivate(result: Result): Boolean = result.verdict != Verdict.FORBIDDEN

    private data class Pattern(val label: String, val regex: Regex)

    /** FORBIDDEN 模式（窄——明确破坏性/逃逸）。 */
    private val FORBIDDEN_PATTERNS = listOf(
        Pattern("rm -rf 破坏性删除", Regex("""rm\s+-rf?\s""")),
        Pattern("shutil.rmtree 系统路径删除", Regex("""shutil\.rmtree\s*\(\s*['"]/(?!root/zero/(?:modules|data|staging))""")),
        Pattern("路径穿越写（../）", Regex("""(\.open|\bopen)\s*\(\s*['"][^'"]*\.\.[^'"]*['"]\s*,\s*['"]w""")),
        Pattern("混淆 subprocess 导入", Regex("""__import__\(\s*['"]subprocess['"]\s*\)""")),
        Pattern("os.system 破坏性命令", Regex("""os\.system\s*\(\s*['"](?:rm|mkfs|dd\s+of=)""")),
    )

    /** NEEDS_PROPOSAL 模式（宽副作用——标记不阻塞）。 */
    private val NEEDS_PROPOSAL_PATTERNS = listOf(
        Pattern("shell 执行（subprocess/os.system）", Regex("""\bsubprocess\b|\bos\.system\b|\bexec\b\s*\(""")),
        Pattern("网络访问（socket/urllib/requests）", Regex("""\bsocket\b|\burllib\b|\brequests\b|\bhttp\.client\b""")),
        Pattern("系统路径写（/etc/~/var 等）", Regex("""\bopen\s*\(\s*['"]/(?:etc|var|tmp|home|root)(?!/zero/(?:modules|data|staging))""")),
        Pattern("eval/exec 动态执行", Regex("""\beval\s*\(|\bexec\s*\(\s*['"]""")),
    )
}
