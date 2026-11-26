# 运行指南

目标、模块和当前完成度统一见 [架构事实](../.arch/ground_truth.md) 与 [看板](../.arch/dashboard.html)。本页只维护可直接操作的安装、运行与恢复步骤。

## 当前本地候选

- Mac：[声音诊断应用](../.build/speaker-match-diagnostics/OpenRayneoBridge.app)；上一自动保存版位于 `.build/speaker-compare-candidate/`。
- Android：[37 release](../android/app/build/outputs/candidates/android37-1.2.0-beta.4/OpenRayneo-v37-release.apk)、[37 debug](../android/app/build/outputs/candidates/android37-1.2.0-beta.4/OpenRayneo-v37-debug.apk)，版本 `1.2.0-beta.4`。
- DSH：[插件0.1.8](../android/app/build/outputs/candidates/android37-1.2.0-beta.4/openrayneo-dsh-0.1.8.tgz)。Android36及之后沿用该版插件。
- Android回退：[36 release](../android/app/build/outputs/candidates/android36-1.2.0-beta.3/OpenRayneo-v36-release.apk)。同签名覆盖安装保留配置与记录，私有库与Keystore一起保留。

这些是开发机产物；当前源码构建以本页命令为准，仓库不提交安装包、模型下载物或私钥。

## 构建

Mac已使用Apple Silicon / macOS 15验证，需要Xcode Command Line Tools与Opus。模型和动态库由现有准备／打包脚本处理。

```sh
sh scripts/prepare-mac-voice.sh
swift build
OPENRAYNEO_APP_DIR="$PWD/.build/dev-candidate/OpenRayneoBridge.app" sh scripts/build-app.sh
open .build/dev-candidate/OpenRayneoBridge.app
```

打包使用现有脚本的签名、内嵌库与RPATH流程。读取已有声纹时使用正常签名的App；独立未授权helper可能触发钥匙串提示。分发包通过 `sh scripts/package-release.sh` 生成，发布与上传按相应任务授权执行。

Android需要JDK17、SDK Platform36 / Build-Tools36.0.0；使用 `ANDROID_HOME` 或不提交的 `android/local.properties` 指向本机SDK。

```sh
cd android
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug -Popenrayneo.abis=arm64-v8a,x86_64 --console=plain
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.openrayneo.android/.MainActivity
```

macOS的Homebrew JDK17可通过 `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` 选择。默认Gradle输出在下一次构建生成；固定交付件放 `outputs/candidates/<版本>/`。原开发签名用于覆盖升级，保留 `~/.android/debug.keystore`，不在文档或源码写入密码。系统阻止ADB安装时，把APK推送到手机Download后由用户手动安装。

## Fork Android 안전 검증판 (2026-11-25)

- GitHub [Android APK](https://github.com/JezCH/OpenRayneo/actions/workflows/android-apk.yml)에서 실기기 시험 준비 후 main을 선택하여 **Run workflow**(workflow_dispatch)로 명시적으로 배포한다. 성공한 해당 실행의 검증 앱의 `openrayneo-validation-update-<commit>` artifact ZIP을 다운로드한다. manifest와 APK, DSH 플러그인은 같은 빌드의 묶음이다. 일반 push/main 및 pull_request는 Kotlin 컴파일 및 기존 정책 검사만 수행하고 설치 APK/외부 업로드를 생성하지 않는다.
- 기존 설치 OpenRayneo(`com.openrayneo.android`)의 서명과 배포 APK 서명이 같다는 근거가 없으면 `app-release.apk`로 업데이트하지 않는다. 실제 main #41과 #43은 서로 다른 임시 인증서였으며 Android 정상 업데이트가 불가능하다. 앱 삭제·데이터 초기화로 서명 오류를 회피하지 않는다.
- 대신 패키지 `com.openrayneo.android.validation`인 `openrayneo-validation.apk`를 **별도 설치**한다. 원본 앱의 로컬 암호화 기록과 설정은 그대로 남고 검증판은 새 기록·새 설정을 사용한다. 기존 앱의 안경 연결/백그라운드 BridgeService를 먼저 중지해 BLE 및 로컬 8766 포트 충돌을 방지한다. 검증판에 주변 기기·알림 권한을 별도 부여한다. 모델 파일 경로 `/sdcard/OpenRayneo/models`는 공유될 수 있다.
- 묶음에 포함된 `openrayneo-dsh-*.tgz` 버전과 실제 설치 DSH 플러그인을 맞추고 엔진을 재시작한다. 안경 연결 후 기존 Assistant 화면의 **AI 연결 및 안경 표시 진단**을 펼쳐 모델 요청 비용 가능성 안내를 확인하고 직접 동의하면 임의의 테스트 코드만 전송한다. 휴대폰에서 코드 일치/응답시간을 확인하고 필요할 때만 동일 코드를 안경으로 보낸 후 사용자가 실제 표시 여부를 확인한다.
- 현재 개발 대상은 `com.openrayneo.android.validation`의 #144 (versionCode 1144, SHA-256 `ed9341632021ba4129608624a86309155b8373f01ec9b16dff2e5863eb440965`) 서명 계보이다. CI는 해당 인증서와 더 높은 버전만 검증 앱에 허용한다. 기존 검증 앱과 설치 충돌은 별도 문제이며 기존 기록 삭제·초기화 승인은 없다. 정식 앱은 이전 서명 기준을 유지하며 이 개발 범위에서 제외한다.
- 명시적으로 APK 제작이 필요한 경우 main의 Run workflow에서 validation을 선택하거나, 검토·검증된 현재 main 커밋으로 `wearable/validation-package` 브랜치를 생성/fast-forward한다. 두 방식 모두 동일 인증서로 검증 앱 APK와 manifest·DSH·대응 소스를 Actions artifact에 저장한다. 일반 main/PR은 컴파일만 한다. 브랜치에는 별도 소스 변경을 넣지 않으며, force push하지 않는다.
- 공개 GitHub Releases 배포는 공개 범위를 승인받은 후에만 main dispatch의 `publish_validation=true` 또는 현재 main으로 `wearable/validation-publish` 브랜치를 명시적으로 fast-forward하는 방식으로 실행한다. 이 두 번째 브랜치는 공개 배포를 의미하므로 일반 빌드에 사용하지 않는다. 배포 전 현재 main 일치, 고정 인증서, 버전·해시를 검증하고 draft에 APK·manifest·관련 소스를 첨부한 뒤 공개한다. 앱에서 설정 → 앱 업데이트로 확인·다운로드·설치한다. #144 실기기 설치 및 연속 업데이트는 아직 검증되지 않았다.
- 신규 진단은 DSH 기본 모델과 HUD 경로만 확인하며 제조사 AI 이용권·ChatGPT 구독·일반 Agent 도구까지 증명하지 않는다.

## 连接与恢复

1. 普通切换先在占用端断开眼镜，保留已有配对；Mac、手机和官方App同时争用会阻塞数据通道。
2. Mac在“设备连接”选择已有配对，连接并启用API；Android授予附近设备权限后选择目标。首次配对用应用配对流程并由用户确认系统提示。
3. 分别检查BLE认证、RFCOMM数据通道及页面／音频业务状态；Mac `/health` 的 `ok` 是服务状态，设备操作还需 `bleReady` 与 `rfcommConnected`。
4. `channel did not finish opening` 时保留诊断，清理本次失败连接；确认手机／其他App释放连接、眼镜已展开佩戴并亮屏，再连接一次。不要连续点击连接、常规恢复不要删配对或清应用数据。
5. API地址和token从当前“Agent工作台／设备连接”复制。GUI优先8765，端口占用可选备用端口，连接服务重启会换token；使用界面当前值。
6. 眼镜没有唤醒响应时先区分设备无响应与Mac没收到：读取native事件及连接状态，再核助手／语音唤醒／唤醒词实际回报。助手页面尚未退出时先回到表盘。GUI默认勾选或已发送指令不能代替设备读回。

Mac GUI退出会停止自己持有的后台服务；“重启连接服务”仅重启该worker，不重置眼镜或系统蓝牙。蓝牙bond损坏时才重新配对；细节见 [协议](protocol.md)。

## Agent与记录同步

语音响应、等待／提示、工具和工作流由本机外部Agent负责；OpenRayneo负责音频、基础转写、记录与官方通路回写。Mac工作台也可“仅启动记录与Agent服务”，不连接眼镜：

```sh
.build/debug/openrayneo-bridge --headless --local-services-only
```

| 入口 | 默认监听 | 凭据 |
| --- | --- | --- |
| Mac设备／Agent API | loopback `8765`，GUI可选备用端口 | 当前Mac token |
| Android本机Agent API | loopback `8766`，设置中启用 | 手机本机token |
| 手机与Mac记录同步 | 手机LAN `8767`，独立启用 | 专用sync token |

接口与会话参数见 [API技能](../.agents/skills/openrayneo-api/SKILL.md)。请求脚本使用 `OPENRAYNEO_API_URL` / `OPENRAYNEO_API_TOKEN` 的本机安全配置，勿打印凭据；`GET /health`无需token，其他路由使用Bearer。

手机在“设置 → DSH Go与外部Agent”启用本机入口；DSH选择“本机 / This phone”，先验证普通聊天。插件安装／工具见 [插件说明](../integrations/dsh-go/README.md)。tgz顶层为 `package/`，在手机DSH终端解压：

```sh
mkdir -p "$HOME/.dsh/dsh-go/plugins/openrayneo-dsh"
tar -xzf /sdcard/Download/openrayneo-dsh-0.1.8.tgz --strip-components=1 -C "$HOME/.dsh/dsh-go/plugins/openrayneo-dsh"
dsh plugin --profile web add "$HOME/.dsh/dsh-go/plugins/openrayneo-dsh"
```

首次安装执行add；更新使用原目录替换，再在DSH“引擎”页重启内核。连接动作存入`OPENRAYNEO_TOKEN`凭据服务。live原生唤醒跨wake复用对话，手机助手“新建对话”显式重开；录音／任务／wake身份各自独立。

相遇同步将手机专用地址和sync token填入Mac工作台，默认15秒补齐文字／人物／总结／派生，音频按需获取。Mac持久副本位于 `~/Library/Application Support/OpenRayneo/MemoryLibrary/PhoneReplica/`；离线派生回写与游标规则见 [同步契约](record-sync-contract.md)。

## 声音与录音

- 原生自动接听需要显式开启。双路默认ch1本人、ch2完整说话人；native／全天混合mono全量处理。手工登记默认选实时ch1，Mac与手机声纹库独立。
- Mac每次原生capture自动流式保存最近完整WAV，位置为 `~/Library/Application Support/OpenRayneo/NativeDiagnostics/`。`GET /v1/assistant/native/diagnostic`查询当前状态和`latest`，核对captureID、complete、frames和路径后使用；无需arm或等待窗口，保存不受ASR游标清理影响。
- 下次完整会话成功后，旧WAV移入Trash；异常轮不冒充完整，当前失败与上一有效latest分别给出。原生capture沿用既有时长设置，保存没有额外60秒截断。
- Mac“内容 → 声音与说话人”选择文件，先关闭ASR分析边界和身份；“本轮结果”可逐段回听。“导出结果”的`matchingDiagnostics`包含参考／query标量矩阵，原导出人物与文字字段保持。
- 手机“记录 → 记录日程”打开已结束记录，“导出WAV”保留原通道；当前正在录制的那条先停止并等待收尾。文件名不用于判断native／实时／全天来源。
- 实际声纹仍有漏认与单人误分组，当前基准和下一步见 [未解决问题](../.arch/ground_truth.md#当前实现与未解决问题)。

## 数据与远端开发

手机加密记录与Keystore、Mac Application Support／Keychain均为用户数据，清构建产物时保留。原始APK／固件／反编译与当前诊断证据在已忽略的 `analysis/`，不作为发布资源。

部分开发证据在SSH别名 `ubuntu-rx7800xt` 的 `~/openrayneo-lab/`。远端工作使用 `ssh-control` 技能；保留原源码基线、模型、SDK和用户资料，临时排错代码阶段结束Trash，不将远端端口、密钥或个人记录抄进项目文档。
