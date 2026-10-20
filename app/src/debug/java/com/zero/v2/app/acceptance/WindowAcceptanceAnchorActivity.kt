package com.zero.v2.app.acceptance

/** Debug-only task root: keeps Samsung's split stage alive between isolated fixture activities. */
class WindowAcceptanceAnchorActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(android.widget.TextView(this).apply {
            text = "实机窗口验收准备中"
            gravity = android.view.Gravity.CENTER
        })
    }
}
