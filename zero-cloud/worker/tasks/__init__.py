"""Worker 任务模块注册（显式导入；C3 逐步追加）"""
from tasks import ping  # noqa: F401
from tasks import python_exec  # noqa: F401
from tasks import research  # noqa: F401
from tasks import file  # noqa: F401
from tasks import download  # noqa: F401
from tasks import bili_login  # noqa: F401
from tasks import wechat_pull  # noqa: F401
from tasks import media  # noqa: F401  # S6.1（D119）：cloud_media（media 专队，worker-media 并发=1）
