---
name: maoyan-movie
description: >
  当用户要求在猫眼买电影票、查电影、看场次时触发本 skill。
  明确触发：用户说"在猫眼买电影票"、"猫眼搜一下 X 电影"、"帮我买今晚的 X 电影票"等。
  Covers the full flow: home → film detail → cinema list → showtimes → seat map → order-confirm.
user-invocable: false
---

# 猫眼购票 on browser (via browser MCP)

按步骤执行。**每一步给出确切的工具调用。不许发明步骤外的工具。**

## 工具

**maoyan 高级 helper（优先用，已封装 DOM 探测+时机+弹窗处理）：**
`maoyan_get_cinemas` / `maoyan_get_shows` / `maoyan_click_show` / `maoyan_query_seats` / `maoyan_select_seat` / `maoyan_dismiss_modal`

**通用：**
`navigate_page` / `dom_click` / `click` / `evaluate_script` / `take_snapshot`

⛔ **以下工具不存在，调用必然报错：**
`screenshot` / `get_seats` / `select_best_seat` / `get_page_content` / `take_screenshot`(全程不用截图，用 take_snapshot 看 DOM)

## 环境

- **pageId=1000**，整个流程都在这个页面（除非 0 tab 时先 new_page）。
- CSS px == viewport px，`physScale=1`（helper 内部已处理）。
- ⛔ **禁止用 `take_screenshot`**，全程用 `take_snapshot` 看 DOM tree（2-6ms，带 [ref=eN] 可直接 click）。

## 前置：确保有可用 tab

调 `list_pages`。返回 `{"pages":[]}` → 调 `new_page(url="https://www.maoyan.com/")`，用返回 pageId。否则用已存在 pageId（通常 1000）。

## target="_blank" 处理

猫眼链接普遍带 `target="_blank"`。helper 内部的抓取脚本已把 `a[target="_blank"]` 改写成 `_self`。**不要单独跑防护 evaluate**（navigate 后第一次 evaluate 会卡 SPA context 切换）。

---

## STEP 1 — 打开猫眼

```
navigate_page(pageId=1000, url="https://www.maoyan.com/")
```

→ STEP 2b（有片名）或 STEP 2（无片名）

## STEP 2b — 有片名：搜 filmId

```
navigate_page(pageId=1000, url="https://www.maoyan.com/#search?query=<URL编码片名>")
```

等 3 秒，`evaluate_script` 提取 filmId：

```
evaluate_script(pageId=1000, script=`(function(){var links=document.querySelectorAll("a[href*='/films/']");var seen=new Set();var out=[];for(var i=0;i<links.length;i++){var m=links[i].href.match(/\/films\/(\d+)/);if(!m||seen.has(m[1]))continue;seen.add(m[1]);var p=links[i].closest("[class*='movie'],[class*='item'],[class*='card']")||links[i];var img=p.querySelector("img[alt]");var title=img&&img.alt?img.alt:(links[i].innerText||"").trim();out.push({filmId:m[1],title:title.slice(0,30),index:out.length});if(out.length>=5)break}return JSON.stringify({found:out.length>0,results:out})})()`)
```

- `found:true` 1 个 → 记住 filmId，进 STEP 3
- `found:true` 多个 → 展示等用户选 → `results[N-1].filmId`，进 STEP 3
- `found:false` → HARD STOP 告知用户

## STEP 2 — 无片名：展示热映列表

```
evaluate_script(pageId=1000, script=`(function(){var out=[],seen=new Set();var cards=document.querySelectorAll(".movie-list .movie-item,[class*=movie-item]");for(var i=0;i<cards.length;i++){var el=cards[i];var a=el.querySelector("a[href*='/films/']");if(!a)continue;var m=a.href.match(/\/films\/(\d+)/);if(!m||seen.has(m[1]))continue;seen.add(m[1]);var img=el.querySelector("img[alt]");var title=img&&img.alt?img.alt:"";var sc=el.querySelector("[class*=score],[class*=grade]");out.push({index:out.length,filmId:m[1],title:(title||el.innerText||"").trim().slice(0,30),score:sc?(sc.innerText||"").trim().slice(0,8):""});if(out.length>=10)break}return JSON.stringify(out)})()`)
```

展示列表，保留 index，⛔ HARD STOP 等用户选。用户选后记住 filmId → STEP 3。

## STEP 3 — 电影详情页 + 进影院列表页

⚠️ **不要 click "特惠购票"按钮**——PC Chrome 上 click(425,411) 坐标会点到 `target="_blank"` 改写后的错误链接（点错电影/影院），页面不跳转或跳错。**直接 navigate 直跳影院列表页**：

```
navigate_page(pageId=1000, url="https://www.maoyan.com/cinemas?movieId=<filmId>")
```

→ STEP 4（URL 变成 `/cinemas?movieId=<filmId>`）

## STEP 4 — 影院列表

先 `scroll_down` 让影院列表在 Chrome 视口里可见（用户能看到列表再选），然后调 helper 抓数据：

```
scroll_down(pageId=1000)
maoyan_get_cinemas(pageId=1000)
```

返回 `{cinemas:[{index,cinemaId,name,address,price,distance,distKm,x,y}]}`。展示给用户，⛔ HARD STOP 等回复。

用户选后**用 navigate 直跳影院详情页**（不要 click 坐标——影院链接是 JS 弹层，click 后页面不跳转）：

```
navigate_page(pageId=1000, url="https://www.maoyan.com/cinema/<cinemaId>?movieId=<filmId>")
```

→ STEP 5

## STEP 5 — 场次列表

navigate 到影院详情页后，先 `scroll_down` 让场次表格在视口里可见，再调 helper（helper 内部不 scroll——scroll 会破坏 xseats 链接的 parent 链导致找不到时间）：

```
scroll_down(pageId=1000)
maoyan_get_shows(pageId=1000, date="today", movieId="<filmId>")
```

返回 `{count, shows:[{time,timeMin,passed,href,x,y}], targetDate, movieId}`。展示场次（时间），⛔ HARD STOP 等用户选。

用户选后用 `maoyan_click_show` 进座位图（helper 内部用 window.location.href 跳转，保留 session，等 4s 验证 /xseats/）：

```
maoyan_click_show(pageId=1000, time="<用户选的 time 如 19:00>", movieId="<filmId>", date="today")
```

- `ok:true` → STEP 6
- `ok:false, error:"xseats_not_reached"` → 用 `evaluate_script` 直接 `window.location.href="<show.href>"`，等 3 秒验证 URL 到 /xseats/，到了进 STEP 6
- `ok:false, error:"redirected_to_login"` → HARD STOP 告知用户需登录

## STEP 6 — 关闭入场须知弹窗（⛔ 必做，不能跳过）

每次进 xseats 页，猫眼都会弹"入场须知"弹窗，挡住座位和确认按钮。**第一步必须关弹窗**：

```
maoyan_dismiss_modal(pageId=1000)
```

- `modal:"none"` → 无弹窗，进 STEP 7
- `modal:"closed"` → 弹窗已关，进 STEP 7
- `ok:false, error:"isolated_seat"` → 极少情况，告知用户座位问题

## STEP 7 — 询问座位偏好 + 一键选座

先查座位图概况：

```
maoyan_query_seats(pageId=1000)
```

返回 `{availableCount, rowCount, rows:[...], orderBtn, centerX}`。⛔ HARD STOP 告知用户：

```
选座页已加载。共 <rowCount> 排，约 <availableCount> 个空位。
你想坐哪个区域？前排/中排/后排 × 左侧/中间/右侧（"正中间"=中排×居中）
```

用户回复后翻译成 rowHint/colHint：

| 用户说 | rowHint | colHint |
|---|---|---|
| 前排 | front | — |
| 中排 | middle | — |
| 后排 | back | — |
| 左侧 | — | left |
| 中间/居中（仅列） | — | center |
| **正中间/正中央** | **middle** | **center** |
| 右侧 | — | right |
| 随便/都行 | middle | center |

一键选座（helper 内部：选座→关弹窗→点确认选座→等 /order/confirm）：

```
maoyan_select_seat(pageId=1000, rowHint="<rowHint>", colHint="<colHint>")
```

返回 `{ok:true, selectedNow, price, orderBtn, chosenRowIdx, confirmed:{ok,orderUrl}}`。

- `confirmed.ok:true` → 进 STEP 8
- `confirmed.ok:false` 且有 `orderBtn` → `click(pageId=1000, x=orderBtn.x, y=orderBtn.y)`，等 3 秒检查 URL 到 /order/confirm/
- `ok:false, error:"no_selectable_seats"` → 告知用户满座
- `ok:false, error:"click_no_effect"` → take_snapshot 看 DOM 再处理

## STEP 8 — 订单确认页

```
evaluate_script(pageId=1000, script=`(function(){var b=document.body.innerText||"";var p=b.match(/实际支付\\s*[：:]\\s*([\\d.]+)/);var t=b.match(/(\\d+月\\d+日\\s*\\d+:\\d+)/);var s=b.match(/(\\d+排\\d+座)/);var h=b.match(/([\\d号]+[^号]*厅)/);return JSON.stringify({price:p?p[1]:"",time:t?t[1]:"",seat:s?s[1]:"",hall:h?h[1]:""})})()`)
```

展示订单信息，⛔ HARD STOP 问是否支付：

```
✅ 订单已生成！
- 场次：<time> / <hall>
- 座位：<seat>
- 金额：¥<price>
需要我帮你点「确认支付」吗？
```

## STEP 8.1 — 用户确认支付后

用户说"支付"/"付款"/"确认支付"等，按顺序执行：

1. **点「确认支付」**（dom_click 文字优先，坐标兜底）：
```
dom_click(pageId=1000, text="确认支付")
```
返回 `{clicked:false}` 时用坐标兜底：先 `take_snapshot` 找按钮 ref，`click(target=eN)`。

2. **等 2 秒检测「订单已取消」弹窗**：
```
evaluate_script(pageId=1000, script=`(function(){var els=document.querySelectorAll("*");for(var i=0;i<els.length;i++){var t=(els[i].innerText||"");var r=els[i].getBoundingClientRect();if(r.width>0&&/该订单已取消|订单已取消|对不起/.test(t)&&t.length<60)return "CANCELLED"}return "OK"})()`)
```
- `CANCELLED` → 告知用户"订单已被取消，请重新下单"，停止
- `OK` → 继续

3. 点确认支付后会弹出支付二维码（微信/支付宝）。**到此停止**——扫码必须用户用手机完成。告知用户：
```
支付二维码已弹出，请用微信/支付宝扫码完成付款。这一步必须你亲自扫，我没法代替。
```

⛔ **禁止调用 `load_image` / `take_screenshot` / 任何图片读取工具试图识别或加载二维码图片**。这些工具在当前环境未配置（`media store not configured`）会报错，且二维码本就是要用户手机扫的，agent 不需要读取图片内容。直接用文字告知用户扫码即可。

遇到登录墙/验证码时暂停并告知用户。

## 死循环熔断

任何一步连续 3 次未找到目标元素，⛔ HARD STOP，`take_snapshot` 看当前 DOM 并告知用户"找不到 X，页面可能和预期不同"。禁止靠反复 evaluate/snapshot 硬试。
