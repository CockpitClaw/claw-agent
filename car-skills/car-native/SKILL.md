---
name: car-native
description: >
  车机端原生 app 自动化 skill。当用户明确要求「用 XX app 做 XXX」「打开 XX app」「在 XX app 里 XXX」时触发，走车机无障碍（native_*）工具操作真实原生 App 界面，而不是浏览器网页，也不是内置 web_search/web_fetch。
  触发场景：「用百度地图 app 搜附近加油站」「打开天气 app 看看」「用网易云音乐 app 放首歌」「在微信 app 里发条消息」等一切显式指定原生 app 的操作。
  不触发：用户说「通过浏览器/打开网页」或未指定 app 的信息检索 → 走 fallback-webview skill。
user-invocable: false
---

# 车机原生 App 自动化 Skill (Car Native) — android-mcp

当用户**明确要求用某个原生 app**完成任务时，用本 skill 通过无障碍服务驱动车机真实 App。

## 环境

- 所有工具名带 `mcp_android_native_` 前缀（picoclaw 对 mcp server key=`android` 加 `mcp_android_`，native 工具再带 `native_`）。
- 依赖无障碍服务（`NativeAccessibilityService` 已启用）。
- 原生界面无 DOM，只能靠无障碍节点树 + 坐标点击。

## 工具调用方式（全部用 mcp_android_native_ 前缀）

```
mcp_android_native_launch_app(packageName)        → 按包名启动 app，如 com.baidu.BaiduMap
mcp_android_native_get_foreground_app              → 拿当前前台 app 包名
mcp_android_native_get_ui_tree                     → 拿当前界面无障碍节点树（带 [ref=nN]）
mcp_android_native_click(x, y)                     → 坐标点击
mcp_android_native_click_node(ref="nN")            → 按 ref 点击节点
mcp_android_native_input_text(text)                → 向焦点输入框输入（先 native_click 聚焦）
mcp_android_native_input_to_node(ref, text)        → 直接给节点设置文字
mcp_android_native_scroll(direction)               → 手势滑动/滚动
mcp_android_native_press_back()                    → 返回键
mcp_android_native_press_home()                    → Home 键
mcp_android_native_screenshot                       → 全屏截图（系统级）
```

## HARD RULES

1. ⛔ **绝对禁止 `web_search` / `web_fetch`**：本 skill 的任务必须走 `mcp_android_native_*` 原生工具。车机 config 已默认关闭 web_search/web_fetch。
2. **启动 app 用包名**：`native_launch_app(packageName)`。不确定包名时先 `native_get_foreground_app` / `native_get_ui_tree` 探测，或如实告知用户「未安装/未找到该 app」。
3. **ref 只在两次 `get_ui_tree` 之间有效**：每次 `native_get_ui_tree` 后旧 ref 失效，重新拉树拿新 ref。
4. **坐标点击前先 `get_ui_tree`**：用坐标 `click(x,y)` 时，坐标必须来自最近一次 `native_get_ui_tree`（或 `native_screenshot` 同 displayId）返回的节点边界，不能凭空猜。
5. **输入文字先聚焦**：`native_input_text` 前先 `native_click`/`native_click_node` 点中目标输入框。
6. **操作前确认前台 app**：必要时 `native_get_foreground_app` 确认目标 app 已在前台（车机 sidebar 遮罩下 app 可能在后台但无障碍仍可读，如实处理）。
7. **连续失败 3 次 HARD STOP**：同一操作连续失败 2 次后停止并告知用户，禁止无脑试错。

## 标准流程

1. `native_launch_app(packageName)` 启动目标 app
2. `native_get_foreground_app` 确认已前台（被 sidebar 遮罩时仍可继续）
3. `native_get_ui_tree` 拿节点树
4. `native_click_node(ref="nN")` 或 `native_click(x,y)` 点目标
5. 输入：`native_click` 聚焦 → `native_input_text(text)`
6. 滚动：`native_scroll`；返回：`native_press_back`
7. 用 `native_get_ui_tree` 或 `native_screenshot` 验证结果，再向用户汇报