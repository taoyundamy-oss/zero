"""
SSRF 防护（D106）——单源（S5.1 基建收敛）。

原 gateway/ssrf.py 与 worker/ssrf.py 为镜像副本（部署单元分离、约定"保持同步"）——
实测已漂移：worker 副本缺 CGNAT 100.64/10 显式阻断（阿里 metadata 段，依赖 stdlib
is_private 版本行为）。收敛取更严的 gateway 版；两镜像经 build context COPY 本文件
（gateway/Dockerfile / worker/Dockerfile），消费方 `from ssrf import validate_url` 不变。

三层防线（D106）：
  L1（本模块）  scheme/端口白名单 + 域名解析全部 IP（含 IPv6）非私网/CGNAT 段
  L2           出口工具（cloud_fetch / cloud_download / research httpx）发起前调用本模块
  L3           宿主机 DOCKER-USER egress 阻断（C0.1 已部署，兜底 redirect/rebinding 竞态）

阻断段：127/8 · 10/8 · 172.16/12 · 192.168/16 · 169.254/16 ·
        100.64/10（阿里 metadata 100.100.100.200 所在 CGNAT 段）·
        ::1 · fc00::/7 · fe80::/10
端口白名单：80 / 443 / 8443
"""
import ipaddress
import socket
from urllib.parse import urlsplit

ALLOWED_SCHEMES = {"http", "https"}
ALLOWED_PORTS = {80, 443, 8443}


def _blocked_ip(ip_str: str) -> bool:
    try:
        a = ipaddress.ip_address(ip_str)
    except ValueError:
        return True
    if a.is_private or a.is_loopback or a.is_link_local or a.is_reserved or a.is_multicast:
        return True
    # CGNAT 共享段（阿里云 metadata 在此段，显式阻断——不依赖 stdlib 版本行为）
    if a.version == 4 and a in ipaddress.ip_network("100.64.0.0/10"):
        return True
    if a.version == 6 and a in ipaddress.ip_network("fc00::/7"):
        return True
    return False


def validate_url(url: str) -> tuple[bool, str]:
    """D106 L1 校验。返回 (ok, reason)；reason 供 trace/报错。"""
    try:
        parts = urlsplit(url.strip())
    except Exception:
        return False, "URL 解析失败"

    if parts.scheme.lower() not in ALLOWED_SCHEMES:
        return False, f"scheme 不允许: {parts.scheme}"
    if parts.port is not None and parts.port not in ALLOWED_PORTS:
        return False, f"端口不允许: {parts.port}"

    host = parts.hostname
    if not host:
        return False, "缺少主机名"

    # IP 字面量直接校验
    try:
        ipaddress.ip_address(host)
        if _blocked_ip(host):
            return False, f"IP 不允许: {host}"
        return True, "ip_literal"
    except ValueError:
        pass

    # 域名：解析全部 A/AAAA，任一命中阻断段即拒绝（rebinding 竞态由 L3 兜底）
    try:
        infos = socket.getaddrinfo(host, None)
    except Exception:
        return False, "域名解析失败"
    ips = sorted({i[4][0] for i in infos})
    for ip in ips:
        if _blocked_ip(ip):
            return False, f"解析到内网地址: {ip}"
    return True, f"resolved:{ips[:3]}"
