package com.zero.v2.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class TriggerSettingsStatusTest {
    @Test fun selectedAppsDescribeConfigurationWithoutClaimingRuntimeHealth() {
        assertEquals("已配置 · 3 个应用", TriggerSettingsViewModel.configurationStatus(true, 3))
    }

    @Test fun emptySelectionAndDisabledSourceRemainExplicit() {
        assertEquals("待配置 · 尚未选择应用", TriggerSettingsViewModel.configurationStatus(true, 0))
        assertEquals("未启用 · 通知感知已关闭", TriggerSettingsViewModel.configurationStatus(false, 3))
    }
}
