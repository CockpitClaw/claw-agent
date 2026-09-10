---
name: fallback-webview
description: >
  通用兜底 skill。当用户的需求不匹配任何专属 skill 时，通过浏览器作为执行手段去尝试满足，而不是回复"不支持/做不到"。
  触发场景：看直播/回放、点外卖、订酒店机票、买演出票、查快递、查信息、浏览网站、YouTube看视频、Spotify听歌等一切没有专属 skill 覆盖但可以通过网页完成的任务。
  示例："看世界杯直播"、"帮我点份外卖"、"订一张去上海的机票"、"YouTube上搜个视频"、"Spotify放首歌"、"B站看个视频"、"帮我查下明天天气"。
  不触发：已有专属 skill 覆盖的场景（JD购物→jd-shopping、淘宝→taobao-shopping、猫眼电影票→maoyan-movie）。
user-invocable: false
---

# 通用兜底 Skill (Fallback WebView) — browser

当用户的需求不匹配任何专属 skill 时，通过浏览器作为执行手段去尝试满足，而不是回复「不支持 / 做不到」。
核心思路：**平台注册表选目标 + ReAct 循环动态执行**，不预设固定步骤。
**DOM 树优先，截图兜底**：优先用 take_snapshot / evaluate_script 获取结构化数据，截图只在 DOM 方案失败时使用。

## HARD RULES

1. **禁止猜 URL**：所有 URL 必须来自 `platforms.md` 或 `evaluate_script` 从页面提取的真实链接。绝对禁止凭模型记忆拼接子路径。
2. **优先 ref 点击**：`take_snapshot` 拿 `[ref=eN]`，用 `click(pageId=1000, target="eN")` 点击。坐标点击作为 fallback（`click(pageId=1000, x=, y=)`）。
3. **每次 navigate 后必须验证 URL**：用 `evaluate_script → location.href` 确认到达目标页，检测是否被重定向。
4. **优先用站内搜索 URL**：`platforms.md` 中注册了 `search` URL 模板的平台，直接 `navigate_page` 跳搜索页，不要在首页层层点击。
5. **平台失败必须走 fallback 链**：当前平台 404/重定向/无法操作时，查 `platforms.md` 的 `fallback` 字段，依次尝试下一个平台。
6. **⛔ `take_snapshot` 只用于拿 ref 点击**：禁止用 `take_snapshot` 做搜索结果提取、状态检查、内容读取。这些有专用 helper（`search_and_extract_links` / `media_status` / `get_page_text`）。`take_snapshot` 返回的是完整 DOM 树，agent 自己解析是幻觉的主要来源。
7. **⛔ 禁止自己写 JS 做以下操作**（必须用 helper 工具，违反即返工）：
   - 提取链接/搜索结果 → 必须用 `search_and_extract_links(pageId, selector)`，selector 从下方"平台 selector 速查"表取
   - 检查视频/音频播放状态 → 必须用 `media_status(pageId)`
   - 播放/暂停媒体 → 必须用 `media_play` / `media_pause`
   - 等元素出现 → 必须用 `wait_for_element(pageId, selector, timeout)`
   - 关闭弹窗/Cookie → 必须用 `dismiss_overlays(pageId)`
   - 提取页面文本 → 必须用 `get_page_text(pageId)`
8. **首次加载国际网站必须关弹窗**：YouTube / Spotify / Google 等首次打开必定有 Cookie 同意弹窗，导航后立即 `dismiss_overlays`。
9. **`evaluate_script` 只用于**：读 `location.href`、读 `document.title`、读简单状态（如 `document.querySelector('video')?.duration`）、强制跳转 `window.location.href = "..."`。**不用于提取列表/链接/媒体状态**（那些有专用 helper）。
10. **⛔ 连续失败 3 次必须 HARD STOP**：同一操作（如 `media_status → found=false`、`search_and_extract_links → count=0`、`click` 无效果）连续失败 2 次后，第 3 次前必须停下来告知用户"当前平台/场景无法完成"，**禁止继续试错**。卡死循环浪费 token 且无意义。
11. **登录墙 vs 试听模式（必须区分）**：调 `detect_login_wall(pageId)` 确认状态：
    - `has_login_wall=true`（URL 含 /login、有密码输入框、弹登录 modal、文字含"请登录/需要登录/Sign in to"）→ ⛔ HARD STOP，告知用户手动登录，禁止绕过
    - `preview_mode=true` 但 `has_login_wall=false`（文字含"试听"、"Preview only"、"30秒"）→ **⚠️ 不要停**！这是试听模式，未登录也能播 30 秒片段。直接调 `click_play_button(pageId)` 尝试播放，再用 `media_status` 验证（自定义播放器可能返回 `type: "custom_player"`）
    - Apple Music/Spotify 用自定义播放器，**没有 `<audio>` 标签**，`media_status → found=false` 不等于"无法播放"，要先试 `click_play_button`
    - **禁止尝试绕过真正的登录墙**（不写 JS 注入、不调 API、不猜 URL）
12. **平台元数据优先**：执行前先读 `platforms.md` 该平台的备注，特别注意 `requires_login`（需登录）、`search_is_spa`（搜索页需等渲染）、`search_url_reliable`（搜索 URL 是否直接返回结果）字段。若 `requires_login=true` 且用户未登录，直接告知用户。
13. **平台选择优先级**：用户明确指定平台 → 直接用。未指定时按版权归属和可用性选：国内剧/综艺（庆余年、三体等）→ 国内平台（tencent_video/iqiyi/youku），国际内容（YouTube/TED/Netflix 等）→ YouTube，体育直播 → cctv5/migu。不要因为"能搜到"就优先走 YouTube——国内版权内容在 YouTube 上通常是搬运、低画质或无字幕，国内平台体验更好。
14. **⛔ 禁止轮询式验证**：`media_status` / `evaluate_script` 查状态，同一目标最多调 2 次。看到 `playing=true` 且 `currentTime` 在增长就是"在播"，立即汇报完成，不要反复检查等"更好的状态"。广告/片头/正片都是播放，不区分。违反此规则会陷入几十次循环浪费 token。

## 平台 selector 速查（用于 `search_and_extract_links` 的 selector 参数）

| 平台 | 提取目标 | selector | 备注 |
|------|---------|---------|------|
| YouTube | 视频链接 | `ytd-video-renderer a#video-title, ytd-grid-video-renderer a#video-title` | search URL 直接返回结果 |
| Bilibili | 视频链接 | `a[href*="/video/"]` | search URL 直接返回结果，可加 `&order=click` 按播放量排序 |
| Spotify | 歌曲/专辑 | `a[href*="/track/"], a[href*="/album/"]` | ⚠️ 需登录才能播放完整 |
| Apple Music | 专辑 | `a[href*="/album/"]` | ⚠️ 需登录才能播放，未登录只能浏览 |
| Apple Music | 艺人 | `a[href*="/artist/"]` | 中国版搜索结果主要返回 album/artist |
| Youku | 视频链接 | `a[href*="v.youku.com"]` | ⚠️ SPA，搜索 URL 打开后需 `wait_for_element` 等渲染 |
| Tencent Video | 视频链接 | `a[href*="v.qq.com"], .result-list a` | ⚠️ 部分内容需 VIP |
| Iqiyi | 视频链接 | `a[href*="iqiyi.com/v_"], .search-result a` | ⚠️ 部分内容需会员 |
| Youku | 视频链接 | `a[href*="v.youku.com"]` | SPA，等渲染 |
| Zhihu | 问题链接 | `a[href*="/question/"]` | ⚠️ 需登录才能看搜索结果 |
| Xiaohongshu | 笔记链接 | `a[href*="/explore/"], .note-item a` | ⚠️ 需登录 |
| Damai | 演出链接 | `a[href*="/item/"], .search-item a` | 需登录下单 |
| Ctrip | 产品链接 | `a[href*="/tour/"], .flight-item a` | 支付前需登录 |
| Google | 搜索结果 | `div.g a[href]:not([href*="google.com"])` | 国际兜底 |
| Baidu | 搜索结果 | `div.result a[href]:not([href*="baidu.com"])` | 国内兜底 |
| 通用（无专用 selector） | 所有链接 | 不传 selector，或传 `a[href]` | 最后手段 |

## 环境

- **pageId=1000**，整个流程都在这个页面。
- CSS px == viewport px，坐标直接用 `getBoundingClientRect()` 返回值。
- **截图坐标换算**：`take_screenshot` 返回 `devicePixelRatio`（Mac Retina=2.0）。从截图上识别的像素坐标 ÷ devicePixelRatio = CSS px = `click(x,y)` 的输入坐标。**不换算就会点偏**。
- `platforms.md` 里的 URL 都是 PC 版，browser 直接可用。

## 工具调用方式

直接用 browser MCP 原生工具：

```
# 导航与页面管理
navigate_page(pageId=1000, url="...")
list_pages()
new_page(url="...")

# DOM 树交互（首选）
take_snapshot(pageId=1000)               // 拿 [ref=eN] 语义树
click(pageId=1000, target="e7")          // ref 点击（首选）
click(pageId=1000, x=<x>, y=<y>)         // 坐标点击（fallback）
type(pageId=1000, target="e3", text="...")
fill(pageId=1000, target="e3", value="...")
hover(pageId=1000, target="e5")          // 触发 hover 菜单/tooltip
drag(pageId=1000, fromX=, fromY=, toX=, toY=)  // 拖拽（滑块、进度条）
scroll_down(pageId=1000) / scroll_up(pageId=1000)

# JS 执行
evaluate_script(pageId=1000, script="<JS>")
evaluate_script_in_frame(pageId=1000, frameUrl="...", script="<JS>")

# 通用 helper 工具（⛔ 禁止自己写 JS 替代这些工具）
search_and_extract_links(pageId=1000, selector="...")  // 提取链接，selector 可选
media_status(pageId=1000)      // 检查 <video>/<audio> 或自定义播放器（Apple Music/Spotify）状态
media_play(pageId=1000)        // 播放（自动尝试 .play() + 点播放按钮）
media_pause(pageId=1000)       // 暂停
click_play_button(pageId=1000) // 点击播放按钮（自定义播放器专用，Apple Music/Spotify 无 <audio> 标签）
wait_for_element(pageId=1000, selector="...", timeout=5000)  // 等待元素出现
dismiss_overlays(pageId=1000)  // 关闭 Cookie 同意/弹窗
get_page_text(pageId=1000)     // 提取标题+heading+段落（轻量，替代大 DOM 树）
detect_login_wall(pageId=1000) // 检测登录墙/未登录状态（media_status found=false 时必调）

# 截图（DOM 失败时兜底）
take_screenshot(pageId=1000)   // 返回 base64 + viewportWidth/Height + devicePixelRatio + screenshotWidth/Height
```

## 截图兜底路径（当 DOM 方案失败时）

```
1. take_snapshot 返回空树 / 无法定位目标元素
2. take_screenshot → 截图给 LLM 识别目标元素位置
3. LLM 返回截图上的像素坐标 (screenshot_x, screenshot_y)
4. 换算公式：
     click_x = screenshot_x / devicePixelRatio
     click_y = screenshot_y / devicePixelRatio
   例：Mac Retina (dpr=2.0)，截图上坐标 (800, 400) → click(400, 200)
5. click(pageId=1000, x=click_x, y=click_y)
6. 验证：evaluate_script 或 media_status 确认操作生效
```

⚠️ **截图兜底是最后手段**，优先级：ref 点击 > evaluate_script 取坐标 > 截图识别坐标。

## 执行流程

### Phase 0：平台选择

1. 解析用户 query → 提取 `{task_type, platform_preference, constraints, time_constraint}`
   - query 含 `最新的 / 最新 / 最近的 / 刚出的 / 新出的` → `time_constraint = latest`
   - `time_constraint = latest` 时：搜索词**必须加入当前年份前缀**（如 `2026世界杯决赛回放`）
2. 读取 `platforms.md`（本 skill 目录下）
3. 路由决策：

```
用户明确说了平台名？（"用B站看"、"在美团点"、"用YouTube搜"）→ 直接路由到该平台
没有明确平台？按 capabilities 标签匹配：
  task_type = sports_live → [cctv5, migu, tencent_sports]（cctv5 兼容性最好，首选）
  task_type = sports_live + query 含"世界杯" → 快速路径：直接用 cctv5.urls.world_cup_2026，跳过搜索
  task_type = tv_series / movie / variety_show + 国内版权内容 → [tencent_video, iqiyi, youku]（国内平台体验更好，不要走 YouTube 搜搬运）
  task_type = tv_series / movie / variety_show + 国际内容 → [youtube, bilibili]
  task_type = tv_series / movie / variety_show + 用户明确指定平台 → 直接用指定平台
  task_type = food_delivery → [meituan, eleme]
  task_type = flight_booking → [ctrip, fliggy, qunar]
  task_type = concert_ticket → [damai, maoyan_show]
  task_type = video / short_video → [bilibili, douyin]
  task_type = music → [spotify, apple_music, youtube]（Spotify Web Player 首选）
  task_type = music + query 含英文歌名/艺人 → [spotify, apple_music]（英文搜索更准）
  task_type = trending / hot_topic → [weibo]（微博热搜）
  task_type = code_search / repo_browse / developer → [github]（代码/仓库搜索）
  task_type = troubleshooting / qa + 技术类 → [stackoverflow] → [github]（技术问题）
  task_type = general_search → [baidu]（国内）/ [google_search]（国际）
完全无法分类？→ baidu 搜索兜底（国内）/ google_search（国际）
```

⚠️ **平台选择不要问用户**（除非两个同等优先级且任务差异大）。用户说"看世界杯"，直接选 cctv5。

### Phase 1：环境准备

```
Step 1.1: 构造目标 URL
  - 从 platforms.md 取 URL 模板
  - 有专属 URL（如 world_cup_2026）→ 直接用（最快路径）
  - 有 search 模板 + 用户有搜索词 → 直接拼搜索 URL
  - 无搜索词 → 用 homepage
  - ⛔ 禁止自己编造子路径

Step 1.2: 导航
  navigate_page(pageId=1000, url="<目标URL>")

Step 1.3: 验证（必须！）
  按 platforms.md 的 load_hint 决定等待时长：
  - load_hint: static → 等 1 秒
  - load_hint: spa / 无此字段 → 等 3 秒
  evaluate_script(pageId=1000, script="location.href")

  检查结果：
  - URL 匹配目标 → 进 Step 1.4
  - URL 被重定向（百度/移动版/登录页）→ 处理重定向（见错误恢复）
  - 404 / 空白页 → 走 fallback 链或换 URL

Step 1.4: 关闭弹窗（国际网站必须！）
  dismiss_overlays(pageId=1000)
  - YouTube / Spotify / Google 等首次打开必定有 Cookie 同意弹窗
  - 中国网站（bilibili/优酷等）一般没有，但调用也安全
  - 如果 dismiss_overlays 返回 dismissed > 0，等 0.5 秒再继续

Step 1.5: 登录墙检测（必须！）
  detect_login_wall(pageId=1000)
  - has_login_wall=true → 告知用户"需要登录，请在 Chrome 里手动登录"，⛔ HARD STOP
  - has_login_wall=false → 进 Step 1.6
  - 以下情况也必须调 detect_login_wall 确认：
    - URL 含 /login、/signin、/account
    - dismiss_overlays 后页面内容为空或跳到无关页
    - 后续 media_status → found=false 连续 2 次

Step 1.6: SPA 搜索结果等待（search_is_spa=true 的平台必须！）
  若 platforms.md 该平台标注 search_is_spa: true：
  wait_for_element(pageId=1000, selector="<搜索结果容器选择器>", timeout=8000)
  - 常见选择器：.search-results, .result-list, [class*="result"], [class*="search"]
  - found=true → 进 Phase 2
  - found=false, error=timeout → 可能搜索无结果或页面异常，走 fallback 链
```

### Phase 2：ReAct 执行循环

```
REPEAT:
  1. OBSERVE（观察当前页面状态）
     优先级：helper 工具 > evaluate_script 定向查询 > take_screenshot（兜底）
     ⛔ take_snapshot 不用于 OBSERVE，只用于需要 ref 点击时

     - 有对应 helper 时优先用 helper：
       search_and_extract_links(pageId=1000, selector="...")  // 提取链接（selector 从速查表取）
       media_status(pageId=1000)                                // 检查播放状态
       get_page_text(pageId=1000)                               // 提取页面文本
     - 无对应 helper 时用 evaluate_script 定向查询（只读 location.href / document.title / 简单状态）
     - 需要交互元素 ref 时用 take_snapshot（拿 ref 后立即 click，不解析树）
     - DOM 完全不可解析时才用 take_screenshot（截图兜底路径）

  2. PLAN（规划下一步）
     根据观察 + 用户目标，决定：需要点击什么？需要输入什么？需要导航到哪？任务是否完成？
     ⚠️ 失败计数：同一操作连续失败 2 次后，第 3 次前必须判断是否 HARD STOP（见规则 10）

  3. ACT（执行操作）
     - click(pageId=1000, target="eN") — ref 点击（首选）
     - click(pageId=1000, x=, y=) — 坐标点击（fallback，⚠️ 截图坐标需 ÷ devicePixelRatio）
     - hover(pageId=1000, target="eN") — 触发 hover 菜单
     - drag(pageId=1000, fromX=, fromY=, toX=, toY=) — 拖拽
     - type(pageId=1000, target="eN", text="...") — 输入
     - navigate_page(pageId=1000, url="...") — 导航
     - media_play(pageId=1000) / media_pause(pageId=1000) — 播放控制

  4. VERIFY（验证结果）
     用 helper 或 evaluate_script 检查：
     - URL 是否变化？→ evaluate_script → location.href
     - 视频/音频是否在播放？→ media_status
     - 元素是否出现？→ wait_for_element(pageId=1000, selector="...", timeout=3000)
     - 时效性验证（time_constraint = latest 时必做）：
       evaluate_script(pageId=1000, script=`(function(){
         var t = document.title + " " + location.href;
         var years = t.match(/20[2-9][0-9]/g) || [];
         return JSON.stringify({title: document.title.substring(0,60), years: years});
       })()`)
       - years 最大年份 ≥ 当前年份 → 时效满足，继续
       - years 全部 < 当前年份，或为空 → 结果过旧，返回上一步重选，搜索词补充年份再试

  5. 判断：任务完成 → Phase 3；需要用户确认 → 暂停询问；操作失败 → 错误恢复；继续 → 回到 OBSERVE
UNTIL 任务完成或不可恢复
```

每轮循环之间给用户一行状态更新。

### Phase 3：完成确认 + 汇报

```
1. 验证任务完成标志（⛔ 同一验证最多调 2 次，不要反复轮询）：
   - 视频播放：media_status → found=true, playing=true 即算完成
     ⚠️ 看到 playing=true 且 currentTime 在增长就是"在播"，**不要猜测是广告还是正片**——media_status 不区分，你也不该猜
     ⛔ 禁止在最终回复里写"可能先是前贴片广告"等猜测性内容——你不知道就别说，只汇报"正在播放 X，当前 N 秒"
     ⛔ 禁止：反复调 media_status 等 duration 变长 / 等 src 变化 / 等 currentTime 超过某个阈值——视频已经在播就是完成了，直接进汇报
   - 音乐播放：media_status → found=true, playing=true 即算完成（同上，不要反复轮询）
   - 搜索完成：结果列表已渲染
   - 表单提交：成功提示出现
   - 下单完成：订单号/确认页出现

2. 向用户汇报：简洁说明完成了什么，如有后续操作（如支付）明确告知
   - 视频播放场景：只说"正在播放 X，当前 N 秒"，⛔ 禁止猜测"这是广告/片头/正片"

3. 可选：take_screenshot 截图给用户看（视频场景不必截图）
```

## 常见任务模式

### 模式 A：视频播放/直播

```
1. 优先使用专属 URL（platforms.md 的具名 URL，如 world_cup_2026）
   没有专属 URL → 导航到搜索 URL
2. search_and_extract_links(pageId=1000, selector="<platforms.md 中的选择器>") 提取视频链接
   无选择器 → search_and_extract_links(pageId=1000) 提取所有链接，从中筛选
3. 选最匹配的 → click ref（⚠️ 很多视频站点击链接会在新标签打开视频页，原 pageId=1000 仍是搜索页）
4. ⚠️ **click 后必须 list_pages 确认实际视频页的 pageId**！视频页常在新标签打开（pageId=1001/1002...）
   - list_pages → 找 URL 含视频详情的 pageId（如 iqiyi.com/v_、v.qq.com/x/cover、bilibili.com/video）
   - 后续所有操作都用这个新 pageId，⛔ 禁止继续用 pageId=1000（搜索页）
   - 如果没有新标签（URL 直接在 1000 变了），继续用 1000
5. wait_for_element(pageId=<新ID>, selector="video", timeout=5000) 等播放器加载
6. media_status(pageId=<新ID>) 检查状态（第 1 次）
7. 如果 paused → media_play(pageId=<新ID>) → 再 media_status 确认（第 2 次，⚠️ 这是最后一次验证）
8. 如果 no-video → 页面可能需要先点击播放入口，take_snapshot 找入口
9. ⛔ 看到 playing=true 且 currentTime > 0 即算"播放中" → 立即汇报完成，进 Phase 3
   ⛔ 禁止猜测"这是广告/片头/正片"——media_status 不返回这个信息，你猜就是脑补。只说"正在播放 X，当前 N 秒"
   ⛔ 禁止：反复调 media_status 等"正片开始"/"duration 变长"/"src 变化"——这会陷入几十次循环
```

### 模式 A'：音乐播放（Spotify / Apple Music）

```
1. 导航到搜索 URL（platforms.md 的 search 模板）
2. dismiss_overlays(pageId=1000) — 关 Cookie 弹窗
3. search_and_extract_links(pageId=1000, selector="a[href*='/track/']")  // Spotify
   或 search_and_extract_links(pageId=1000, selector="a[href*='/album/']")  // Apple Music
4. 选最匹配的 → click ref 或 navigate_page(href)
5. wait_for_element(pageId=1000, selector="audio,video,[data-testid='play-button']", timeout=5000)
6. media_status(pageId=1000) 检查状态
7. 如果 paused → media_play(pageId=1000)
8. 再次 media_status 确认播放中
⚠️ Spotify/Apple Music 未登录只能预览 30 秒片段，完整播放需用户手动登录
```

### 模式 A''：YouTube 搜索播放

```
1. 导航到搜索 URL：https://www.youtube.com/results?search_query=<query>
2. dismiss_overlays(pageId=1000) — 关 Cookie 弹窗
3. search_and_extract_links(pageId=1000, selector="ytd-video-renderer a#video-title, ytd-grid-video-renderer a#video-title")
4. 提取 videoId（从 href 中 watch?v=VIDEO_ID），navigate_page 到 https://www.youtube.com/watch?v=VIDEO_ID
5. wait_for_element(pageId=1000, selector="video", timeout=5000)
6. media_status(pageId=1000) 检查播放状态
7. 如果 paused → media_play(pageId=1000)
8. 验证 duration ≥ 预期（区分完整视频和短视频）
```

### 模式 B：搜索信息

```
1. 导航到搜索 URL
2. search_and_extract_links(pageId=1000, selector="<平台选择器>") 提取搜索结果
3. 展示给用户 或 直接提取答案
4. 如需进入详情页 → click ref 或 navigate_page(href)
```

### 模式 C：表单填写/下单

```
1. 导航到目标页面
2. take_snapshot 找所有 input/select/textarea 的 ref
3. 逐个 type(pageId=1000, target="eN", text="...")
4. 找提交按钮 ref → click
5. ⚠️ 涉及支付/个人信息 → 必须用户确认后才能提交
```

### 模式 D：浏览/查看

```
1. 导航到目标页面
2. get_page_text(pageId=1000) 提取页面关键内容（轻量，不会撑爆上下文）
3. 整理后告诉用户
4. 如需翻页/展开 → scroll_down 或找对应按钮 click
```

## 错误恢复策略

| 错误 | 恢复方式 |
|------|---------|
| **404 / 页面不存在** | 查 platforms.md fallback 链 → 换下一个平台；或回首页用站内搜索 |
| **重定向到百度/外部页** | 重新 navigate 到目标 URL；反复重定向 → 换平台 |
| **重定向到移动版 (m.xxx)** | 检查移动版 DOM 是否可用；不可用 → 换平台 |
| **重定向到登录页** | 告知用户"需要登录，请在 Chrome 里完成登录"，⛔ HARD STOP |
| **ref 点击无效** | 改用 evaluate_script 获取坐标 → click(x, y) |
| **坐标点击偏移** | 检查 devicePixelRatio！截图坐标 ÷ dpr = CSS px 坐标 |
| **SPA 导航失效**（点击后 URL 不变） | evaluate_script → window.location.href = "目标URL" 强制跳转 |
| **弹窗遮挡** | dismiss_overlays(pageId=1000) 关闭；不行则 take_snapshot 找关闭按钮 |
| **Cookie 同意弹窗**（YouTube/Spotify/Google） | dismiss_overlays(pageId=1000) 自动处理 |
| **页面加载超时** | 按 load_hint 重试一次；仍失败 → 换 URL 或换平台 |
| **平台不可用** | 走 fallback 链；所有 fallback 都失败 → 告知用户 |
| **播放不自动开始** | media_play(pageId=1000)；如果失败，take_snapshot 找播放按钮 click |
| **元素未出现** | wait_for_element(pageId=1000, selector="...", timeout=5000) 等 SPA 渲染 |

## 安全规则

| 场景 | 规则 |
|------|------|
| **支付/付款** | ⛔ 必须用户明确确认后才能操作，展示金额后 HARD STOP |
| **个人信息**（手机号/身份证/地址） | ⛔ 必须用户确认后才能填写/提交 |
| **不可逆操作**（下单/提交/删除） | ⛔ 必须用户确认 |
| **多个选项需决策**（选哪个商品/场次/座位） | 展示选项，等用户选择 |
| **登录/授权** | 告知用户需要手动操作，不代为输入账号密码 |

## 与专属 skill 的边界

| 场景 | 走哪个 |
|------|--------|
| "帮我买 X"（无平台） | **jd-shopping**（专属） |
| "在淘宝买 X" | **taobao-shopping**（专属） |
| "买电影票" | **maoyan-movie**（专属） |
| "看世界杯直播" / "点份外卖" / "订张机票" / "YouTube搜视频" / "Spotify放歌" / "B站看视频" | **fallback-webview**（本 skill） |

⚠️ **当 fallback 执行中发现任务其实属于某个专属 skill 的范畴**（如用户说"帮我买个东西"但没指定平台，你走了 fallback 打开了京东网页），应建议切换到专属 skill 以获得更稳定的体验。
