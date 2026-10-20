package com.zero.v2.core.moduleapi

/**
 * 配置字段注解 — 用于 AutoFormUI 自动生成配置表单。
 *
 * 对标 Alife 的 [Description] 属性模式。
 * 被此注解标记的 ModuleConfigBase 子类属性，
 * 将由 ModuleConfigParser（在 onAwake 时）一次性解析为
 * List<FormFieldState>，Compose UI 只遍历 State 列表，零反射。
 *
 * @property label 显示标签
 * @property description 字段说明（可选）
 * @property fieldType 控件类型（TEXT / NUMBER / TOGGLE / DROPDOWN / SLIDER / MULTILINE）
 * @property min 数值最小值（NUMBER/SLIDER 类型使用）
 * @property max 数值最大值
 * @property options 下拉选项（DROPDOWN 类型使用）
 * @property password 是否掩码显示（密码类字段）
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConfigField(
    val label: String,
    val description: String = "",
    val fieldType: ConfigFieldType = ConfigFieldType.TEXT,
    // N4.1：默认 max=100 是脚枪（忘写界的数值字段被静默钳到 100，如 maxTokensPerRun 默认 50000 越界）。
    // 默认改为"无上界"——安全性由各字段显式定界（§7.3）+ flushConfigChanges 写入钳位（§7.4 #3）保障。
    val min: Double = 0.0,
    val max: Double = Double.MAX_VALUE,
    val options: Array<String> = [],
    val password: Boolean = false
)

/**
 * 配置字段 UI 控件类型。
 */
enum class ConfigFieldType {
    /** 单行文本输入 */
    TEXT,
    /** 数值输入 */
    NUMBER,
    /** 开关 */
    TOGGLE,
    /** 下拉选择 */
    DROPDOWN,
    /** 滑块 */
    SLIDER,
    /** 多行文本输入 */
    MULTILINE,
    /** 密钥字段 — 存储为 apiKeyRef 引用，UI 显示 ******** */
    SECRET
}
