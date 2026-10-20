package com.zero.v2.services.llm

/** DIRECT preserves old non-BYOK routing. Billing is ultimately controlled by Cloudflare. */
enum class GatewayMode(val label: String) {
    DIRECT("直连 / 自定义兼容服务"),
    CF_WORKERS("Cloudflare Workers AI"),
    CF_BYOK("Cloudflare Gateway · 默认 BYOK"),
    CF_GATEWAY("Cloudflare Gateway · 服务端计费策略"),
}

fun LlmProfile.effectiveGatewayMode(): GatewayMode =
    if (gatewayMode == GatewayMode.DIRECT && cloudflareByok) GatewayMode.CF_BYOK else gatewayMode
