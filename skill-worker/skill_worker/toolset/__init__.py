"""toolset（Job 内注入，§4.5）：Skill 黑盒的平台能力供给与契约封装。

不装 skill-sdk 的 Skill 按裸约定读写文件同样成立（§4.7）；
本包即沙箱镜像内置的「skill-sdk 薄包」等价实现 + 平台注入能力。
"""

from .sdk import load_input, report_progress, save_artifact  # noqa: F401
