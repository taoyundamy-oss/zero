package com.zero.v2.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReleaseRiskPolicy] 单测 — E2 验收：风险分类 + 自动 activate 门禁。
 *
 * 用户方向：FORBIDDEN 仅窄危险模式（阻塞）；NEEDS_PROPOSAL 标记不阻塞（LLM 自主）；LOW_RISK 默认。
 */
class ReleaseRiskPolicyTest {

    private val manifest = ModuleReleaseManifest()
    private fun classify(code: String) = ReleaseRiskPolicy.classify(manifest, code)

    @Test
    fun lowRisk_defaultDeclarative() {
        // 纯声明式/内存计算 → LOW_RISK
        assertEquals(ReleaseRiskPolicy.Verdict.LOW_RISK, classify("def serve(req): return {'sum': req['a']+req['b']}").verdict)
        assertEquals(ReleaseRiskPolicy.Verdict.LOW_RISK, classify("pass").verdict)
        assertEquals(ReleaseRiskPolicy.Verdict.LOW_RISK, classify("def serve(): return {'ok': True}").verdict)
    }

    @Test
    fun forbidden_destructuveRm() {
        assertEquals(ReleaseRiskPolicy.Verdict.FORBIDDEN, classify("import os; os.system('rm -rf /')").verdict)
    }

    @Test
    fun forbidden_shutilRmtreeSystemPath() {
        assertEquals(ReleaseRiskPolicy.Verdict.FORBIDDEN, classify("import shutil; shutil.rmtree('/etc')").verdict)
    }

    @Test
    fun forbidden_pathTraversalWrite() {
        assertEquals(ReleaseRiskPolicy.Verdict.FORBIDDEN, classify("open('../../etc/passwd','w')").verdict)
    }

    @Test
    fun forbidden_obfuscatedSubprocess() {
        assertEquals(ReleaseRiskPolicy.Verdict.FORBIDDEN, classify("__import__('subprocess').run(['ls'])").verdict)
    }

    @Test
    fun forbidden_osSystemDestructive() {
        assertEquals(ReleaseRiskPolicy.Verdict.FORBIDDEN, classify("os.system('dd of=/dev/sda')").verdict)
    }

    @Test
    fun needsProposal_shellSubprocess() {
        // subprocess → NEEDS_PROPOSAL（标记不阻塞）
        val r = classify("import subprocess; subprocess.run(['ls'])")
        assertEquals(ReleaseRiskPolicy.Verdict.NEEDS_PROPOSAL, r.verdict)
        assertTrue(ReleaseRiskPolicy.canAutoActivate(r))  // 不阻塞
    }

    @Test
    fun needsProposal_networkRequests() {
        val r = classify("import requests; requests.get('https://example.com')")
        assertEquals(ReleaseRiskPolicy.Verdict.NEEDS_PROPOSAL, r.verdict)
        assertTrue(ReleaseRiskPolicy.canAutoActivate(r))
    }

    @Test
    fun needsProposal_eval() {
        val r = classify("eval('1+1')")
        assertEquals(ReleaseRiskPolicy.Verdict.NEEDS_PROPOSAL, r.verdict)
        assertTrue(ReleaseRiskPolicy.canAutoActivate(r))
    }

    @Test
    fun forbidden_blocksAutoActivate() {
        val r = classify("os.system('rm -rf /')")
        assertEquals(ReleaseRiskPolicy.Verdict.FORBIDDEN, r.verdict)
        assertFalse(ReleaseRiskPolicy.canAutoActivate(r))
    }

    @Test
    fun lowRiskAllowsAutoActivate() {
        val r = classify("def serve(req): return {'ok': True}")
        assertEquals(ReleaseRiskPolicy.Verdict.LOW_RISK, r.verdict)
        assertTrue(ReleaseRiskPolicy.canAutoActivate(r))
    }

    @Test
    fun dataPathWrite_notForbidden() {
        // 写模块私有数据目录路径（/root/zero/data/<id>/）不触发 FORBIDDEN（允许模块持久化私有数据）
        val r = classify("open('/root/zero/data/mymod/state.json','w')")
        // 不 FORBIDDEN（是 NEEDS_PROPOSAL 或 LOW_RISK——系统路径写模式排除了 zero 子树）
        assertTrue(r.verdict != ReleaseRiskPolicy.Verdict.FORBIDDEN)
    }

    @Test
    fun reason_isHumanReadable() {
        val r = classify("import subprocess; subprocess.run(['ls'])")
        assertTrue(r.reason.contains("subprocess") || r.reason.contains("shell"))
    }
}
