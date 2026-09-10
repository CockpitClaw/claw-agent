#!/usr/bin/env bash
# setup-car.sh — 车机端（Android）一次性准备脚本
#
# 做的事：
#   1. 交叉编译决策层 picoclaw → build/picoclaw-android-arm64
#   2. 编译 android-mcp 执行层 APK → android/app/build/outputs/apk/debug/app-debug.apk
#   3. 从 .env 渲染车机端 config.json → build/car-config.json
#      （与 mac 端共用 .env 的 LLM 配置，mcp server key = android、端口 19003）
#
# 之后按 README 把 config 和二进制 push 到设备即可。
# 本脚本只做「本地准备」，不直接操作 adb 设备——设备可能未连接。
#
# 前置：与 mac 端一致（Go 1.25+、JDK/Gradle）。LLM 配置走 .env，填你自己的公开服务 key。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CAR_MCP_PORT="19003"   # 车机 android-mcp 端口，与 mac browser-mcp 的 19002 错开
CAR_WORKSPACE="/data/local/tmp/picoclaw-workspace"

bold() { printf "\033[1m%s\033[0m\n" "$*"; }
step() { printf "\n\033[1;34m▶ %s\033[0m\n" "$*"; }
ok()   { printf "\033[1;32m  ✓ %s\033[0m\n" "$*"; }
warn() { printf "\033[1;33m  ! %s\033[0m\n" "$*"; }
die()  { printf "\033[1;31m  ✗ %s\033[0m\n" "$*"; exit 1; }

# ── 0. 前置检查 ────────────────────────────────────────────────
step "检查前置依赖"
command -v go >/dev/null || die "Go not found. brew install go"
command -v java >/dev/null || die "JDK not found（编译 APK 需要）"
[ -f .env ] || { warn ".env 不存在，从 .env.example 复制"; cp .env.example .env; warn "请编辑 .env 填入你的 LLM_API_KEY"; }
ok "前置 OK"

# 载入 .env（渲染 ${VAR} 用）
set -a; source "$ROOT/.env"; set +a

# ── 1. 交叉编译决策层 ────────────────────────────────────────────
step "交叉编译决策层 picoclaw（android/arm64）"
(cd "$ROOT/picoclaw" && make build-android-arm64)
ok "决策层二进制: $ROOT/picoclaw/build/picoclaw-android-arm64"

# ── 2. 编译 android-mcp 执行层 APK ──────────────────────────────
step "编译 android-mcp 执行层 APK"
(cd "$ROOT/android" && sh ./gradlew assembleDebug >/dev/null 2>&1)
ok "APK: $ROOT/android/app/build/outputs/apk/debug/app-debug.apk"

# ── 3. 渲染车机 config.json ─────────────────────────────────────
step "渲染车机端 config.json → build/car-config.json"
mkdir -p "$ROOT/build"
python3 - "$ROOT" "$CAR_MCP_PORT" "$CAR_WORKSPACE" <<'PYEOF'
import json, os, re, sys

root, car_mcp_port, car_workspace = sys.argv[1], sys.argv[2], sys.argv[3]

# 读模板 + 用环境变量（已 source .env）替换 ${VAR}
tpl = open(os.path.join(root, 'config', 'picoclaw.config.json')).read()

def rep(m):
    return os.environ.get(m.group(1), m.group(0))
rendered = re.sub(r'\$\{(\w+)\}', rep, tpl)

d = json.loads(rendered)

# 车机端差异 1：workspace 指向设备上的目录
d['agents']['defaults']['workspace'] = car_workspace

# 车机端差异 2：mcp server key 用 android（而非 mac 的 browser），端口固定 19003
d['tools']['mcp']['servers'] = {
    "android": {
        "enabled": True,
        "type": "http",
        "url": f"http://127.0.0.1:{car_mcp_port}/mcp"
    }
}

# 车机端差异 3：关闭内置 web 搜索/抓取捷径，任务必须走 mcp_android_*（浏览器/原生 app）真工具，
# 避免 LLM 用 web_search/web_fetch 直接抓取、不经过真实浏览器。
d['tools']['web']['enabled'] = False
d['tools']['web_fetch']['enabled'] = False

out = os.path.join(root, 'build', 'car-config.json')
json.dump(d, open(out, 'w'), indent=2, ensure_ascii=False)
print(f"  车机 config 已生成: {out}")
print(f"    mcp server key = android, url = http://127.0.0.1:{car_mcp_port}/mcp")
print(f"    workspace = {car_workspace}")
print(f"    model = {d['agents']['defaults']['model_name']}（从 .env 的 PICOCRAW_MODEL_NAME 读）")
PYEOF

step "准备完成"
bold "后续（按 README）："
printf "  1. 安装 APK：  adb -s <device> install android/app/build/outputs/apk/debug/app-debug.apk\n"
printf "  2. 启动 app：  adb -s <device> shell am start -n com.clawagent/.WebViewRemoteControlActivity\n"
printf "  3. 开无障碍：  adb -s <device> shell 'su 0 settings put secure enabled_accessibility_services ...'（见 README）\n"
printf "  4. push 决策层：adb -s <device> push picoclaw/build/picoclaw-android-arm64 /data/local/tmp/picoclaw-core\n"
printf "  5. push 配置：  adb -s <device> shell 'su 0 mkdir -p /data/local/tmp/.picoclaw'; adb -s <device> push build/car-config.json /data/local/tmp/.picoclaw/config.json\n"
printf "  6. push skills：adb -s <device> shell 'su 0 mkdir -p /data/local/tmp/picoclaw-workspace/skills'; adb -s <device> push car-skills/fallback-webview /data/local/tmp/picoclaw-workspace/skills/; adb -s <device> push car-skills/car-native /data/local/tmp/picoclaw-workspace/skills/\n"
printf "  7. 运行 agent： adb -s <device> shell \"HOME=/data/local/tmp /data/local/tmp/picoclaw-core agent -m '...'\"\n"