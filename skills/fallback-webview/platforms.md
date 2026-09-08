# platforms.md — 平台注册表

> fallback-webview skill 的平台数据源。  
> 每个平台注册：URL 模板、能力标签（capabilities）、加载提示（load_hint）、WebView 兼容备注、fallback 链。  
> ⚠️ **所有 URL 均为 PC 版**，禁止使用 m.、h5.、i. 等移动子域。

---

## 路由规则速查

```
精确匹配：query 明确提到平台名 → 直接路由到该平台
能力匹配：按 capabilities 标签匹配 → 按优先级选最佳平台
通用兜底：无法分类 → baidu 搜索
平台失败：按 fallback 链依次尝试下一个平台
指向专属 skill：fallback 值为 skill 名 → 建议切换专属 skill
```

**load_hint 说明**
- `static` → navigate 后等 **1 秒**（静态页面，加载快）
- `spa` → navigate 后等 **3 秒**（SPA/动态页面，等 JS 渲染）
- 无此字段 → 默认等 **3 秒**（保守策略）

**快速路径（减少 ReAct 轮次）**
- `task_type = sports_live` → 优先级：`cctv5 > migu > tencent_sports`
- `query 含"世界杯" + task_type = sports_live` → 直接用 `cctv5` 的 `world_cup_2026` URL，跳过搜索
- `task_type = tv_series / movie / variety_show` → 优先级：`tencent_video > iqiyi > youku`（根据版权归属选首选）

---

## 体育 / 直播

### cctv5 — CCTV5 / 央视网

**capabilities**: `world_cup` `sports_live` `sports_replay` `news` `documentary`  
**load_hint**: `static`（等 1 秒）  
**fallback**: `migu` → `tencent_sports`

**URLs**
```
homepage:       https://tv.cctv.com/live/cctv5/
search:         https://search.cctv.com/search.php?qtext={query}&type=video
sports_channel: https://tv.cctv.com/sports/
world_cup_2026: https://worldcup.cctv.com/2026/index.shtml
```

**备注**
- 兼容性最好，静态页面为主，**首选**体育视频平台
- 世界杯任务优先直接跳 `world_cup_2026`，比走搜索少 2~3 轮 ReAct
- ⚠️ **仅用 `world_cup_2026`（index.shtml）**；`/schedule/index.shtml` 等子路径是动态渲染页（SPA），agent 扫 3 轮也无结果，**禁止主动导航到 schedule 子路径**
- 世界杯页面 DOM 选择器（定向查询，禁止全量扫描 `<a>`）：
  - 视频/回放链接：`.match-video a, .video-list a, .content-video a`
  - 搜索结果视频链接：`a[href*="sports.cctv.com"], a[href*="/VIDE"]`
- **全场回放 vs 新闻片段 区分策略（⚠️ 关键）**：
  - ✅ 优先选文字含 `全场`、`全场回放`、`完整`、`回放` 的链接
  - ❌ 跳过文字含 `集锦`、`精彩片段`、`新闻`、`视频新闻`、`进球` 的链接
  - 如果页面上无法区分，**用 search URL 加 `全场回放` 后缀**：
    ```
    search.cctv.com/search.php?qtext=2026世界杯决赛全场回放&type=video
    ```
  - 进入视频页后验证时长：全场回放 duration ≥ 5400 秒（90分钟）；若 duration < 600 秒（10分钟）说明是片段，**必须返回重新选**
- 视频页 `<video>` 通常自动播放，验证脚本：
  ```js
  var v=document.querySelector("video"); v ? JSON.stringify({paused:v.paused,time:v.currentTime,duration:v.duration,src:v.src.substring(0,80)}) : "no-video"
  ```
  - `duration ≥ 5400` → 全场回放 ✅
  - `duration < 600` → 新闻/片段，返回重选 ❌
- 搜索词策略：
  - 用户说"回放/完整比赛/全程" → 搜索词加后缀 `全场回放`
  - 用户说"集锦/精彩片段" → 搜索词加后缀 `集锦`
  - 用户说"直播" → 优先 `world_cup_2026` 首页，找"直播中"标签
  - 用户说"最新的" → **搜索词必须加年份前缀**（如 `2026世界杯决赛全场回放`），不加年份大概率返回历史结果
- **时效性验证（"最新的"必须满足）**：
  - 进入视频页后，`evaluate_script` 检查页面标题/URL 是否含当前年份（`2026`）
  - 若标题含旧年份（`2022`、`2023`、`2024`）→ **此结果不满足"最新"，必须返回重选**
  - 验证脚本：
    ```js
    var title = document.title + " " + location.href;
    var year = title.match(/20[2-9][0-9]/g);
    JSON.stringify({title: document.title.substring(0,60), years_found: year})
    ```
  - 返回 years_found 中最大年份 ≥ 当前年份 → ✅ 时效满足
  - 返回 years_found 全部 < 当前年份 → ❌ 返回重选，在搜索词里补充年份
- 若页面提示需要 App 或跳转移动版，强制用 PC URL 重新 navigate

---

### migu — 咪咕视频

**capabilities**: `world_cup` `sports_live` `nba` `cba` `esports` `ufc`  
**load_hint**: `spa`（等 3 秒）  
**fallback**: `cctv5` → `tencent_sports`

**URLs**
```
homepage: https://www.miguvideo.com
search:   （站内搜索不可靠，用首页导航）
```

**备注**
- SPA 应用，子路径（`/sport/live` 等）大概率 404，**必须从首页进入**
- 部分链接点击后跳转到外部页（如百度），需检测 URL 变化
- ref 点击不可用，必须用坐标

---

### tencent_sports — 腾讯体育

**capabilities**: `nba` `cba` `sports_live` `sports_replay` `esports`  
**fallback**: `cctv5` → `migu`

**URLs**
```
homepage: https://sports.qq.com
search:   https://sports.qq.com/search/?keyword={query}
```

**备注**
- NBA/CBA 版权持有方
- 部分直播需要 VIP

---

### iqiyi_sports — 爱奇艺体育

**capabilities**: `sports_live` `football` `tennis` `golf`  
**fallback**: `cctv5` → `migu`

**URLs**
```
homepage: https://sports.iqiyi.com
```

---

## 外卖 / 本地生活

### meituan — 美团外卖

**capabilities**: `food_delivery` `local_life` `hotel` `grocery` `group_buy`  
**fallback**: `eleme`

**URLs**
```
homepage: https://www.meituan.com
search:   https://www.meituan.com/search/keyword/{query}
food:     https://bj.meituan.com/meishi/
```

**备注**
- 需要登录（手机号/微信）
- 外卖需要定位，首次使用需用户设置地址
- ⚠️ 下单前必须用户确认：商品、金额、收货地址

---

### eleme — 饿了么

**capabilities**: `food_delivery` `local_life` `grocery`  
**fallback**: `meituan`

**URLs**
```
homepage: https://www.ele.me
search:   https://www.ele.me/search/{query}
```

**备注**
- 需要登录（手机号/支付宝）
- ⚠️ 下单前必须用户确认

---

## 出行 / 预订

### ctrip — 携程

**capabilities**: `flight_booking` `hotel_booking` `train_ticket` `travel_package` `scenic_ticket`  
**fallback**: `fliggy` → `qunar`

**URLs**
```
homepage:      https://www.ctrip.com
flight_search: https://flights.ctrip.com/online/list/oneway-{from}-{to}?depdate={date}
hotel_search:  https://hotels.ctrip.com/infosite/list.html?city={city}
train_search:  https://trains.ctrip.com/TrainBooking/SearchTrain?fromStation={from}&toStation={to}&dDate={date}
search:        https://www.ctrip.com/search/?query={query}
```

**备注**
- 综合出行首选，机票/火车/酒店均有
- 需要登录（手机号/微信）
- ⚠️ 支付前必须用户确认

---

### fliggy — 飞猪（阿里旅行）

**capabilities**: `flight_booking` `hotel_booking` `train_ticket` `travel_package`  
**fallback**: `ctrip` → `qunar`

**URLs**
```
homepage:      https://www.fliggy.com
flight_search: https://www.fliggy.com/sale/flightlist.htm?depCity={from}&arrCity={to}&ddate={date}
search:        https://www.fliggy.com/search/index.htm?keyword={query}
```

**备注**
- 阿里系，可用淘宝/支付宝账号登录
- ⚠️ 支付前必须用户确认

---

### qunar — 去哪儿

**capabilities**: `flight_booking` `hotel_booking` `train_ticket` `travel_package`  
**fallback**: `ctrip` → `fliggy`

**URLs**
```
homepage:      https://www.qunar.com
flight_search: https://flight.qunar.com/site/oneway.htm?searchDepartureAirport={from}&searchArrivalAirport={to}&searchDepartureDate={date}
hotel_search:  https://hotel.qunar.com/city/{city}/
search:        https://www.qunar.com/site/search.htm?query={query}
```

**备注**
- 比价功能强，机票价格信息丰富
- 需要去哪儿账号或手机登录

---

## 演唱会/音乐节门票

### damai — 大麦

**capabilities**: `concert_ticket` `theater_ticket` `sports_ticket` `exhibition_ticket`  
**fallback**: `maoyan_show`

**URLs**
```
homepage: https://www.damai.cn
search:   https://search.damai.cn/search.htm?keyword={query}
```

**备注**
- PC 版（`www.damai.cn`）
- 需要登录（淘宝 / 支付宝账号）
- 热门演出需要抢票

---

## 视频 / 内容

### tencent_video — 腾讯视频

**capabilities**: `tv_series` `movie` `variety_show` `live_stream` `sports_replay` `kids_content`  
**fallback**: `iqiyi` → `youku`

**URLs**
```
homepage: https://v.qq.com
search:   https://v.qq.com/x/search/?q={query}
```

**备注**
- 热播剧/综艺首选（如《庆余年》《繁花》等腾讯独播剧）
- PC 版搜索结果：`.result-list a[href*="/x/cover/"]`
- 部分内容需要 VIP

---

### iqiyi — 爱奇艺

**capabilities**: `tv_series` `movie` `variety_show` `documentary` `kids_content`  
**fallback**: `tencent_video` → `youku`

**URLs**
```
homepage: https://www.iqiyi.com
search:   https://so.iqiyi.com/so/q_{query}
```

**备注**
- 爱奇艺独播剧/综艺首选
- 部分内容需要会员

---

### youku — 优酷

**capabilities**: `tv_series` `movie` `variety_show` `documentary`  
**fallback**: `tencent_video` → `iqiyi`

**URLs**
```
homepage: https://www.youku.com
search:   https://so.youku.com/search_video/q_{query}
```

**元数据**
- `requires_login`: false（浏览不需登录，部分内容需会员才能播放）
- `search_is_spa`: true（搜索 URL 打开后需等 JS 渲染）
- `search_url_reliable`: false（搜索结果可能不直接渲染，需 `wait_for_element`）

**备注**
- 优酷/阿里独播内容首选
- 部分内容需要会员
- ⚠️ SPA 搜索：navigate 后必须 `wait_for_element(pageId, selector=".search-result, [class*='result']", timeout=8000)` 等渲染
- 视频链接 selector：`a[href*="v.youku.com"]`

---

### bilibili — 哔哩哔哩

**capabilities**: `video` `anime` `live_stream` `educational`  
**fallback**: （无）

**URLs**
```
homepage: https://www.bilibili.com
search:   https://search.bilibili.com/all?keyword={query}
```

**备注**
- 视频播放器兼容性较好
- 部分视频需要登录
- 搜索结果视频链接选择器：`a[href*="/video/"]`
- 搜索 URL 支持排序：`https://search.bilibili.com/all?keyword={query}&order=click`（按播放量排序）
- 视频卡片文字提取：用 `search_and_extract_links(pageId=1000, selector="a[href*='/video/']")` 提取视频链接
- ⚠️ **禁止自己写 JS 做搜索结果提取**，必须用 `search_and_extract_links`
- 播放状态验证：`media_status(pageId=1000)`

---

### douyin — 抖音

**capabilities**: `short_video` `live_stream` `shopping`  
**fallback**: （无）

**URLs**
```
homepage: https://www.douyin.com
search:   https://www.douyin.com/search/{query}
```

---

### weibo — 微博

**capabilities**: `trending` `social` `news` `hot_topic`  
**fallback**: `baidu` → `google_search`

**URLs**
```
homepage:    https://www.weibo.com
hot_search:  https://s.weibo.com/top/summary
search:      https://s.weibo.com/weibo?q={query}
```

**元数据**
- `requires_login`: false（浏览热搜不需登录，发微博/评论需登录）
- `search_is_spa`: false（热搜页是静态渲染）
- `search_url_reliable`: true（hot_search URL 直接返回热搜榜）

**备注**
- 热搜榜页面（`s.weibo.com/top/summary`）是服务端渲染的表格，直接 `get_page_text` 即可提取
- 热搜条目选择器：`#pl_top_realtimehot table tbody tr td:nth-child(2) a`（文字+链接）
- ⚠️ 首次访问可能有登录弹窗，`dismiss_overlays` 关掉
- 热搜榜每条带排名（1-50），文字末尾可能有"热"、"爆"、"新"等标签
- 验证脚本：
  ```js
  var items = document.querySelectorAll('#pl_top_realtimehot table tbody tr td:nth-child(2) a');
  JSON.stringify({count: items.length, top5: Array.from(items).slice(0,5).map(a => a.textContent.trim())})
  ```

---

### zhihu — 知乎

**capabilities**: `qa` `article` `knowledge`  
**fallback**: `baidu` → `google_search`

**URLs**
```
homepage: https://www.zhihu.com
search:   https://www.zhihu.com/search?type=content&q={query}
question: https://www.zhihu.com/question/{questionId}
```

**元数据**
- `requires_login`: true（⚠️ 搜索结果必须登录才能看，未登录会弹登录框）
- `search_is_spa`: true
- `search_url_reliable`: false（未登录时 search URL 不返回结果）

**备注**
- ⚠️ 未登录访问搜索页会弹登录框，`dismiss_overlays` 关掉后页面可能跳转或内容为空
- 登录墙检测：页面有 `.sign-in`、`[class*="login-modal"]`、`input[type=tel]` → HARD STOP
- 已登录搜索结果选择器：`a[href*="/question/"]`、`.SearchResult-Card a`
- 问题页回答内容选择器：`.RichContent-inner`、`.AnswerCard`
- ⚠️ 若用户未登录，建议改用 `baidu` 搜索 `site:zhihu.com <query>` 兜底

---

### xiaohongshu — 小红书

**capabilities**: `lifestyle` `review` `recipe` `travel_guide`  
**fallback**: （无）

**URLs**
```
homepage: https://www.xiaohongshu.com
search:   https://www.xiaohongshu.com/search_result?keyword={query}
```

**元数据**
- `requires_login`: true（⚠️ 搜索必须登录）
- `search_is_spa`: true
- `search_url_reliable`: false

**备注**
- 未登录会弹登录框，检测到就 HARD STOP
- 笔记链接选择器：`a[href*="/explore/"]`、`.note-item a`
---

## 工具 / 信息

### baidu — 百度（最终兜底）

**capabilities**: `general_search` `translation` `calculator` `weather` `express_tracking`  
**fallback**: （终点，无后继）

**URLs**
```
homepage: https://www.baidu.com
search:   https://www.baidu.com/s?wd={query}
```

**备注**
- 通用兜底搜索引擎，当没有更具体的平台匹配时使用
- 搜索结果选择器：`div.result a[href]:not([href*="baidu.com"])`
- ⚠️ 百度搜索结果链接经重定向，提取真实 URL 需 `evaluate_script` 取 `a[href]` 后跟踪

---

## 开发 / 技术

### github — GitHub

**capabilities**: `code_search` `repo_browse` `developer` `documentation`  
**load_hint**: `spa`（等 2 秒）
**fallback**: `google_search` → `baidu`

**URLs**
```
homepage:   https://github.com
search:     https://github.com/search?q={query}&type=repositories
trending:   https://github.com/trending
repo:       https://github.com/{owner}/{repo}
issues:     https://github.com/{owner}/{repo}/issues
readme:     https://github.com/{owner}/{repo}#readme
```

**元数据**
- `requires_login`: false（浏览/搜索不需登录，clone/star/fork 需登录）
- `search_is_spa`: false（搜索结果服务端渲染）
- `search_url_reliable`: true（search URL 直接返回结果）

**备注**
- 搜索仓库选择器：`div[data-testid="results-list"] div h3 a`（新版）或 `.repo-list-item a`
- trending 页选择器：`article.Box-row h2 a`
- README 渲染选择器：`article.markdown-body`
- ⚠️ 首次打开无 Cookie 弹窗，可直接操作
- 搜索建议：仓库名/关键词搜索用 `type=repositories`；代码搜索用 `type=code`；用户搜索用 `type=users`
- ⚠️ 国内访问可能慢或超时，超时时走 `google_search` 或 `baidu` 兜底

---

### stackoverflow — Stack Overflow

**capabilities**: `qa` `developer` `troubleshooting`  
**load_hint**: `static`（等 1 秒）
**fallback**: `github` → `google_search`

**URLs**
```
homepage: https://stackoverflow.com
search:   https://stackoverflow.com/search?q={query}
question: https://stackoverflow.com/questions/{questionId}
```

**元数据**
- `requires_login`: false
- `search_is_spa`: false
- `search_url_reliable`: true

**备注**
- 搜索结果选择器：`div.s-post-summary div.s-post-summary--content-title a`
- 问题答案选择器：`.js-post-body`
- ⚠️ 国内访问可能慢，超时时走 `google_search` 兜底

---

## 音乐

### spotify — Spotify Web Player

**capabilities**: `music` `podcast` `playlist` `album` `artist`
**load_hint**: `spa`（等 3 秒）
**fallback**: `apple_music` → `youtube`

**URLs**
```
homepage:    https://open.spotify.com
search:      https://open.spotify.com/search/{query}
album:       https://open.spotify.com/album/{albumId}
playlist:    https://open.spotify.com/playlist/{playlistId}
artist:      https://open.spotify.com/artist/{artistId}
```

**元数据**
- `requires_login`: true（⚠️ 完整播放必须登录，未登录只能预览 30 秒片段）
- `search_is_spa`: true（搜索页需等渲染）
- `search_url_reliable`: true（登录后 search URL 直接返回结果）

**备注**
- SPA 应用，首次加载慢（3~5 秒），navigate 后等 4 秒更稳
- ⚠️ 首次打开必有 Cookie 同意弹窗，用 `dismiss_overlays` 关闭（选择器：`button#onetrust-accept-btn-handler` 或 `button[data-testid="cookie-accept"]`）
- 搜索结果选择器（定向查询，禁止全量扫描）：
  - 歌曲：`a[href*="/track/"]`
  - 专辑：`a[href*="/album/"]`
  - 艺人：`a[href*="/artist/"]`
  - 播客：`a[href*="/episode/"]`
- 播放按钮：`button[data-testid="play-button"]` 或 `button[aria-label*="Play"]`
- 暂停按钮：`button[data-testid="pause-button"]` 或 `button[aria-label*="Pause"]`
- 播放状态验证脚本：
  ```js
  var v=document.querySelector("video,audio"); var pb=document.querySelector('[data-testid="play-button"],[aria-label*="Pause"]'); JSON.stringify({hasMedia:!!v, hasPlayBtn:!!pb, paused:v?v.paused:true})
  ```
- ⚠️ **未登录检测**：`media_status → found=false` 且页面有 `[class*="login"]` 或 `[data-testid="login-button"]` → HARD STOP 告知用户登录
- ⚠️ 部分功能需要 Premium 登录；免费版有广告弹窗
- 未登录只能预览 30 秒片段，完整播放需要用户在 Chrome 中手动登录

---

### apple_music — Apple Music Web Player

**capabilities**: `music` `radio` `playlist` `album` `artist` `music_video`
**load_hint**: `spa`（等 4 秒，Apple JS bundle 较大）
**fallback**: `spotify` → `youtube`

**URLs**
```
homepage:    https://music.apple.com
search:      https://music.apple.com/search?term={query}
album:       https://music.apple.com/album/{albumId}
playlist:    https://music.apple.com/playlist/{playlistId}
artist:      https://music.apple.com/artist/{artistId}
```

**元数据**
- `requires_login`: true（⚠️ 完整播放必须登录 Apple ID，未登录只能浏览）
- `search_is_spa`: true（搜索页需等渲染，navigate 后等 5 秒）
- `search_url_reliable`: false（search URL 可能跳主页，需 `wait_for_element` 确认搜索结果容器）
- `has_native_app`: true（macOS 有 Music.app，可 fallback 到 `musics://` URL scheme）

**备注**
- SPA 应用，加载较慢，navigate 后等 5 秒
- ⚠️ 需要登录 Apple ID 才能完整播放；未登录可浏览但不能播放
- Cookie 同意弹窗选择器：`button[data-testid="cookie-consent-accept"]` 或 `.cookie-banner button`
- 搜索结果选择器：
  - 歌曲/专辑：`a[href*="/album/"]`（国际版和中国版通用）
  - 艺人：`a[href*="/artist/"]`
  - 播放列表：`a[href*="/playlist/"]`
  - ⚠️ 中国版搜索结果主要返回 `/album/` 和 `/artist/`，不含 `/song/`
- 播放按钮：`button[aria-label*="Play"]` 或 `.play-button`
- 播放状态验证脚本：
  ```js
  var v=document.querySelector("video,audio"); JSON.stringify({hasMedia:!!v, paused:v?v.paused:true, src:v?v.src.substring(0,80):""})
  ```
- Apple Music 搜索词用英文更准（中文支持有限）
- ⚠️ **未登录检测**：`media_status → found=false` 且页面有 `[class*="signin"]` 或 `[aria-label*="Sign In"]` → HARD STOP 告知用户登录
- ⚠️ **网页版失败 fallback**：若未登录且用户想播放，建议用本地 Music.app（`musics://` URL scheme），告知用户在 Chrome 里手动放行协议

---

## 国际视频

### youtube — YouTube

**capabilities**: `video` `music` `live_stream` `short_video` `educational` `tutorial` `search`
**load_hint**: `spa`（等 3 秒）
**fallback**: `bilibili` → `baidu`

**URLs**
```
homepage:    https://www.youtube.com
search:      https://www.youtube.com/results?search_query={query}
watch:       https://www.youtube.com/watch?v={videoId}
channel:     https://www.youtube.com/c/{channelName}
shorts:      https://www.youtube.com/shorts/{shortId}
live:        https://www.youtube.com/live/{liveId}
```

**备注**
- ⚠️ 首次打开必有 Cookie 同意弹窗，用 `dismiss_overlays` 关闭（选择器：`button[aria-label*="Accept"]` 或 `ytd-button-renderer#accept-button button`）
- 搜索结果选择器（定向查询）：
  - 视频链接：`ytd-video-renderer a#video-title, ytd-grid-video-renderer a#video-title`
  - 播放列表：`ytd-playlist-renderer a`
  - 频道：`ytd-channel-renderer a`
- 每个视频链接的 `href` 含 `watch?v=VIDEO_ID`，提取后可直接 `navigate_page` 跳转
- 播放器交互：
  - 播放/暂停：`button.ytp-play-button` 或 `evaluate_script → document.querySelector('video').play() / .pause()`
  - 全屏：`button.ytp-fullscreen-button`
  - 音量：`button.ytp-mute-button`（切换静音）或 `input.ytp-volume-slider`
- 播放状态验证脚本：
  ```js
  var v=document.querySelector("video"); v ? JSON.stringify({paused:v.paused, time:v.currentTime, duration:v.duration, src:v.src.substring(0,80)}) : "no-video"
  ```
- ⚠️ YouTube 搜索结果页是懒加载滚动列表，`take_snapshot` 只能看到可视区域。需要更多结果时 `scroll_down`
- YouTube Music（`music.youtube.com`）是独立产品，如需纯音乐播放可导航到 `https://music.youtube.com/search?q={query}`

---

### google_search — Google 搜索（国际兜底）

**capabilities**: `general_search` `translation` `calculator` `weather` `news` `knowledge`
**fallback**: （终点，无后继）

**URLs**
```
homepage: https://www.google.com
search:   https://www.google.com/search?q={query}
```

**备注**
- 国际场景的通用兜底搜索引擎（国内场景用 baidu）
- ⚠️ 首次打开可能有 Cookie 同意弹窗，选择器：`button#L2AGLb`（接受所有）或 `div[role="dialog"] button`
- 搜索结果选择器：
  - 自然结果：`div.g a[href]:not([href*="google.com"])`
  - 知识面板：`div.kp-blk`
  - 精选摘要：`div.cXedhb`
- Google 搜索结果链接通常被 Google 重定向（`/url?q=REAL_URL`），提取真实 URL 需：
  ```js
  Array.from(document.querySelectorAll('div.g a')).map(a => ({text:a.textContent.substring(0,80), href:a.href})).filter(r=>r.href&&!r.href.includes('google.com')).slice(0,10)
  ```
- ⚠️ Google 在某些网络环境下不可用，此时走 `baidu` 兜底

---

## 如何注册新平台（扩展指南）

> 想支持新网站（如豆瓣、知乎、小红书、StackOverflow、Wikipedia 等）？  
> 只需在本文件加一个平台条目，不需要改任何代码。

### 新增平台步骤

1. **选一个合适的位置**：按类别分组（视频/外卖/出行/工具信息等），或新增类别标题
2. **填写条目模板**（必填项用 ⭐ 标注）：

```
### {platform_key} — {显示名}

**capabilities**: ⭐ `tag1` `tag2` ...
**load_hint**: ⭐ `static`（等 1 秒）/ `spa`（等 3 秒）/ 不写（默认 3 秒）
**fallback**: ⭐ `next_platform` → `fallback_skill`（或"（无）"）

**URLs**
```
homepage: ⭐ https://www.example.com
search:   ⭐ https://www.example.com/search?q={query}（可选，但强烈建议）
```

**元数据**（可选但推荐）
- `requires_login`: true/false（需登录才能完成核心操作吗？）
- `search_is_spa`: true/false（搜索页是否需要等 JS 渲染？）
- `search_url_reliable`: true/false（search URL 是否直接返回结果？）

**备注**
- ⭐ 关键选择器（搜索结果/列表/按钮的 CSS selector）
- ⚠️ 已知坑（Cookie 弹窗、SPA 加载、登录墙、重定向等）
- 验证脚本（可选，用 evaluate_script 检查状态）
```

### 填写要点

| 字段 | 为什么重要 | 怎么填 |
|---|---|---|
| `capabilities` | 决定平台何时被路由到 | 用现有标签（见下方速查表），没有就新造一个简短的 |
| `load_hint` | 决定导航后等多久再操作 | 静态页填 `static`，SPA/JS 渲染页填 `spa` |
| `fallback` | 平台失败时去哪 | 选功能最接近的平台，兜底到 `baidu` |
| `search` URL | 让 agent 直接跳搜索页，少 2-3 轮 | 从浏览器地址栏复制搜索页 URL，把关键词替换成 `{query}` |
| 选择器 | 让 `search_and_extract_links` 精准提取 | 用浏览器开发者工具（F12）找列表/链接的 CSS selector |
| `requires_login` | 避免 agent 卡在登录墙 | 核心操作需要登录就填 `true` |

### 示例：注册豆瓣电影

```
### douban_movie — 豆瓣电影

**capabilities**: `movie_info` `rating` `review`
**load_hint**: `static`（等 1 秒）
**fallback**: `baidu`

**URLs**
```
homepage: https://movie.douban.com
search:   https://movie.douban.com/subject_search?search_text={query}
top250:   https://movie.douban.com/top250
```

**元数据**
- `requires_login`: false
- `search_is_spa`: false
- `search_url_reliable`: true

**备注**
- 搜索结果选择器：`.item-root a.title`
- 评分选择器：`.rating_num`
- ⚠️ 反爬较严，频繁请求可能返回 403，间隔 2 秒再重试
```

### 验证新平台是否可用

注册后用这个流程测试：
1. `navigate_page` 到 `search` URL
2. `wait_for_element` 等搜索结果容器
3. `search_and_extract_links` 用你写的选择器提取
4. 确认返回非空列表 → 注册成功

---

## capabilities 标签速查

| 类别 | 标签 |
|------|------|
| 体育直播 | `world_cup` `sports_live` `nba` `cba` `esports` `sports_replay` `ufc` `football` |
| 长视频/内容 | `tv_series` `movie` `variety_show` `video` `anime` `short_video` `live_stream` `educational` `documentary` |
| 音乐 | `music` `podcast` `playlist` `album` `artist` `radio` `music_video` |
| 外卖/本地 | `food_delivery` `local_life` `hotel` `group_buy` `grocery` |
| 出行 | `flight_booking` `hotel_booking` `train_ticket` `travel_package` `scenic_ticket` |
| 票务 | `concert_ticket` `theater_ticket` `sports_ticket` `exhibition_ticket` `movie_ticket_alt` |
| 购物 | `shopping` `group_buy` `sneaker` `streetwear` `second_hand` `electronics` `appliance` |
| 内容/社区 | `qa` `article` `knowledge` `lifestyle` `review` `recipe` `travel_guide` |
| 开发/技术 | `code_search` `repo_browse` `developer` `documentation` `troubleshooting` `trending` |
| 工具/搜索 | `general_search` `translation` `calculator` `weather` `express_tracking` `news` `tutorial` |
