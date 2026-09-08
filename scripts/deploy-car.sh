#!/usr/bin/env bash
# deploy-car.sh — 车机端（Android）部署脚本
#
# 把 setup-car.sh 产出的产物部署到已连接的 adb 设备，并启动 agent。
# 与 setup-car.sh 职责分离：编译走 setup-car.sh，部署到设备走本脚本。
#
# Usage:
#   ./scripts/deploy-car.sh --device <serial> -m "query"   # 部署 + 单次任务
#   ./scripts/deploy-car.sh --device <serial>              # 部署 + 交互式入口提示
#
# Options:
#   --device <serial>  设备标识；缺省时自动探测（单台自动，多台报错）
#   -m <query>         单次任务 query；不带则部署后提示交互式入口
#   -s <session>       Session key（自动补 sk_v1_ 前缀，同 start.sh）
#   -d                 debug：打印 LLM 推理与工具调用轨迹
#
# 前置：先跑 ./scripts/setup-car.sh（产物：picoclaw-android-arm64、app-debug.apk、car-config.json）

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CAR_MCP_PORT="19003"                      # 车机 android-mcp 端口
CAR_WORKSPACE="/data/local/tmp/picoclaw-workspace"
CAR_CONFIG_DIR="/data/local/tmp/.picoclaw"
APP_PKG="com.clawagent"
APP_ACTIVITY="$APP_PKG/.WebViewRemoteControlActivity"
ACCESSIBILITY_SVC="$APP_PKG/$APP_PKG.accessibility.NativeAccessibilityService"

BIN="$ROOT/picoclaw/build/picoclaw-android-arm64"
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
CFG="$ROOT/build/car-config.json"

bold() { printf "\033[1m%s\033[0m\n" "$*"; }
step() { printf "\n\033[1;34m▶ %s\033[0m\n" "$*"; }
ok()   { printf "\033[1;32m  ✓ %s\033[0m\n" "$*"; }
warn() { printf "\033[1;33m  ! %s\033[0m\n" "$*"; }
die()  { printf "\033[1;31m  ✗ %s\033[0m\n" "$*"; exit 1; }

DEV=""
QUERY=""
SESSION=""
DEBUG_FLAG=""

while [ $# -gt 0 ]; do
  case "$1" in
    --device)
      [ -n "${2:-}" ] || die "--device 需要参数"
      DEV="$2"; shift 2 ;;
    --device=*) DEV="${1#--device=}"; shift ;;
    -m) QUERY="${2:-}"; shift 2 ;;
    -s) SESSION="${2:-}"; shift 2 ;;
    -d|--debug) DEBUG_FLAG="-d"; shift ;;
    -h|--help) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "未知参数: $1" ;;
  esac
done

# ── 0. 前置检查 ────────────────────────────────────────────────
step "检查前置"
command -v adb >/dev/null || die "adb not found"
[ -f .env ] || die ".env 不存在，先 cp .env.example .env 并填 LLM_API_KEY"
for f in "$BIN" "$APK" "$CFG"; do
  [ -f "$f" ] || die "产物缺失: $f（先跑 ./scripts/setup-car.sh）"
done
ok "前置 OK"

# ── 1. 解析设备 ────────────────────────────────────────────────
step "解析设备"
if [ -z "$DEV" ]; then
  mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  case "${#DEVICES[@]}" in
    0) die "未检测到 adb 设备，先 adb connect <ip>:5559" ;;
    1) DEV="${DEVICES[0]}" ;;
    *) die "检测到多台设备，请用 --device 指定: ${DEVICES[*]}" ;;
  esac
fi
adb -s "$DEV" get-state >/dev/null 2>&1 || die "设备 $DEV 不可用"
ok "设备: $DEV"

# ── 2. 安装 APK ────────────────────────────────────────────────
step "安装 APK"
adb -s "$DEV" install -r "$APK"
ok "APK 安装完成"

# ── 3. 启动 app ────────────────────────────────────────────────
step "启动 clawagent（前台服务自动拉起 MCP server :${CAR_MCP_PORT}）"
adb -s "$DEV" shell am start -n "$APP_ACTIVITY" >/dev/null
ok "app 已启动"

# ── 4. 开启无障碍服务 ──────────────────────────────────────────
step "开启无障碍服务（native_* 工具依赖）"
if adb -s "$DEV" shell "su 0 settings put secure enabled_accessibility_services '$ACCESSIBILITY_SVC'" >/dev/null 2>&1; then
  adb -s "$DEV" shell "su 0 settings put secure accessibility_enabled 1" >/dev/null 2>&1
  ok "无障碍服务已开启"
else
  warn "su 不可用（无 root），跳过自动开启。若需 native_* 工具，请在 设置›无障碍 手动授权 ClawAgent Native Control"
fi

# ── 5. push 决策层二进制 ───────────────────────────────────────
step "push 决策层二进制"
adb -s "$DEV" push "$BIN" /data/local/tmp/picoclaw-core
adb -s "$DEV" shell chmod 755 /data/local/tmp/picoclaw-core
ok "决策层二进制已就位"

# ── 6. push 车机 config ────────────────────────────────────────
step "push 车机 config"
adb -s "$DEV" shell "su 0 mkdir -p $CAR_CONFIG_DIR"
adb -s "$DEV" push "$CFG" "$CAR_CONFIG_DIR/config.json"
ok "config 已就位"

# ── 7. push 车机 skills ────────────────────────────────────────
step "push 车机 skills（逐个 push，skill 发现只认单层目录）"
adb -s "$DEV" shell "su 0 mkdir -p $CAR_WORKSPACE/skills"
adb -s "$DEV" push "$ROOT/car-skills/fallback-webview" "$CAR_WORKSPACE/skills/"
adb -s "$DEV" push "$ROOT/car-skills/car-native" "$CAR_WORKSPACE/skills/"
ok "skills 已就位"

# ── 8. 运行 agent ──────────────────────────────────────────────
# session key 前缀：非 sk_v1_/agent: 开头则补 sk_v1_（车机端直接跑二进制无 start.sh 封装）
SESSION_FLAG=""
if [ -n "$SESSION" ]; then
  case "$SESSION" in
    sk_v1_*|agent:*) SESSION_FLAG="-s $SESSION" ;;
    *)               SESSION_FLAG="-s sk_v1_$SESSION" ;;
  esac
fi

if [ -n "$QUERY" ]; then
  step "运行 agent（单次任务）"
  bold "Query: $QUERY"
  adb -s "$DEV" shell "HOME=/data/local/tmp /data/local/tmp/picoclaw-core --no-color agent $DEBUG_FLAG $SESSION_FLAG -m '$QUERY'"
else
  step "部署完成，进入交互式 REPL"
  bold "交互式需 TTY，请按下面步骤进入设备 shell 后手动执行："
  printf "\n"
  printf "  adb -s %s shell\n" "$DEV"
  printf "  su 0\n"
  printf "  export HOME=/data/local/tmp\n"
  printf "  /data/local/tmp/picoclaw-core agent %s %s\n" "$DEBUG_FLAG" "$SESSION_FLAG"
  printf "\n"
  printf "  出现 You: 提示符即可输入，Ctrl+C 或 exit/quit 退出。\n"
fi