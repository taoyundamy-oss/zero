# 素材来源与生成记录

日期：2026-09-30。`visual-direction.png` 使用内置 image_gen（imagegen skill）生成并复制到此目录。该图已目视检查：浅深色、折叠屏布局、Zero环形标识均符合探索方向；图中设备外框/微弱渐变/英文文案仅作概念展示，正式实现遵循中文UI规范。SVG、Android XML、tokens与HTML是另行编写的可编辑设计源，不是对生成位图的像素转换。

生成提示词：

> Use case: ui-mockup. Create a polished visual direction board for ZERO, a personal Android AI assistant on a Samsung foldable. Show two large phone UI screens side by side: a warm off-white chat screen and charcoal dark chat screen, plus a compact icon specimen at top. Modern minimalist conversational UI inspired by ChatGPT's calm typography and spacious assistant answers, Android Material 3 ergonomics. User messages in subtle rounded gray bubbles, assistant text directly on canvas, elegant rounded bottom composer with plus, microphone and circular send. Top reads ZERO with tiny green status dot and subtitle Ready. Side navigation items Chat, Library, Workspace, Settings. One understated task card 'Module updated' with small 'Verified • Reversible'. Original ZERO mark: thick open circular zero orbit with a small offset solid square at upper right, white on black icon, monochrome bold geometric clarity reminiscent of minimalist Grok app aesthetics but not the Grok logo. Crisp flat product design, no glassmorphism, no gradients, no sci-fi neon, no fake phone camera hardware, no decorative wallpaper. Professional UI design sheet, generous margins, exact legible short English labels. Landscape composition.

动画主源为`zero-activity.svg`与`tokens.json`，正式Compose实现按视觉规范映射；无位图帧序列、无外部CDN、无大型动画依赖。预览页面不向服务器发送消息或采集音频。

验证：SVG/Android XML通过XML解析；tokens通过JSON解析；preview内嵌JS通过Node语法解析，静态src引用存在。生成位图已目视检查。内置浏览器因安全策略拒绝file://，因此未进行HTML浏览器渲染与交互验收；未通过其他方式绕开该限制。Android XML仅作为未接线设计源，XML解析不等于aapt资源编译。
