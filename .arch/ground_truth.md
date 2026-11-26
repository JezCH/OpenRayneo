# 已验证现实事实与硬约束 (Ground Truth)

- [GT-01] (已验证) 用户明确：目前无法自定义眼镜本身的功能；OpenRayneo的主要目的是引出眼镜已有官方功能，再做二次处理。 -> 架构约束：以现有官方功能及已验证接入路径为能力边界；伴侣软件负责能力接入、输出获取、基础二次处理与对外开放。不得假设可修改眼镜固件、增加设备原生功能或存在尚未证实的官方输出。此前增量交付、任务管理等决策均在这个边界内解释。
- [GT-02] (已验证) 用户确认手机本机已有Agent软件，OpenRayneo沿用现有方式暴露本地API即可供其接入。 -> 架构约束：手机原生助手采用本机API与本机Agent对接；Agent负责对话理解、回复及其自身模型或网络服务。OpenRayneo无需为此另建远程Agent实时接入路径。
- [GT-03] 用户已授权按已确认架构开发，可委派子代理并行。图中早期“仅规划／后续实施”是原决策阶段描述；当前实现见下方快照及代码。solid表示决策已确定，构建、接口验证和实际质量分别记录。保留用户已有未提交工作。
- [GT-04] (已验证) 用户明确更新说话人处理策略：眼镜双通道输入的通道1默认属于本人，只对通道2做声纹匹配和说话人分段；全天智记默认回传混合声音，需要对完整混合音频运行说话人处理。 -> 架构约束：双通道必须保留并消费两路：本人通道走ASR与固定本人归属，环境通道走ASR与声纹/分段；按真实输入模式区分全天智记混合单声道，禁止把所有单声道输入默认当本人。音频落盘、延迟补齐、转写来源、时间锚点和Agent交付保持通道出处与旧资料兼容。
- [GT-05] 原生“小雷小雷”连续交互复用本机Agent对话。录音记录、任务、wakeID／SID和Agent会话身份分别管理，回复核对当前轮次；显式“新建对话”轮换身份，取消仅影响归属请求。
- [GT-06] 用户明确要求Mac每次原生capture自动保存最近一轮完整WAV，直接用于比对。独立于ASR确认流，无arm／等待窗口或额外60秒保存截断；下一完整轮成功后旧音频Trash，异常轮保留上一有效latest并区分captureID。
- [GT-07] 用户明确以`.arch/`为项目核心文档，只保留少量操作与合同说明。常规文档收敛到根README、运行指南、核心协议和同步契约；更新现有事实／节点，停止新增逐轮报告和重复架构图。六个核心文件由主Git按普通文件跟踪，`.arch/.git`影子库仅本机保留并忽略。

## 当前实现与未解决问题

更新：2026-11-17。本节替换当前状态，不追加逐轮流水。

| 范围 | 当前事实 |
| --- | --- |
| 平台职责 | 双端独立接眼镜。Mac面向本机Agent、工作流及测试，全天输入主要作实验；手机面向日常与全天记录。语义决策／通用回复归外部Agent，伴侣保留基础音频、ASR、记录及内建总结。 |
| 记录 | 全天时间线＋总结；其他入口独立时间／内容／总结，统一日月日程。原音频、样本偏移与墙钟锚点保留；资源不足优先保存再补处理，历史纠正保留来源。 |
| 同步 | 手机源权威、Mac持久副本／离线派生，文字／人物／总结默认补齐，原音按需。手机独立LAN入口8767及sync token；设备／Agent接口回环。精确字段见[同步契约](../docs/record-sync-contract.md)。 |
| 原生Agent | Android36起＋DSH0.1.8按phone／conversation key／preset持久绑定，跨wake续历史；当前37沿用。30项隔离真实DSH SDK检查覆盖历史、冷重启、取消归属和失败不重放。 |
| Fork后续事项 | 手机记录详情可显式选取最多20段原文（每段400字符）请求已有DSH无工具hint提取候选。候选不等于授权；用户逐项确认标题与时间后才使用已有待办／日程写入及回读。加密记录保留专用followup:派生项、源版本、已尝试／确认／未知状态；写前持久化，禁止自动重放及外部注解覆盖专用ID。独立策略与模拟记录测试通过；Android构建、实际模型提取质量和设备回读另行验收。既有直接Agent工具权限不变。 |
| Fork全天提示 | 新增独立ProactiveHintPolicy；当前全天语音走已有DSH hint（无工具），语义判断仍由外部模型完成。发话后60秒失效，待领提示合并为最新一条，显式任务优先，提示队列不持久化。同一记录30秒内重复正文不显示，旧序号或不再拥有显示权的结果不显示。native／realtime／历史补齐路径保持原有行为。独立JVM策略与模拟relay测试通过；Android构建和实机效果分别验收。 |
| 手机ASR | `asrEngine`四选（Fork未设置且无云端Key时默认`stream`，未设置语言提示默认`ko`，保留显式选择）：云端Bailian `qwen-audio-3.1-asr-flash-message`（中间结果、`disfluency_removal_enabled`、`vad_model=near_meeting_16k`；原生wake把中间结果以0x0d type5 `final:false`推给眼镜）。实机日志：回答后眼镜在播完时发type11续轮；旧流程回答前先发2 rc2关收音，故答中说话无效。现眼镜workMode=1（连接时自动读设置）时不关收音，回答后立即开下一轮（只发2 rc1不发9 rc1），答中原生麦克风（48k）10ms电平连续≥`bargeInMinMs`（默认250ms）超过`bargeInLevelDb`（默认-30dBFS）即发9 rc1打断（不等ASR），另首个中间／最终文字（≥2字且不在答案文本中）也打断，答中峰值电平写入日志供校准，未打断则等type11再发9 rc1；无AEC时回声误触发待实机确认；`stream`=HF下载的韩语流式zipformer（int8 encoder/joiner＋fp32 decoder，端点0.8s）；`local`=SenseVoice整句＋语言提示过滤（韩文文本不因语种标签丢弃）。`nemotron`=Nemotron 3.5多语流式0.6B 560ms int8（sherpa≥1.13.3，stream option `language`：单一提示ko-KR／en-US，否则auto；4线程；应用内从HF第三方镜像下载，官方仅tar.bz2）。本地模型优先读取用户复制到`/sdcard/OpenRayneo/models/<官方归档目录名>/`的文件（需MANAGE_EXTERNAL_STORAGE，重装保留）；有该权限时应用内下载也直接写入该目录，已在应用内的副本可一键移出（核对大小后删内部副本），否则下载到应用内部。本地各引擎均先过内置GTCRN流式降噪；`asrKeepLoaded`（默认开）时所选本地模型在服务启动／保存设置后预加载并常驻，其余模型立即释放，关闭则空闲60s释放。实时双路：每20ms若ch1比ch2高≥`selfVoiceMarginDb`（默认6dB）且ch1>-50dBFS，视为本人说话，ch2静音并保持`selfVoiceHoldMs`（默认300ms；判定下限`selfVoiceFloorDb`默认-50dBFS，三项均在语音设置滑块调整）（判比值不判绝对音量，外部大声时两路同升不触发），录音原样、backfill同样适用。全天A3的vpuMask／vadMask按每20ms帧闸门实时ASR／speaker输入（前200ms后500ms，原音不闸门，`glassesVoiceGate`可关）；原生type3无mask，轮起止清空待用flag；实时翻译遇stream改用已安装SenseVoice或Nemotron；未安装时提示准备本地模型，不再自动回退云端。mask语义与降噪对识别率的影响待实机确认。 |
| 声道与身份 | ORPCM1旧mono／ORPCM2双路兼容；真实双路ch1默认本人、ch2完整speaker，native／AON mono全量处理。Opus编码路数与物理来源分别看待。 |
| 人物修正 | Android37显式绑定归并来源匿名，历史ID保留；候选仅过滤与已命名人物同model、完全相同的匿名重复参考。短段保留、重叠仅标实际交集。 |
| Mac分析 | 离线连续PCM整段SDK聚类，保持SDK成员关系；实时仍用原8秒VAD批次。最新原生会话流式WAV＋latest索引，导出含只读`matchingDiagnostics`标量，无原始向量。 |
| 当前产物 | Mac `.build/speaker-match-diagnostics/OpenRayneoBridge.app`（同签名Debug）；Android CI构建versionCode=1000+GITHUB_RUN_NUMBER（本地37）、BuildConfig.GIT_COMMIT显示于设置、versionName=1.(run-23).0逐次递增并用于Nextcloud文件名（本地1.2.0-beta.4），DSH0.1.8。上一Mac自动保存候选、Android36 release作回退，路径见[运行指南](../docs/runbook.md)。 |
| 验证范围 | 构建／签名、存储／API／同步及定点生产类检查完成，独立审查已闭环。声音模型、真实持续吞吐、功耗及各固件硬件行为按实际输入继续验收。 |
| Fork Android 安装路径（2026-11-26） | 用户选择 #144 为新的 validation 签名基线，applicationId=com.openrayneo.android.validation，versionCode=1144，证书SHA-256 ed9341632021ba4129608624a86309155b8373f01ec9b16dff2e5863eb440965。PR48/49/50 已合入 main，#144 APK构建通过，但与旧validation安装冲突，实际新安装和数据保留尚未验收。旧包不可擅自卸载或清数据；正式包com.openrayneo.android保持原签名策略。更新仅允许同证书且更高versionCode；应用内下载和PackageInstaller已实现，公开Releases和连续真机更新未验收。普通main/PR仅编译，显式main dispatch或当前main的validation-package分支生成artifact；公开范围获准后才使用validation-publish或dispatch的发布开关。 |

| Fork Android 韩语功能（2026-11-25） | PR #23 独立本地字幕已合入，Android #52／main #53构建成功。PR #24（main 3c6a7ba）手机系统语音输入默认ko-KR、保留可选英文／中文；本地韩语数字命令、语言／历史人物与记忆标签显示、韩文Smart Follow字符边界及韩语导航文本解析；国内地图仅显式选择的实验性系统navigation通知，方向与距离严格匹配，冲突拒绝，15秒过期，不保证实际各应用通知格式。保留原协议`zh-CN`页面设置及设备报告语言白名单，不发送未经确认的韩语固件唤醒词／AI语言值；官方AI权限、韩语固件TTS、字体和硬件表现未验证。74项定点JVM断言及Android #54双APK构建、签名、DSH打包与安装包组装通过；用户设备未安装，固定签名仍未设置。 |

**当前主问题：真人声纹漏认与单人误分组。** 已保存真实59.64秒native输入，用户确认仅本人讲话，结果5段／3组、最高匹配0.624（其余0.602／0.399），当前门槛0.70。6份登记参考留一互验有2份仅0.659／0.674；同一native录音A／B互cos0.641。短句特征不稳与登记／匹配标准不协调已有实证，登记ch1与native mono收音差异的独立贡献待验证；当前模型／门槛／档案未因此调整。

本机匿名指标与模型条件集中在`analysis/speaker-debug-2026-11-16/case.json`，公开四人对照音频在其`samples/`；原始本人WAV仍由App的latest策略维护。后续用这些已确认同人及异人样本标定，先核人声／噪声筛选与短句聚合，避免只凭“登记成功”判断可识别。

**其他已知边界：** 官方账户／官方云上传下载／HTTP代理为停用分支；跨设备声纹向量同步未实现，Mac与手机声纹参考独立。Android原生离线录音导入、真实全天后台存活及耗电仍有端侧验收差距。窗口焦点会同步前台标记，但曾发生的唤醒恢复尚无单一因果结论；保留正常连接后先查事件，不盲写AI配置或重复配对。

## 实现与证据入口

- `Sources/OpenRayneoBridge/`：设备接入、记录副本、原生助手、声音分析与桌面API；`Sources/AssistantCore/`：助手引擎。
- `android/app/src/main/java/com/openrayneo/android/`：手机服务、音频／人物／记录、API与同步；`integrations/dsh-go/`：外部Agent适配。
- [核心协议](../docs/protocol.md)维护消息／连接合同及原始逆向索引；[运行指南](../docs/runbook.md)维护构建、安装、故障恢复；[API技能](../.agents/skills/openrayneo-api/SKILL.md)维护精确HTTP参数。
- 唯一旧源差分仍在Ubuntu `openrayneo-lab`归档；`analysis/`保留原始逆向证据、模型实验源和本机指标，构建产物与用户Library资料分别管理。协议／CLI的已发送、设备确认与佩戴可见效果分别核验。
