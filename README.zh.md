# Claw Agent

[English](README.md) | 中文

**Claw Agent** 是一个以真实场景为核心的**本地自然语言浏览器 Agent**：用一句话描述目标，它就在真实 Chrome 中持续观察页面、调用工具、验证结果并自我纠偏，一步步把多步骤网页任务做完，并可延伸到智能座舱车机等端侧交互场景。

> **让 AI 不只是"能聊天"，而是"能办事"。**

过去两年，我们见识了太多"能写诗、能画画"的 AI。但当你真的想让它帮你订张电影票、比个价、查条出行方案时，它却只会告诉你"抱歉，我做不到，不过我可以给你介绍一下……"。

**这就是"有脑无手"。** Claw Agent 想解决的正是这件事：给大模型装上一双能操作真实世界的"手"。它不是固定脚本，也不是点击录制器，而是一个会"看着屏幕自己想办法"的执行体。

技术上，它把轻量级 **Agent Runtime**、声明式任务 **Skill**、**MCP 浏览器服务** 和 **Chrome DevTools Protocol（CDP）** 组合在一起，在真实浏览器中高效完成多步骤网页任务。当前一键脚本面向 macOS 和 android车机系统；核心 Agent Runtime 与 Browser MCP 基于跨平台的 Go 与 CDP 实现，可移植到其他桌面系统。

### 为什么它值得关注：从"人找应用"到"服务找人"

智能座舱行业正在发生一场无声的革命——App 正在死去，Service（服务）开始永生。当车机屏幕上还趴着几十个 App 图标，用户在 120km/h 时速下根本没有时间去"点击应用抽屉、划屏找图标、等冷启动"。真正的方向是**去 App 化、服务原子化、Zero-Layer（零层级）交互**：用户说"我饿了"，系统直接在后台完成搜索、筛选、决策、执行，而不是甩给用户一堆选项。

而连接"用户意图"和"服务执行"之间的那座桥，正是 **Agent**。Claw Agent 的能力模型——感知（观察页面）、规划（拆解任务）、行动（调用工具）——与智能座舱系统级 Agent 的诉求高度同构。

> **mac 与车机场景均已跑通 Demo：** 我们已在 macOS（Chrome）与真实车机环境中完成了 Claw Agent 的端到端 Demo 级验证，用自然语言驱动浏览器完成多步骤网页任务链路。这是内部 Demo 级别的验证，尚非量产方案，但它证明了这套架构能够直接迁移到智能座舱这类算力受限、强调"无感交互"的端侧场景。

适用场景包括网页搜索、媒体播放、购物辅助、电影选座、出行查询和通用网页操作，并可延伸到智能座舱车机等端侧交互场景。

> Claw Agent 不绑定单一运行环境：既支持在 macOS + Google Chrome 上通过一键脚本以本地进程运行，也支持将核心 Agent Runtime（跨平台 Go）放入 Docker/容器运行（需自行配置模型与依赖）；并已在 macOS（Chrome）、车机 WebView、车机原生 App（Android）三种环境端到端 Demo 验证。遇到需要账号权限、安全校验、敏感凭证或可能产生不可逆结果的操作时，Agent 会暂停流程，等待用户授权或手动完成后再继续。

## 核心亮点

- 🗣️ **自然语言即操作**：把一句话请求转换为"观察—执行—验证—纠偏"的迭代闭环，而不是回放固定点击宏。
- 🌐 **真实 Chrome 自动化**：通过 Chrome DevTools Protocol（CDP）驱动可见 Chrome，在真实页面上完成任务。
- 🧰 **MCP 浏览器工具集**：提供 33 个工具，覆盖导航、语义 DOM、输入、滚动、截图、JavaScript、iframe、媒体控制和平台专属操作。
- 📜 **声明式 Skill**：通过 `SKILL.md` 沉淀任务流程；内置京东购物、淘宝购物、猫眼购票和通用网页兜底 Skill。
- 🧭 **平台注册与路由**：使用 Markdown 注册 25 个平台项，描述能力标签、PC URL、选择器、加载提示和 fallback 链。
- ⚙️ **完整 Agent Runtime**：提供工具调用、Session 持久化、上下文压缩、模型回退、Steering、Hook 与子 Agent，并支持多消息渠道扩展。
- 🔌 **多模型接入**：OpenAI 兼容 Endpoint 开箱即用，支持本地 Ollama，可扩展其他 Provider。
- ⚡ **轻量高性能**：Go 运行时，非首次启动 `< 1 秒`、任务期平均 CPU `约 0.5%`、内存 `约 38 MB`，天然适配算力受限的端侧场景。
- 🛡️ **安全边界**：登录挑战、敏感信息、订单确认和最终支付必须由用户确认或手动完成。
- ✅ **mac 与车机场景均已端到端验证**：已在 macOS（Chrome）与真实车机环境中完成 Demo 级验证，用自然语言驱动浏览器跑通多步骤任务链路。

## 📰 最新动态

- ✅ **mac 与车机 Demo 跑通**：已在 macOS（Chrome）与真实车机环境中完成 Demo 级验证，用自然语言驱动浏览器完成多步骤网页任务链路。当前为内部 Demo 级别验证，尚非量产方案，但已证明该架构可迁移到智能座舱这类算力受限、强调"无感交互"的端侧场景。
- ⚡ **轻量高性能基线**：在参考测试中，Agent Runtime 展现出"启动快、CPU 低、内存小"的资源画像，详见下方性能章节。

## 📑 目录

- [核心亮点](#核心亮点)
- [🎥 场景 Demo](#-场景-demo)
- [轻量高性能](#轻量高性能)
- [系统架构](#系统架构)
- [一次请求如何执行](#一次请求如何执行)
- [快速开始](#快速开始)
- [车机端（Android）运行](#车机端android运行)
- [配置说明](#配置说明)
- [扩展方式](#扩展方式)
- [安全与运行边界](#安全与运行边界)
- [致谢](#致谢)
- [📄 License](#-license)

## 🎥 场景 Demo

下面这段录屏在真实系统上一镜到底跑通了 10 个日常任务：**猫眼购票、GitHub 信息聚合、京东购物、视频播放、AppleMusic音乐播放、微博热搜聚合等**——全程由自然语言驱动，涉及支付等不可逆操作时都会停在确认环节等待用户操作。

![Claw Agent 演示](assets/demo.gif)

▶️ 上方为加速预览 GIF（约 22 秒循环）。完整高清录屏：[assets/demo.mp4](assets/demo.mp4)（约 15 分钟，点击下载后本地播放）。

### Demo 1 · 猫眼购票（选电影 -> 选影院/场次 -> 选座 → 支付收银台）

**用户指令：** 帮我看下最近有哪些好评的电影，买张电影票。

Agent 检索热映电影、进入猫眼选座下单，最终停在美团/微信收银台（订单编号、应付金额已生成），把最终支付交给用户。

### Demo 2 · GitHub 信息聚合

**用户指令：** 帮我看看 GitHub 上关于 Agent 应用都有哪些高星仓库，梳理它们的核心能力。

Agent 搜索并打开相关项目页面，通过页面理解及操作实现多个仓库的信息聚合，并汇总关键信息展示给用户。

### Demo 3 · 爱奇艺播放电视剧（搜索电视剧 -> 选择剧集 -> 播放 -> 验证播放状态）

**用户指令：** 爱奇艺播放狂飙第一集。

Agent 进入爱奇艺网站执行搜索、点击以及页面理解，完成影视播放。


### Demo 4 · 京东购物（搜索商品 -> 选尺码 → 支付收银台）

**用户指令：** 帮我在京东买双詹姆斯 12 代 43 码的篮球鞋。

Agent 搜索并进入商品页、选定 43 码、进入结算与收银台，最终停在"支付验证/输入支付密码"环节，等待用户完成支付。

### Demo 5 · Apple Music 搜歌（搜索歌曲 -> 页面理解 → 点击播放）

**用户指令：** 用 Apple Music 播放张学友的《吻别》。

Agent 进行关键词搜索，抓取页面信息并选择指定歌曲播放。

### Demo 6 · YouTube 播放视频（搜索关键词 -> 选择视频 -> 播放 -> 验证播放状态）

**用户指令：** 帮我YouTube播放可爱的猫咪视频。

Agent 进入 YouTube 搜索、页面识别并成功自动播放相关内容。

### Demo 7 · B站视频播放（搜索关键词 -> 选择视频 -> 播放 -> 验证播放状态）

**用户指令：** 帮我用 B 站播放罗翔讲刑法最新的视频。

Agent 进入 B 站搜索、页面识别并成功自动播放。

### Demo 8 · 机票查询 （打开携程 -> 页面识别/操作 -> 信息抓取汇总）

**用户指令：** 查下北京到上海明天的机票。

Agent 打开携程进行页面识别及操作，返回整理后的行程、日期、价格等详细信息。

### Demo 9 · 百度查天气（打开百度 -> 关键词搜索 -> 信息抓取汇总）

**用户指令：** 帮我用百度查下明天北京的天气。

Agent 打开百度输入关键词搜索，抓取页面信息理解并汇总天气信息。

### Demo 10 · 微博热搜信息聚合

**用户指令：** 帮我看下今天微博热搜都有哪些。

Agent 打开微博热搜页并结构化提取热搜榜单，返回整理后的榜单信息。

## 轻量高性能

Claw Agent 采用 Go 实现轻量级运行时，在参考测试中展现出优秀的资源效率：

| 指标         | 参考结果 |
|------------|---:|
| 启动时间（非首次）  | `< 1 秒` |
| 任务期平均 CPU  | `约 0.5%` |
| 内存占用       | `约 38 MB` |

实际数据会随硬件、模型服务、任务复杂度、浏览器页面和采样方式变化；以上结果用于展示 Agent Runtime 自身的轻量化水平，不包含 Chrome 和外部模型服务的资源消耗。

这种"启动快、CPU 低、内存小"的特性，恰恰是智能座舱等**算力受限端侧场景**最看重的能力——Agent Runtime 本身几乎不占资源，把宝贵的算力留给业务和模型。

## 系统架构

下图为 **mac 端**执行链路；车机端是与之对称的结构：同一套 Claw Agent Runtime 决策，执行层由 `browser-mcp` 换成 `android-mcp`（WebView CDP + 无障碍），见「车机端（Android）运行」。

```text
用户 / CLI / 消息渠道
          │
          ▼
┌────────────────────────────────────────┐
│ Claw Agent Runtime                    │
│ 路由、Prompt、Session、ReAct、Provider │
└───────────────────┬────────────────────┘
                    │ MCP (Streamable HTTP / JSON-RPC)
                    ▼
┌────────────────────────────────────────┐
│ browser-mcp                            │
│ 浏览器原语、通用语义 Helper、平台 Helper │
└───────────────────┬────────────────────┘
                    │ Chrome DevTools Protocol
                    ▼
┌────────────────────────────────────────┐
│ Google Chrome                          │
│ 页面渲染、DOM、Runtime、用户输入       │
└────────────────────────────────────────┘
```

仓库包含两个相互独立的 Go module，外加一个车机 Android（Kotlin）执行模块：

| Module | 位置 | 职责 |
|---|---|---|
| `claw-agent` | 仓库根目录（`browser-mcp/`） | mac 执行层：Browser MCP 服务与 Chrome/CDP 集成 |
| `github.com/sipeed/picoclaw` | `picoclaw/` | 决策层：Agent Runtime、Provider、Session、工具、MCP 客户端、gateway 与渠道（mac 与车机共用） |
| `com.clawagent`（Kotlin/Gradle） | `android/` | 车机执行层：`android-mcp`（WebView CDP + 原生无障碍） |

三者都在运行时通过 MCP 耦合，不通过 import 耦合。

## 一次请求如何执行

```text
用户请求
  → 选择 Agent 与 Session
  → 组装 Prompt、历史、摘要和 Active Skills
  → 调用模型
  → 归一化 Tool Call
  → 执行本地工具或 MCP 工具
  → 将 Tool Result 回灌模型
  → 重复直到任务完成
  → 持久化 Session 并返回最终答复
```

浏览器任务（mac 端）会继续经过：

```text
Agent ToolRegistry
  → MCP Client
  → POST /mcp
  → browser-mcp
  → CDP WebSocket
  → Chrome
```

车机端的浏览器/原生 App 任务走同样的 `Agent ToolRegistry → MCP Client → POST /mcp` 前段，后段换成 `android-mcp`（WebView 走 CDP，原生 App 走无障碍）。

同一 Session 内的任务串行执行，不同 Session 可以并行；运行中的 Turn 还可以接收新的 Steering 指令。

## Browser MCP 工具体系

Browser MCP 是 **mac 端**的浏览器执行层，当前提供 33 个工具，分为四类（车机端的 `android-mcp` 工具见「车机端运行」）。

### 浏览器原生能力

- 页面发现、复用、新建与导航
- 带稳定元素引用的语义 DOM 快照
- 页面截图
- 点击、DOM 点击、输入、填充、悬停、拖拽与滚动
- 主页面及 iframe 内 JavaScript 执行

### 跨站语义 Helper

- 搜索结果链接提取
- 渲染后页面文本提取
- 元素等待和遮罩关闭
- 媒体检测、播放与暂停
- 登录墙识别
- 播放按钮发现

### 京东 Helper

- 提取可用尺码
- 选择尺码并验证选中状态
- 在不同结算页面形态中识别订单与支付控件

### 猫眼 Helper

- 提取影院和场次
- 打开指定场次
- 查询与选择座位
- 关闭购票页面弹窗

复杂且可重复的 DOM 判断被下沉到 Go Helper，让模型调用一个稳定工具，而不是每次临时生成冗长、脆弱的脚本。

## Skill 与平台路由

项目将任务策略和浏览器执行解耦：

- `skills/*/SKILL.md` 定义触发条件、执行步骤和必须由用户确认的边界。
- `skills/fallback-webview/platforms.md` 注册平台能力、PC URL、选择器、加载特征和 fallback 顺序。
- `browser-mcp/` 提供这些流程可调用的实际浏览器能力。

内置 Skill：

| Skill | 用途 |
|---|---|
| `jd-shopping` | 默认京东购物辅助流程 |
| `taobao-shopping` | 用户明确指定淘宝时使用的购物流程 |
| `maoyan-movie` | 电影、影院、场次、座位和订单确认流程 |
| `fallback-webview` | 搜索、媒体、出行、外卖、信息查询等通用网页任务 |

车机端另有**前置路由 skill**（`car-skills/`，不随 `setup.sh` 同步到 mac），其唯一作用是在执行前按触发词把车机 query 确定性分发到「车机浏览器」或「车机原生 App」，不承载具体业务逻辑——具体怎么操作由命中后的 ReAct 循环现场决定。

| Skill | 用途                                               |
|---|--------------------------------------------------|
| `fallback-webview` | 默认兜底：未指定原生 app 的查询（查天气/搜索/浏览网页等）→ 车机 WebView CDP |
| `car-native` | query 含「用 XX app / 打开 XX app」→ 车机无障碍服务操作原生 App   |

普通网站通常只需增加一条平台注册记录。复杂结算页、Canvas、iframe 或高度动态流程，可能还需要专属 Skill 或 Go Helper。

## 环境要求

mac 端一键脚本（`setup.sh` / `start.sh`）当前仅面向 macOS（车机端脚本见「车机端（Android）运行」）：

- Intel 或 Apple Silicon Mac
- Homebrew
- Go 1.25+（构建时自动下载所需工具链）
- Google Chrome，安装于 `/Applications/Google Chrome.app`
- 缺少 `envsubst` 时需要 Python 3
- LLM API Key，或已运行的本地 Ollama

## 快速开始

### 1. 克隆并构建

```bash
git clone <repository-url> claw-agent
cd claw-agent
chmod +x ./scripts/*.sh
./scripts/setup.sh
```

安装脚本会构建两个 Go 二进制；在需要时将 `.env.example` 复制为 `.env`；同步 Skill 和工作区规则；并在 `~/.picoclaw/` 下渲染 Agent 配置。

### 2. 配置模型

编辑 `.env`：

```dotenv
LLM_API_KEY=your-api-key
LLM_API_BASE=https://api.openai.com/v1
LLM_MODEL_ID=your-model-id
PICOCRAW_MODEL_NAME=default
LLM_THINKING_LEVEL=off
```

使用 Ollama：

```dotenv
LLM_API_KEY=
LLM_MODEL_ID=qwen3:32b
PICOCRAW_MODEL_NAME=ollama-local
```

请先启动 Ollama 并拉取对应模型。

### 3. 运行

单次任务：

```bash
./scripts/start.sh "用百度查询明天北京的天气"
```

交互式 REPL：

```bash
./scripts/start.sh
```

加 `-s` 指定某个命名或新 session 开启交互式对话（文件名为 `sk_v1_<名字>.jsonl`，即给名字加 `sk_v1_` 前缀）：
```bash
./scripts/start.sh -s "task01"  
```

调试输出与命名 Session：

```bash
./scripts/start.sh -d -s research "比较三种出行方案"
```

`start.sh` 会同步最新 Skill、渲染配置、启动或复用 Chrome 与 `browser-mcp`，最后启动 Claw Agent。

> **关于「信息检索」类任务走浏览器还是内置搜索**：mac 端默认保留了内置 `web_search`/`web_fetch` 快捷工具，因此「查天气 / 搜 GitHub 高星仓库 / 看热搜」这类纯信息任务时，Agent 可能直接用内置搜索/抓取完成（更快），而不是驱动真实 Chrome。若想让这类任务也走真实浏览器，可在 query 里加**「用浏览器 / 打开浏览器 / 打开网页」**等关键词提示；想要强制（像车机端一样）关闭内置搜索捷径，则在 `config/picoclaw.config.json` 里把 `tools.web.enabled` 与 `tools.web_fetch.enabled` 设为 `false` 后重新 `./scripts/setup.sh`。

## 车机端（Android）运行

Claw Agent 除了 mac 端浏览器形态，还提供对称的车机端形态：用同一套 Agent Runtime 决策，改由 Android 执行层直接操作车机应用与车机网页。

```text
Mac  端：picoclaw(决策)  + browser-mcp(执行，控制 Chrome)
车机端：picoclaw(决策)  + android-mcp(执行，WebView CDP（android browser） + 无障碍服务原子能力 (app))
```

### 车机端环境要求

- Android 设备/车机（arm64-v8a，已在 Android 16 车机验证）
- 可通过 `adb` 连接设备
- 设备具备 root（开启无障碍需写安全设置）；无 root 则需在系统设置中手动授权无障碍服务
- Go 1.25+（用于交叉编译决策层）
- JDK + Gradle（用于编译 `android/` 执行层 APK）
- LLM API Key（与 mac 端一致）

### 执行流程

```bash
# 0. 配置 LLM（与 mac 端共用 .env，填你自己的公开服务 API key）
cp .env.example .env
#    编辑 .env：LLM_API_KEY / LLM_API_BASE / LLM_MODEL_ID（OpenAI、百炼、DeepSeek 等 OpenAI 兼容服务均可）
#    也可用本地 Ollama（见 .env.example 说明）
#    注意：.env 里的 MCP_PORT 只影响 mac 端 browser-mcp（19002）；车机 android-mcp 端口固定 19003，不读 MCP_PORT。

# 1. 本地准备：交叉编译决策层 + 编译 APK + 渲染车机 config（代码有改动后重跑）
./scripts/setup-car.sh

# 2. 一键部署到设备（安装 APK → 启动 → 开无障碍 → push 二进制/config/skills → 运行）
./scripts/deploy-car.sh -m "打开B站搜索罗翔并播放"   # 部署 + 单次任务
./scripts/deploy-car.sh                             # 部署 + 交互式入口提示
./scripts/deploy-car.sh -s research -m "..."        # 部署 + 命名 session 单次任务
./scripts/deploy-car.sh -d -m "..."                 # 部署 + 调试（打印每步工具调用轨迹）
```

`deploy-car.sh` 参数说明：

| 参数 | 作用 |
|---|---|
| `--device <serial>` | 指定设备；**单台设备可省略（自动探测）**，多台设备需指定 |
| `-m "query"` | 单次任务；不带则部署后打印交互式 REPL 入口提示 |
| `-s <name>` | 指定 session（自动补 `sk_v1_` 前缀），同名多轮共享上下文 |
| `-d` | 调试，打印 LLM 推理与工具调用轨迹 |

无 root 设备时脚本会自动跳过「开无障碍」并提示你手动授权；`--device` 只在同时连接多台设备时才需要，普通单台车机直接 `adb connect <ip>:5559` 后跑脚本即可。

### 交互式 REPL（连续多轮对话）

`agent` 去掉 `-m` 即交互式。但 `adb shell "cmd"` 是**非 TTY**，readline 起不来，必须进设备 shell 手动执行（`deploy-car.sh` 不带 `-m` 时也会打印这段提示）：

```bash
adb -s <device> shell          # 进入设备交互 shell（分配 TTY）
# —— 以下是设备 shell 内执行 ——
su 0
export HOME=/data/local/tmp     # 必须，否则读不到 config
/data/local/tmp/picoclaw-core agent
# 出现 You: 提示符即可输入，Ctrl+C 退出；exit/quit 结束

# 指定 session（多轮上下文隔离，同名前缀会共享/续接上下文）
/data/local/tmp/picoclaw-core agent -s sk_v1_travel
```

### Session 说明

picoclaw 只把 `sk_v1_` 或 `agent:` 开头的 key 当作**显式 session**，裸名字会被忽略（回落默认 `cli:default`）。`deploy-car.sh` 和 mac 端 `start.sh` 一样会帮你加 `sk_v1_` 前缀；直接跑设备上的二进制则要自己带前缀。已命名 session 文件落在 `/data/local/tmp/picoclaw-workspace/sessions/sk_v1_<name>.jsonl`，删除该文件即可重置上下文。

> **触发词路由说明**：车机端有两条确定性路由——query 含「用 XX app / 打开 XX app」时走 `car-native`（`mcp_android_native_*` 无障碍工具）；其余未指定原生 app 的查询默认走 `fallback-webview`（`mcp_android_*` WebView CDP），天然覆盖「通过浏览器 / 查天气 / 搜 XX」等场景。车机 config 已**关闭内置 `web_search`/`web_fetch`**，以使得任务走真实浏览器或原生 App 自动化执行，避免信息搜集类任务 LLM 绕开浏览器直接抓数据。

> **端口错开说明**：车机 android-mcp 默认监听 **19003**，与 mac 端 browser-mcp 的 **19002** 错开。这样 mac 端 browser-mcp 和车机 android-mcp 可同时存在：从 mac 调试车机时用 `adb forward tcp:19003 tcp:19003`，不会和 mac 本地 19002 串台。picoclaw 通过 config 里不同的 server key（`browser` vs `android`）和工具名前缀（`mcp_browser_*` vs `mcp_android_*`）区分调用哪个 MCP。

### 车机端 MCP 工具

`android-mcp` 当前暴露 48 个工具，通过 `mcp_android_*` 前缀暴露给 Agent，分为两类：

- **WebView CDP（35 个）**：`list_pages`、`navigate_page`、`take_snapshot`、`take_screenshot`、`click`、`type`、`scroll_up/down`、`evaluate_script`，以及京东/猫眼购物 helper（`jd_get_sizes`、`jd_select_size`、`jd_find_pay_button`、`maoyan_get_cinemas`、`maoyan_get_shows`、`maoyan_click_show`、`maoyan_query_seats`、`maoyan_select_seat`、`maoyan_dismiss_modal`）、`new_page`、`dom_click` 等，操作**车机网页**——包括 clawagent 内嵌 WebView 与车机浏览器中的 WebView，通过 CDP 直连 DOM。
- **无障碍 native（13 个）**：`native_launch_app`、`native_get_foreground_app`、`native_get_ui_tree`、`native_click(_node)`、`native_input_text`、`native_input_to_node`、`native_long_click`、`native_open_miniprogram`、`native_scroll`、`native_press_back/home`、`native_screenshot` 等，操作**车机原生 App**——原生界面没有 DOM，只能通过无障碍节点树和坐标点击。

### 关键注意

- **无障碍服务**：无 root 设备需用户在系统设置里手动授权；有 root 可用上面的 `settings put secure` 一键开启。
- **配置文件路径**：`su 0` 的 HOME 为 `/`，务必用 `HOME=/data/local/tmp`（或其他放置 `.picoclaw/` 的目录）运行，否则会读不到 `config.json`。

## 配置说明

主要配置文件：

| 文件 | 用途 |
|---|---|
| `.env` | 模型凭证和运行参数 |
| `config/picoclaw.config.json` | Agent Runtime 配置模板 |
| `skills/*/SKILL.md` | 任务路由和执行规则 |
| `skills/fallback-webview/platforms.md` | 平台注册表 |
| `picoclaw/workspace/AGENT.md` | Agent 行为规则 |
| `picoclaw/workspace/SOUL.md` | Agent 身份与交互风格 |

主要环境变量：

| 变量 | 默认值 | 用途 |
|---|---|---|
| `LLM_API_KEY` | 无 | 模型凭证 |
| `LLM_API_BASE` | 示例中为 OpenAI Endpoint | OpenAI-compatible API 地址 |
| `LLM_MODEL_ID` | 见 `.env.example` | Provider 模型 ID |
| `PICOCRAW_MODEL_NAME` | `default` | 选择配置模板中的模型项 |
| `LLM_THINKING_LEVEL` | `off` | 配置支持的 Thinking 模式 |
| `MCP_AUTH_TOKEN` | 空 | 非空时为 Browser MCP 启用 Bearer 鉴权 |
| `MCP_KEEP_ALIVE` | 启动时默认为 `1` | 在多次运行间复用 MCP 进程 |

### 端口说明

当前 `browser-mcp` 通过编译期常量监听 `19002`，并连接 Chrome 的 `9222` 端口。虽然 `.env.example` 暴露了 `MCP_PORT` 和 `CDP_PORT`，但在同步修改 Go 常量并重新构建前，请保持默认值。

## 扩展方式

### 新增网站

在 `skills/fallback-webview/platforms.md` 中增加平台项，声明 capability、PC URL、加载提示、选择器和 fallback。该方式适用于常规导航、搜索和内容交互。

### 新增任务流程

创建新的 Skill 目录和 `SKILL.md`，定义触发条件、执行阶段、安全规则和优先工具。同名 Skill 的优先级为 workspace > global > builtin。

### 新增浏览器操作

在 `browser-mcp` 中实现能力，补充 MCP Tool Schema 和分发逻辑。对重复、确定性的站点 DOM 逻辑，优先封装为语义 Helper。

### 接入外部能力

在 `config/picoclaw.config.json` 中注册新的 MCP Server。Agent Runtime 启动时会发现远端工具，并将其适配进统一 ToolRegistry。

### 新增模型或渠道

Agent Runtime 提供 Provider 接口、Provider Factory 和 Channel Factory。OpenAI-compatible Endpoint 通常可复用现有 HTTP Provider；全新协议需要实现 Provider 并注册到 Factory。

## 测试

仓库有两个 Go module 和一个车机 Android module，需要分别验证：

```bash
# 根模块（claw-agent → browser-mcp）：编译并运行根模块已有 Go 测试
go test ./...

# Agent Runtime 单元测试和 Web 测试
cd picoclaw
make test

# 可选：Docker 驱动的 MCP 集成测试
make integration-test

# 车机 android-mcp 执行层（Kotlin）
cd android
./gradlew assembleDebug
```

仓库还提供真实网站手工集成脚本：

```bash
python3 test_generic_tools.py
```

运行前需要启动 Chrome 和 `browser-mcp`。其结果会受到网络、地区、登录状态和网站当前 DOM 的影响。

当前质量边界：Agent Runtime 内核拥有较完整的单元测试和 MCP 集成测试；顶层 Browser MCP Helper、平台 Selector 和端到端 Skill 的自动化覆盖仍有限。

## 安全与运行边界

- 不要把 Browser MCP 端口暴露到公网。
- `browser-mcp` 当前监听所有网卡；在不可信网络中应设置 `MCP_AUTH_TOKEN`，并为 MCP Client 配置相同 Bearer Header。
- Agent 不绕过登录墙、验证码、滑块、扫码、会员权限或反爬机制。
- 支付密码、扫码支付、验证码、最终订单提交及其他不可逆操作，需要用户确认或手动完成。
- 平台路由和不少安全规则由 Skill Prompt 驱动，并非独立的强制交易策略引擎。
- 平台适配依赖当前 PC 页面 DOM，网站升级后 Selector 可能失效。
- Workspace 限制可以降低误访问风险，但不等于完整 OS Sandbox；处理不可信任务时应使用 VM 或容器隔离。
- 浏览器内容、任务文本和相关工具结果可能发送到所配置的模型服务商。浏览器和 Session 在本地，并不代表使用云模型时完全离线。

## 故障排查

| 现象 | 检查项 |
|---|---|
| `picoclaw binary not built` | 运行 `./scripts/setup.sh` 并检查 `go version` |
| `browser-mcp failed to start` | 查看 `.mcp.log`，检查 `19002` 是否被占用 |
| `connection refused 127.0.0.1:9222` | 确认 Chrome 已由 `scripts/launch-chrome.sh` 启动 |
| Chrome 中没有已有登录状态 | 在 `~/.browser-mcp/` 对应独立 Profile 中重新登录 |
| 路由到错误网站 | 调整 Skill description 或平台注册项 capability |
| 页面动作突然失效 | 检查登录墙、SPA 渲染、遮罩和过期 Selector |
| 命名 Session 带有旧上下文 | 使用新 Session 名，或删除 sessions 目录下的对应文件 |

## 目录结构

```text
claw-agent/
├── android/                     # 车机端执行层（WebView CDP + 无障碍 MCP server，Kotlin）
├── assets/                      # 演示录屏等静态资源（demo.gif 预览 / demo.mp4 完整版）
├── browser-mcp/                 # MCP Server 与 CDP 浏览器执行层
├── car-skills/                  # 车机端专属任务手册（fallback-webview / car-native，触发词分流）
├── config/                      # Agent Runtime 配置模板
├── picoclaw/                    # 独立的 Agent Runtime Go module
├── scripts/                     # macOS 安装/启动脚本（setup.sh/start.sh）+ 车机准备/部署脚本（setup-car.sh/deploy-car.sh）
├── skills/                      # 项目专属任务执行手册
├── test_generic_tools.py        # 真实站点手工集成检查
├── .env.example                 # 运行配置示例
└── go.mod                       # 根 Browser MCP Go module
```

## 已知限制

- **需要自行准备 LLM**：本项目不自带模型服务，需要你在 `.env` 填自己的 LLM API key（OpenAI 兼容的服务均可，如 OpenAI / 阿里百炼 / DeepSeek），或用本地 Ollama。不同的设备（mac 浏览器 vs 车机）需要能访问该 LLM 端点。
- **车机 WebView 对部分站点不稳定**：车机内嵌 WebView（基于 Chrome for WebView）对少数 CSR/高反爬站点（如微博热搜、百度搜索）可能加载缓慢或触发安全验证，表现不如 mac 桌面 Chrome。这类站点若在车机加载失败，Agent 会如实报告拿不到数据，而非编造。
- **车机原生 App 可能被 sidebar 遮罩**车机等有常驻透明 sidebar Activity（如 `com.XXX.sidebar`）会盖在新启动的 app 之上，导致前台看不到目标 app（但 app 实际已在后台运行、无障碍仍能读到其 UI）。这是车机系统层行为，非本项目问题。
- **车机端放宽明文流量**：`AndroidManifest` 设了 `usesCleartextTraffic="true"`，用于让 WebView CDP 的 `ws://` 明文 WebSocket 连上 localhost DevTools。这是车机内网 app 的常规取舍，若对外发布需评估安全影响。
- **平台适配依赖页面 DOM**：网站升级后 Selector/Helper 可能失效，需要更新 `skills/*` 或 `browser-mcp`/`android-mcp` 中的适配逻辑。

## 项目状态

该项目目前定位为实验性的本地浏览器 Agent，适合受监督的个人自动化和开发验证。若用于生产环境，还需要进一步加强网络隔离、鉴权、确定性的审批控制，以及站点专属流程的自动化回归测试。

## 致谢

感谢 [PicoClaw](https://github.com/sipeed/picoclaw) 开源社区及其贡献者。本项目的 Agent Runtime 基于该项目进行集成与扩展；相关代码继续遵循其原始 MIT License 和版权声明。

## 📄 License

本项目的 Agent Runtime 部分继续遵循上游 [PicoClaw](https://github.com/sipeed/picoclaw) 的 MIT License 及其版权声明。其余代码在集成时请遵循仓库内相应的许可与声明。

---

如果这个项目对你有帮助，欢迎 Star、Issue 和 PR，一起把它做得更好、推广得更远。
