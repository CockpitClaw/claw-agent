---
name: fallback-webview
description: >
  车机端默认兜底 skill。凡是用户没有明确要求「用 XX app」的查询，一律走本 skill，用真实车机 WebView 浏览器打开网页完成，而不是内置 web_search/web_fetch 直接抓取。天然覆盖「通过浏览器 / 用浏览器 / 打开网页」的显式诉求，也覆盖「查天气、搜 GitHub、看热搜、浏览网页、媒体播放」等一切未指定原生 app 的信息检索与页面操作任务。
  触发场景：查信息、搜索、看网页、视频/音乐播放、订票购物（网页版）等一切默认走浏览器就能完成的任务。
  不触发：用户明确说「用 XX app 打开 / 玩 / 搜」等原生 app 任务 → 走 car-native skill。
user-invocable: false
---

# 车机浏览器兜底 Skill (Fallback WebView) — android-mcp

当用户**没有显式指定原生 app**时，默认用本 skill 驱动车机真实 WebView 打开网页完成任务。

## 环境

- 所有工具名带 `mcp_android_` 前缀（picoclaw 对 mcp server key=`android` 统一加前缀）。
- pageId 从 `mcp_android_list_pages` 的返回值拿；首次无页面时用 `mcp_android_navigate_page` 指定 URL。
- 浏览器真实渲染，DOM 优先、截图兜底。

## 工具调用方式（全部用 mcp_android_ 前缀）

```
mcp_android_list_pages                         → 列出当前页面，拿 pageId
mcp_android_navigate_page(pageId, url)         → 导航到 URL
mcp_android_take_snapshot(pageId)              → 拿语义 DOM（带 [ref=eN]）
mcp_android_take_screenshot(pageId)            → 截图（只在 DOM 失败时用）
mcp_android_click(pageId, target="eN")         → 按 ref 点击；或 click(pageId, x=, y=) 坐标点击
mcp_android_type(pageId, text)                 → 输入文字
mcp_android_evaluate_script(pageId, script)    → 读 location.href / document.title / 简单状态
mcp_android_scroll_up(pageId) / mcp_android_scroll_down(pageId)
```

## HARD RULES

1. ⛔ **绝对禁止 `web_search` / `web_fetch`**：本 skill 的任务必须走真实浏览器 `mcp_android_*` 工具。车机 config 已默认关闭 web_search/web_fetch，如仍可见说明配置有误。
2. **pageId 从 `list_pages` 拿**，不能凭空猜 pageId=1000（那是 mac 端 browser-mcp 的约定，车机端不同）。
3. **每次 navigate 后验证 URL**：用 `evaluate_script → location.href` 确认到达目标页。
4. **DOM 优先，截图兜底**：优先 `take_snapshot` 拿结构化 ref；截图只在 DOM 方案失败（SPA/canvas 无 DOM）时用。
5. **禁止拿 `take_snapshot` 做内容提取**：提取结果/状态用 `evaluate_script` 读 DOM，别让 LLM 自己解析整棵 DOM 树。
6. **连续失败 3 次 HARD STOP**：同一操作连续失败 2 次后必须停下告知用户「当前页面/平台无法完成」，禁止无脑试错。
7. **不自己拼接 URL**：搜索优先走搜索引擎真实首页（如 baidu.com）再导航搜索页；不确定的 URL 用 `evaluate_script` 从页面真实链接提取。

## 标准流程

1. `list_pages` → 拿 pageId
2. `navigate_page(pageId, url=搜索引擎或目标页)` → 验证 `location.href`
3. `take_snapshot(pageId)` → 拿 `[ref=eN]`
4. `click(pageId, target="eN")` 或读内容
5. 需要输入时 `type(pageId, text)`；需要滚动 `scroll_down/up`
6. 用 `evaluate_script` 提取最终结果并验证，再向用户汇报