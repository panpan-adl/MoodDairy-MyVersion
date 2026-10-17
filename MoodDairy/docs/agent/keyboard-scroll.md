# 1.4.6 鼠标滚轮与聊天键盘布局

聊天页使用固定顶部、独立消息列表和底部输入区。主页面明确采用 adjustResize，KeyboardAwareScaffold 统一处理系统边距和键盘避让，并消费传给子页面的边距，防止重复留白。聊天时键盘显示即隐藏底部导航；关闭键盘后恢复。顶部快捷按钮避开状态栏。

消息列表从视觉底部锚定，按时间戳、发言方及重复序号生成稳定的界面标识；不将正文或流式状态放进列表 key，不改变聊天存储格式。查看最新内容时，回复增长和键盘变化保持底部可见；用户滚动查看历史时保留阅读位置，主动发送后才重新回到底部。错误提示、待确认计划和情绪支持卡片均处于消息滚动区域内，不挤占固定顶部。

已检查当前页面使用的 LazyColumn、verticalScroll、日历网格及画板手势。滚轮沿用 Compose 原生事件处理和系统滚动系数，没有添加重复的滚轮转发器或电脑鼠标钩子。鼠标应位于模拟器内的可滚动内容上；内层区域优先响应，弹窗滚动不影响背景，左键拖动与绘画保持原样。

设备测试使用虚构消息，通过系统注入 SOURCE_MOUSE / ACTION_SCROLL 事件，并实际展开、关闭当前软键盘，覆盖聊天历史、新回复、长回复、图片与入口卡片、普通页面、嵌套区域、弹窗和非聊天表单。测量固定顶部位置、消息区域高度、输入区与键盘边界，并回归 Enter/输入法确认不误发及回复期间仍可编辑。测试不发送聊天、不修改用户账号或历史。

模拟器截图由测试生成在应用外部文件目录 keyboard-scroll-qa 中；内容均为虚构。后端接口、模型请求和数据库没有变更。

本次验证：Android 构建成功，85 项本地测试通过，7 项新增设备交互测试通过；原有 10 项输入规则、主导航和支持卡片设备测试通过。键盘展开/收起截图已人工检查。鼠标测试在弹窗独立窗口坐标中注入移动与滚轮事件，避免测试把滚轮错误地送到背景窗口。

## 2026-10-16 当前未完成事项

以上鼠标用例仅验证 Android 内部注入事件。随后在 Android Studio 内嵌模拟器中用电脑端滚轮复现：拖动可滚动，但滚轮无法滚动。启用本机虚拟输入设备后已观察到 Linux REL_WHEEL 事件，页面仍未滚动；指针坐标/事件映射尚待排查，不能视为完整链路验收通过。用户停止电脑操作后已暂停排查。此机器上的模拟器输入设置不属于 APP 源码，也不随本次 Git 提交分发。

## 2026-10-16 鼠标模式配置修正（等待冷启动验证）

只读检查发现当前内嵌模拟器使用触控笔输入设备，没有鼠标指针控制器，也没有鼠标滚轮轴。本机 `C:/Users/Administrator/.android/advancedFeatures.ini` 原配置为 `VirtioTablet = on`、`VirtioMouse = off`；现改为 `VirtioTablet = off`、`VirtioMouse = on`。两个输入模式互斥，不能同时开启。修改前的配置备份为工作区 `.project-entry-backup/mouse-input/advancedFeatures.20261016-231252.ini`。

配置需要冷启动模拟器后生效；恢复旧快照不能作为新输入设备生效的依据。本次没有安装 APK、启动 instrumentation、注入鼠标操作或重启模拟器。当前只完成配置修正，尚未验证电脑滚轮到页面滚动的完整链路，不应标记为已修复或验收通过。等待用户选择重启时机；若重启，必须遵守根目录 AGENTS.md 的活动测试检查及应用前台恢复要求。

用户随后发送“冷启动”，已据此检查无活动 instrumentation 后正常关闭模拟器，并用 `-no-snapshot-load` 冷启动同一个 AVD 和端口。未安装应用、未清除数据、未运行测试。启动完成后执行根目录 `restore_emulator_app.ps1 -DeviceId emulator-5554`，应用前台持续检查通过。只读输入诊断已出现 `VSCROLL: source=MOUSE`，说明鼠标滚轮轴已注册；电脑实际滚轮能否带动页面仍待用户确认。

后续用户反馈：`VirtioMouse = on` 模式下内嵌模拟器点击没有反应。因此已撤回该配置，恢复 `VirtioTablet = on`、`VirtioMouse = off`，并通过 Android Studio 设备管理器的 Cold Boot 入口启动。禁止将此前仅注册滚轮轴的结果视为滚轮修复成功；本机的鼠标模式会造成点击回归，不应再次直接启用。电脑滚轮问题仍未解决，优先保留原先可点击、可拖动的输入模式。

## 2026-10-17 独立窗口兼容入口（尚未启动）

用户再次反馈电脑滚轮无效。只读检查显示 emulator 36.5.10 当前仍为 VirtioTablet on / VirtioMouse off，Android 输入设备为 QEMU Virtio Tablet，Motion Ranges 没有 VSCROLL。读取本机 Android Studio 的 EmulatorView 输入实现，确认内嵌窗口转发 WheelEvent；不把此前 Android 内部注入成功当作电脑端验收。

官方模拟器 Qt 窗口 `wheelEvent` 在 VirtioMouse、VirtioTablet 均关闭时支持把滚轮转换为触摸滑动；内嵌窗口转发的原始 wheel 事件不走该 Qt 回退路径。根目录新增 `start_emulator_wheel.ps1`，准备通过独立可见窗口和单次启动参数 `-feature -VirtioMouse,-VirtioTablet` 使用该兼容路径。全局配置保持原样，关闭快照读写，不安装 APK、不清数据、不运行 instrumentation。脚本默认只预览，只有显式 `-ColdBoot` 才重启；重启前核对设备、AVD 和全部活动 instrumentation，启动后调用应用恢复脚本并检查前台。

依据：https://android.googlesource.com/platform/external/qemu/+/refs/heads/emu-main-dev/android/android-ui/modules/aemu-ui-qt/src/android/skin/qt/emulator-qt-window.cpp ，以及同分支 `android-qemu2-glue/qemu-user-event-agent-impl.c`。已通过 PowerShell 语法解析。遵照用户此前“发冷启动再重启”的要求，本次未重启或操纵 APP；需要用户发出冷启动指令后再启动并确认真实鼠标的滚动、点击效果。该方案尚未验收，不得报告已修复。

用户随后发送“冷启动”，已执行根目录 `start_emulator_wheel.ps1 -ColdBoot`：核对同一 AVD 且无活动 instrumentation 后正常关闭原实例，使用单次兼容参数冷启动独立窗口。未安装 APK、未清数据、未运行设备测试；恢复脚本确认 APP 持续前台。已通过 Windows 窗口截图确认独立模拟器可见且助手页面已打开，并将窗口置于前台。真实电脑鼠标的滚动与点击仍待用户确认。

## 2026-10-17 滚轮复发原因与入口统一

用户已反馈上述独立窗口模式“可以了”。随后再次失效时，只读获取当前进程命令行发现参数变成了 `-feature -VirtioMouse,VirtioTablet`，与已验证的兼容模式不同；根目录 run_latest.ps1 同时存在这一冲突配置。此次复发可定位为重新启用 VirtioTablet，而非 APP 滚动控件回归。

新增根目录 emulator_input.ps1 作为两个正式启动入口的统一参数来源：关闭两种指针模式、关闭快照加载/保存、显示独立窗口。run_latest.ps1 移除绘图板参数和隐藏窗口启动，复用公共参数，安装前检查活动 instrumentation，更新后调用恢复脚本；发现已经运行的绘图板/相对鼠标设备时明确报错，避免静默沿用错误输入模式。start_emulator_wheel.ps1 同样复用公共参数，保留麦克风及后端检查。

本机 advancedFeatures.ini 已备份并持久改为 VirtioMouse off / VirtioTablet off，正式入口仍显式传入相同参数。AGENTS.md 和 LATEST_PROJECT.md 已记录约束，避免后续聊天再次通过启用绘图板来处理鼠标捕获问题。未安装测试包、未运行 instrumentation；脚本语法及公共参数检查通过。当前修复恢复的是此前经用户确认的滚轮输入路径，真实电脑滚轮仍由用户确认，不以输入设备诊断代替实际操作验收。

### 后续再次复发的确切来源及电脑端验证

### Studio 默认启动入口修正与验证

之后重启再次失效时，进程参数是 `-qt-hide-window -grpc-use-token`，但两种指针模式均已关闭，说明另一条复发链路来自 Studio 内嵌启动。此前中断的设置修改没有落盘，不能报告其已生效。现已实际通过 Studio Settings → Tools → Emulator 取消 `Launch in the Running Devices tool window` 并点击 OK；只读确认 `%APPDATA%/Google/AndroidStudio2025.3.2/options/emulator.xml` 存在 `launchInToolWindow=false`。

在无活动 instrumentation 时正常关闭原实例，然后实际通过 Studio Device Manager 的 Cold Boot 启动同一 AVD（不是通过专用脚本代替验证）。新命令行是 `-netdelay none -netspeed full -no-snapstorage -avd Medium_Phone_API_36.1`，没有 `-qt-hide-window`；输入模式及前台检查通过。通过电脑端 sky.scroll 在个人中心向下滚到退出登录与版本号，再向上滚回头像，均正常。未清数据、未安装测试包或运行 instrumentation。后端与麦克风已恢复。

Assert-EmulatorWheelMode 现除 Android 输入设备外还检查对应 qemu 进程命令行，拒绝 `-qt-hide-window` / `-no-window`，防止输入设备正确却仍在内嵌窗口的情况漏检。Studio 的 Running Devices 面板“+”明确启动内嵌窗口，应使用 Device Manager 的启动按钮/Cold Boot 或根目录正式入口。

只读核对另一个聊天“修复报错”的近期命令，确认其在 2026-10-17 09:09 左右启动 emulator.exe 时直接传入 `-feature -VirtioMouse,VirtioTablet`，绕过已有公共入口；运行进程命令行及 Android QEMU Virtio Tablet 设备与之对应。本机配置文件和两个正式启动脚本保持关闭模式，不能把此次复发误判为这些文件被改回。

增加 Assert-EmulatorWheelMode，共享恢复脚本在确认 APP 前台后也检查输入设备，冲突模式不再静默通过。专用冷启动入口同样检查。统一 Android 工程新增 AGENTS.md，明确禁止手写启动参数、禁止为渲染/点击/语音问题启用绘图板或相对鼠标模式；根目录增加可双击的 start_emulator_wheel.cmd。四个启动/恢复脚本语法检查通过，本地模拟检查确认触摸回退模式通过、两种冲突设备均被拒绝。

本次按用户修复要求重启同一 AVD，原有数据保留，恢复脚本前台及输入模式检查通过。随后使用 Windows computer-use 的 sky.scroll 从电脑端向独立窗口发送滚轮，实际观察个人中心从顶部滚动到退出登录/版本号，再反向滚回头像卡片；页面固定标题及底部导航未滚动，点击“我的”成功。此验证经过电脑窗口输入路径，不是 Android 内部注入；未运行 instrumentation、未安装测试包。用户自己的物理鼠标效果仍可继续反馈。不会拦截系统进程或增加强制重启/前台 watchdog，任意外部手写参数仍可能绕过脚本；正式路径现有检查会明确报告此类冲突。
