# 当前代码快照 · 2026-10-16

Android 与后端统一源码位于 `MoodDairy/`，Android 版本为 1.4.6。请在 Android Studio 打开该目录。

本次快照包含功能与入口收拢、心理内容查找与测试卡片、放松页、时光信箱与情绪空间、规划请求超时调整、聊天键盘布局以及相应测试。

Windows 本地后端可使用仓库根目录 `start_chat_backend.cmd` 启动；它同时托管记忆渲染进程。安装 `MoodDairy/backend/requirements.txt` 中的依赖，并根据 `.env.example` 在本机配置环境变量。Android 本地 SDK 和 API 地址按项目原有方式配置，不随代码上传。

已知未完成项：电脑鼠标经过 Android Studio 内嵌模拟器后仍无法滚动页面。Android 内部滚轮注入测试通过不代表电脑到模拟器的完整链路通过，详见 `MoodDairy/docs/agent/keyboard-scroll.md`。

密钥、真实环境文件、用户上传/记忆数据、日志、构建缓存与本机模拟器配置不包含在此快照中。
