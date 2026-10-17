# 日记关键词信笺

日记文字、图片下方的回顾使用原生 Compose / Canvas 纸质信封与散开词云。点击信封：翻盖约 600 ms，词语从 650 ms 开始按 85 ms 间隔沿曲线浮出，750 ms 后定格；支持收起和完整重播。最终词块散布在信封上方，不放进矩形纸卡。不使用 WebView、远程动画脚本或图片中固定的示例词。最多显示 12 个去重后的真实关键词，字号绑定相对权重，不代表情绪强度或心理评分。布局按实际文字尺寸及旋转后的边界避让，窄屏、长词和大字体时增加画面高度。

背景是米白与薄荷绿柔光，词块采用低饱和米黄、薄荷、粉、灰蓝、淡紫；虚线从信封口延伸，辅以叶片、圆点、纸张细纹和折痕。加载、更新和失败提示位于画面下方；分析依据默认折叠。文字修改后提示先保存，更新失败保留已有结果。

## 离线运行

安装 backend/requirements.txt。已有 backend/.local/models/bge-small-zh-v1.5 时，默认使用本地 KeyBERT + BGE CLS 嵌入，中文候选词使用 jieba 词性过滤；长正文分段嵌入后合并。没有本地模型时使用 jieba TextRank/TF-IDF。两条路径都不会下载模型或调用付费 API。设置 DIARY_KEYBERT_MODEL_PATH 可指定已准备好的本地 BGE/兼容 BERT 目录；DIARY_KEYWORD_ENGINE=textrank 可固定轻量模式。模型目录不存在或损坏时指定配置会明确报错。

情绪词使用本项目的小型词典与否定修饰识别，覆盖喜悦、安宁、期待、低落、紧张、疲惫、烦闷；提取原文词语，不做临床或语境推理。关键词和情绪词都必须出现在原文，无明确情绪时不补造标签。情绪词典不等同 UIE，复杂反讽、转述和跨句否定仍需用户自行理解。

### 空白页面与故障恢复

未加载完、没有非空文字块或语音转写时隐藏整个回顾模块；仅标题、照片或视频不作为显示条件，删除最后一段文字后也隐藏。尚未保存的正文可显示模块，仍沿用先保存再生成的约束。

服务端关键词提取超过 8 秒或模型异常时，使用无模型依赖的原文短语与情绪词典提取，保留原有版本检查、鉴权和加密缓存。如果客户端请求失败（不包括鉴权、资源不存在或版本冲突）且编辑器有已保存正文，则直接在设备上从该正文生成本地结果；分析依据中标注设备离线整理、尚未同步服务端。结果不添加参考图示例词，也不把客观记录强行标成某种情绪。空文字不生成词块。

2026-10-17 本次检查：Android 本地编译通过，回顾、隐藏规则和保存流程相关 14 项单元测试通过；后端关键词及回顾持久化相关 27 项测试通过，包含模型异常和冷启动超时后的真实原文结果。未启动仪器测试；普通 APK 更新复用正在运行的模拟器，保留数据和现有输入模式。

服务保持原有鉴权、内容版本与并发保护。只读取当前日记的文字/转写，不读取图片视频内容。新缓存版本为 3，关键词整体加密保存；旧回顾提示更新。已有其他多模态分析字段继续保留。

## 开源来源与许可

- KeyBERT 0.8.5：https://github.com/MaartenGr/KeyBERT ，MIT。
- BAAI/bge-small-zh-v1.5：https://huggingface.co/BAAI/bge-small-zh-v1.5 ，模型卡标注 MIT。权重由本机已有模型目录提供，不重复分发。
- jieba 0.42.1：https://github.com/fxsjy/jieba ，MIT，安装包包含其许可。
- Sentence Transformers 2.6.1：https://github.com/UKPLab/sentence-transformers ，Apache-2.0，作为 KeyBERT 兼容依赖安装。
- Transformers、Android Compose 等基础依赖各自遵循原许可，不把整个依赖树统称 MIT。
- 未引入用户参考的 xusenlin/uie-base（其模型卡标注 Apache-2.0）或 CSS 信封仓库；动画、排版和情绪词典为本次项目实现。
- 中文宋体：Google Fonts 的 Noto Serif SC，SIL Open Font License 1.1（不是 MIT）。为排除可变字体在 Android 上的空白文字兼容问题，已用 fontTools 将原始可变字体固定为 400 常规字重，移除可变轴数据。随应用保留完整许可 `app/src/main/assets/licenses/NotoSerifSC-OFL.txt`。字体来源：https://github.com/google/fonts/tree/main/ofl/notoserifsc 。`app/src/main/res/font/diary_serif.ttf` SHA-256：`c93084eeb7e44e58e523a5c8d7982259bf35c686f0ac6c7dddac710131db679d`，文件 14,854,716 字节，全部字体表可解码，cmap 包含 30,928 个字符映射，预览所用中文无缺字，逐字绘制像素非空。此项尚未进行 Android 设备验证。

## 验证

本地编译及 Android 单元测试；backend/tests/test_diary_review.py 覆盖持久化、隔离、版本、失败保留与并发；test_diary_keywords.py 覆盖原文约束、否定、无情绪文本、短文本和本地 KeyBERT 禁止联网测试。未启动设备测试、安装或重启设备应用。

### 重建静态设计预览

1. 项目根目录执行 `python tools/preview_diary_envelope.py --measure`，以应用附带宋体测量示例文字。
2. 执行 `./gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest -x ensureLocalBackend --console=plain`，单元测试调用与 Compose 相同的 Kotlin 布局算法，导出 `app/build/outputs/diary-envelope/geometry.json`。
3. 执行 `python tools/preview_diary_envelope.py`，生成 `docs/qa/diary-envelope-expanded.png` 与 `docs/qa/diary-envelope-closed.png`。

预览采用 Pillow 绘制，复用应用字体、Kotlin 实际布局输出与构图参数，标有“静态设计预览 · 示例文字 · 非设备截图”。它用于静态视觉核对，不证明 Android 字形测量、动画帧、点击区域或滚动在设备上的实际表现；这些仍未进行设备实测。示例词仅存在于预览夹具，不进入应用或离线提取结果。
