"""
工具注册表 + 自动发现（C1.1）。

- @register_tool(name, description, parameters) 装饰器注册
- import 本包即触发 tools/ 下全部模块发现（按模块名排序，保证 tools/list 顺序稳定）
- 重复注册名直接抛错（防手误覆盖）
"""
import importlib
import pkgutil

TOOLS: dict = {}  # name → {description, parameters, handler}


def register_tool(name: str, description: str, parameters: dict):
    def decorator(fn):
        if name in TOOLS:
            raise RuntimeError(f"工具重复注册: {name}")
        TOOLS[name] = {"description": description, "parameters": parameters, "handler": fn}
        return fn
    return decorator


def _autodiscover():
    for mod in sorted(pkgutil.iter_modules(__path__), key=lambda m: m.name):
        importlib.import_module(f"{__package__}.{mod.name}")


_autodiscover()
