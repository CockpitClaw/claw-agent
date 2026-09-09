---
name: jd-shopping
description: >
  当用户要求购买商品、买东西、网购时，默认走京东，直接触发本 skill，不要询问平台。
  明确触发：用户说"帮我买 X"、"买双 X"、"买个 X"、"在京东买"、"京东搜一下"等。
  仅当用户明确指定其他平台（如淘宝、拼多多）时才不用本 skill。
  Covers the full flow — search → open product → buy-now → size selection → payment popup.
user-invocable: false
---

# JD Shopping on browser (via browser MCP)

This skill is your **only** correct playbook for buying anything on 京东 through the `browser` MCP. Follow it step-by-step. Do NOT invent alternative approaches without checking here first.

## 环境（mac 浏览器 / 车机 WebView 通用）

- **pageId**：先调 `list_pages` 拿浏览器页 id 并在全文使用该值。桌面 Chrome 恒为 `1000`；车机内嵌 WebView 为 `list_pages` 返回的 id（通常 `1`）。整个购买流程都在这个 pageId 上进行。
- **仅车机**（browser MCP 无 `set_user_agent` 工具，mac 跳过本步）：首次 `navigate_page` 前调 `set_user_agent(mode="mac")` 设桌面 UA，让京东返回 PC 版 DOM（`item.jd.com` / 结算 iframe），下文的 PC 选择器才能原样可用。mac 上天然桌面 UA，无需此步。
- **CSS 像素 == 视口像素**，`physScale=1`。click 坐标直接用 `getBoundingClientRect()` 返回的 CSS px，不需要任何缩放。
- **禁止传 `displayedWidth` 参数**给 click——本 MCP 的 click 不支持它，传了会被忽略。
- **跨域 iframe**（京东结算弹窗 `https://pc-settlement-lite-pro.pf.jd.com`）必须用 `evaluate_script_in_frame`，主 frame 的 `evaluate_script` 看不到它。
- **Never** use `web_fetch` — all page data is obtained via `evaluate_script` on the already-loaded page.
- **Never** use `take_snapshot` on JD result/detail pages — the DOM is too big and useless. 截图只在必要时用一次。

## 工具调用方式

所有调用直接用 browser MCP 原生工具（picoclaw 已注册为 MCP client，不需要 exec/curl）：

```
navigate_page(pageId=1000, url="https://search.jd.com/Search?keyword=...")
evaluate_script(pageId=1000, script="<JS>")
evaluate_script_in_frame(pageId=1000, frameUrl="https://pc-settlement-lite-pro.pf.jd.com", script="<JS>")
click(pageId=1000, target="<ref 或 CSS selector>")  // 或 click(pageId=1000, x=<x>, y=<y>)
take_screenshot(pageId=1000)
```

## The canonical steps

Do these in order. Print a one-line status update between steps so the user can follow along.

### 0. 检查本次 session 是否已有该商品的详情页 URL

**如果用户说"再买一件/同款/一样的/相同的"**，扫描 session 对话历史，找到所有 `item.jd.com/<sku>.html` 出现记录，按**实际商品品类**过滤（球衣找球衣、鞋找鞋），取**最近一次**该品类的 SKU。找到后直接 navigate，跳过 step 1-2，进 step 3：

```
navigate_page(pageId=1000, url="https://item.jd.com/<sku>.html")
```

⛔ 找到 SKU 后直接 navigate，禁止向用户询问尺码或商品确认——同款意味着尺码也沿用历史选择。找不到对应品类 SKU → 进 step 1 搜索。

### 1. Search

**搜索词构造规则（执行前必读）**：

- **服装类**（球衣/T恤/衣服等）：若用户提供了身高，按下表推算尺码并追加到搜索关键词末尾
- ⛔⛔ **鞋类：身高/体重信息对鞋码推算无效，禁止根据身高体重推算鞋码**。鞋类搜索词里不加尺码，等 step 3.5 再询问用户
- 服装类身高→尺码推算表（此表优先于一切经验判断，禁止自行推算）：

| 身高 | 尺码 |
|------|------|
| ≤170cm | M |
| 171–175cm | L |
| 176–180cm | XL |
| ≥181cm | **2XL** |

直接 navigate 搜索 URL，不要在搜索框里 type：

```
navigate_page(pageId=1000, url="https://search.jd.com/Search?keyword=<url-encoded query>&enc=utf-8")
```

导航后直接进 step 2，不加任何 wait_for，也不用 evaluate_script 检查 URL。⛔ 禁止在这一步截图或检查 URL。

### 2. Pick a product

**候选商品队列（信息足够时的标准路径）**

信息足够时（品牌+款型明确，或用户提供了身高/尺码），**一次性提取前 5 个非广告 SKU 存为候选队列**，按顺序逐个进入详情页检查。发现缺货/不符直接取队列下一个，不重新搜索。

```
evaluate_script(pageId=1000, script=`(function() {
  var cards = document.querySelectorAll("div[data-sku]");
  var vh = window.innerHeight;
  var out = [];
  for (var i = 0; i < cards.length; i++) {
    var el = cards[i];
    var r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0 || r.top >= vh || r.bottom <= 0) continue;
    var txt = (el.innerText || "").trim();
    if (txt.slice(0, 4).indexOf("广告") !== -1) continue;
    out.push({sku: el.getAttribute("data-sku"), title: txt.slice(0, 40)});
    if (out.length >= 5) break;
  }
  return JSON.stringify(out);
})()`)
```

- 队列非空 → navigate 到 `out[0].sku`，进 step 3 检查尺码
- step 3 发现目标尺码缺货 → 取 `out[1].sku`，不重新搜索，依此类推
- 整个队列耗尽无货 → 重新搜索（关键词末尾追加尺码重试一次）
- 返回空数组 → 搜索页未加载完，等 3 秒后重试一次，仍空则告知用户

⛔ **禁止**在信息足够时展示列表等待用户确认——会被 global_yes 规则劫持。

**信息不足时**（只说"买双鞋"，无品牌/款式），展示列表后等用户选：
- 用户说"第 N 个" ≡ `out[N-1]`，不允许重排编号
- ⛔ 用户回复选择后，直接用当次 DOM 反查里 `out[N-1].sku` navigate，禁止重新搜索

**跳转到详情页**：

```
navigate_page(pageId=1000, url="https://item.jd.com/<sku>.html")
```

⚠️ **绝对禁止凭模型记忆直接 navigate 到 `item.jd.com/<sku>.html`**——SKU 必须来自本次 DOM 反查。JD may substitute SKUs (合并 SKU, 广告位跳转)，URL 落地的 SKU 可能和卡片 `data-sku` 不同，这是 JD 业务逻辑不是 bug。

### 3. On the product detail page: 先选尺码，再点"立即购买"

⚠️⚠️⚠️ **关键：尺码在详情页 DOM 上选，不是点完立即购买后在结算页选！** trade.jd.hk 订单确认页**没有尺码元素**（只有收货地址/订单信息），尺码必须在 click 立即购买之前，在 `item.jd.com` / `npcitem.jd.hk` 详情页上选好。之前因点完立即购买再找尺码（在 trade.jd.hk 上找不到）导致反复跳商品页。

#### Step 3a — 在详情页选尺码（用 helper，不要自己写 JS）

```
jd_get_sizes(pageId=1000)
```

返回 `{found, sizes:[{text,x,y,selected,outOfStock}]}`。helper 内部已处理 `specification-item-sku` selector + `--sel` 选中态 + 3s 轮询等异步渲染，agent 不要自己写脚本。

**处理结果**：

- **服装类**：用户提供了身高 → 按下表推算尺码，调 `jd_select_size` 选推算码
- **鞋类 且 用户已指定尺码** → 调 `jd_select_size` 选用户指定码
- **鞋类 且 用户未指定尺码** → 展示有货尺码（`outOfStock:false` 的 text 列表），⛔ HARD STOP 问"您穿几码？"
- **服装类 且 用户未提供身高** → 直接进 Step 3b（不选尺码，详情页默认即可）

选尺码：
```
jd_select_size(pageId=1000, size="<目标尺码如 43>")
```
返回 `{ok:true,selected}` 或 `{ok:false,error:"click_no_effect"}`。helper 内部已 DOM click + 坐标兜底 + 验证选中态。

- `ok:true` → 进 Step 3b
- `ok:false, error:"size_not_in_list"` → 尺码不存在，navigate 候选队列下一个 SKU
- `ok:false, error:"click_no_effect"` → take_snapshot 看 DOM 再处理

#### 服装类尺码推算（根据身高严格对照，禁止自行判断）

⛔⛔ **此推算表仅适用于服装类。鞋类绝对禁止用身高/体重推算鞋码，必须问用户。**

| 身高 | 尺码 |
|------|------|
| ≤170cm | M |
| 171–175cm | L |
| 176–180cm | XL |
| ≥181cm | **2XL** |

185cm → **2XL**，禁止推算为 XL。

#### Step 3b — 找"立即购买"按钮坐标并 click

尺码选好后，找按钮坐标：
```
evaluate_script(pageId=1000, script=`(function(){var nodes=document.querySelectorAll("a,button,div,span");for(var i=0;i<nodes.length;i++){var el=nodes[i];var t=(el.innerText||"").trim();if(t!=="立即购买"&&t!=="立即抢购")continue;var r=el.getBoundingClientRect();if(r.width>30&&r.width<500&&r.height>20&&r.height<100)return JSON.stringify({x:Math.round(r.left+r.width/2),y:Math.round(r.top+r.height/2)})}return JSON.stringify({x:0,y:0,error:"button_not_found"})})()`)
```
```
click(pageId=1000, x=<x>, y=<y>)
```

- `button_not_found` → 等 2 秒重试一次，仍失败告知用户停止

#### Step 3c — 判断 click 后落地页

click 立即购买后，用 `evaluate_script` 取一次 `location.href` 判断落地：

- URL 含 `trade.jd.com` / `trade.jd.hk` → **订单确认页**（尺码已在 Step 3a 选好带过来了），直接进 Step 4（确认支付）
- URL 含 `npcitem.jd.hk` 或仍是 `item.jd.com`（SKU 变了）→ **二级详情页**（京东国际商品常见）：这个页面也要先选尺码——回到 Step 3a 重新 `jd_get_sizes` + `jd_select_size`，然后再找立即购买 click 一次。第二次 click 后通常进 trade.jd.hk 订单页
- URL 含 `passport` / `login` → 登录墙，HARD STOP 告知用户
- **URL 没变（仍含 item.jd.com）且不是二级详情页** → **弹出了 iframe 结算弹窗**！普通京东商品点"立即购买"不跳页，而是在当前页叠一个跨域 iframe（`https://pc-settlement-lite-pro.pf.jd.com`）显示订单确认。
  - ⛔ **不要**在主 frame 反复查 URL / take_screenshot / take_snapshot——弹窗内容在 iframe 里，主 frame 看不到
  - ⛔ **不要**在 iframe 找不到按钮后反复换方法（截图/snapshot/查登录墙）——这些在 iframe 场景下都是浪费，iframe 里的内容只能用 `evaluate_script_in_frame`
  - **第一次用 `evaluate_script_in_frame` 在 iframe 里查按钮**：
    ```
    evaluate_script_in_frame(pageId=1000, frameUrl="https://pc-settlement-lite-pro.pf.jd.com", script="(function(){var btnTexts=['立即支付','去付款','确认支付','提交订单','去结算','结算'];var all=document.querySelectorAll('a,button,div,span');for(var i=0;i<all.length;i++){var el=all[i];var t=(el.innerText||'').trim();if(btnTexts.indexOf(t)===-1)continue;var r=el.getBoundingClientRect();if(r.width>60&&r.width<400&&r.height>20)return JSON.stringify({txt:t,x:Math.round(r.left+r.width/2),y:Math.round(r.top+r.height/2)})}return JSON.stringify({x:0,y:0,error:'btn_not_found'})})()")
    ```
  - 找到按钮 → ⛔ HARD STOP 澄清后等用户确认"支付"，回复后才 `click(pageId=1000, x=<x>, y=<y>)`（坐标是 iframe 内的 CSS px，click 工具会自动换算到主 frame）
  - `btn_not_found` → iframe 可能还在加载。**fallback：在 iframe 里读所有可见文本 + 找所有可点击元素**（只调 1 次，不要反复重试）：
    ```
    evaluate_script_in_frame(pageId=1000, frameUrl="https://pc-settlement-lite-pro.pf.jd.com", script="(function(){var btns=document.querySelectorAll('a,button,[role=button],.btn');var out=[];for(var i=0;i<btns.length;i++){var el=btns[i];var r=el.getBoundingClientRect();if(r.width>40&&r.height>15&&r.width<500)out.push({txt:(el.innerText||'').trim().slice(0,20),tag:el.tagName,x:Math.round(r.left+r.width/2),y:Math.round(r.top+r.height/2)})}return JSON.stringify({count:out.length,buttons:out.slice(0,15)})})()")
    ```
    从返回的 buttons 数组里找文字含"支付/付款/提交/结算/确认"的，取它的坐标 click。若返回 count=0 或全是不相关按钮 → HARD STOP 告知用户"iframe 结算弹窗未加载出按钮，请手动点击"

⚠️ **不要在 trade.jd.hk 上查尺码/选尺码**——那里没有尺码元素，尺码必须在详情页（Step 3a）选好。

⛔ click 后**禁止** take_screenshot、take_snapshot（弹窗/订单页 DOM 大且无用）。允许一次 `evaluate_script` 取 `location.href` 判断落地；若是 iframe 弹窗形态，改用 `evaluate_script_in_frame`。

### 3.5 流程中换尺码（改码）

用户说"换成 X 码 / 改成 X 码 / 改成 X"时，⛔ **先判断当前状态再动手**，用一次 evaluate_script 判断：

```
evaluate_script(pageId=1000, script="JSON.stringify({url:location.href, hasSettlement: !!document.querySelector('iframe[src*=\"pf.jd.com\"], iframe[src*=\"settlement\"]')})")
```

分三种情况处理：

**A. 仍在详情页、且无结算弹窗**（url 含 item.jd/npcitem 且 `hasSettlement=false`）：
直接 `jd_select_size(pageId=1000, size="X")` 换码，然后回 Step 3b 重新点"立即购买"。⛔ 禁止重新 navigate / 重新搜索。

**B. 详情页上已叠 iframe 结算弹窗**（url 仍 item.jd.com 且 `hasSettlement=true`）：
旧弹窗里是旧尺码，⛔ 不能直接在弹窗里改（或改了也会残留旧码误导）。必须先**重开干净状态**：
1. 关闭旧弹窗：优先点弹窗右上角关闭按钮，找不到就 reload 详情页：
   ```
   navigate_page(pageId=1000, url="<当前 item.jd.com 的 SKU 详情页 URL>")
   ```
2. 回到 Step 3a：`jd_get_sizes` → `jd_select_size(pageId=1000, size="X")`
3. Step 3b 重新点"立即购买"，弹出新结算弹窗
4. ⛔ **必须验证**（见下方"改码后验证"）

**C. 已进 trade.jd 订单页**（尺码已固化进订单）：
同 B：navigate 回原 SKU 详情页 → Step 3a 重选 → Step 3b 重新点立即购买 → 验证。

#### 改码后验证（B/C 必须做，A 也要顺带确认）

改码并重新点立即购买后，用 `evaluate_script_in_frame` 读结算 iframe 里的尺码，⛔ **确认与目标码一致**，否则重复上面步骤（最多再 1 次），仍不一致则 HARD STOP 告知用户：

```
evaluate_script_in_frame(pageId=1000, frameUrl="https://pc-settlement-lite-pro.pf.jd.com", script="(function(){var t=document.body.innerText||'';var i=t.indexOf('尺码');return t.slice(i,i+60).replace(/\\s+/g,' ');})()")
```

⛔⛔ **铁律：只改详情页的选择器不算完成**。旧结算弹窗会残留旧码误导用户——改码的完成标志是**新结算弹窗里的尺码 = 目标码**。

### 4. 订单确认页 → 点提交订单 → 支付页 → 停下交用户付款

⚠️⚠️⚠️ **进入订单确认页（URL 含 trade.jd）后，第一件事必须是调 `jd_find_pay_button(pageId=1000)`，⛔ 严禁自己写 evaluate_script 查按钮、严禁 scroll_down/scroll_up**。

`jd_find_pay_button` 自动处理三种形态 + 轮询等渲染 8 秒，agent 不需要判断：
- `form: "single_page"` → 京东国际版，直接有"立即支付"按钮 → 澄清 + click
- `form: "two_phase_submit"` → 普通京东，先"提交订单" → click → 跳支付页 → 再调 `jd_find_pay_button` 找"立即支付"
- `form: "iframe"` → item.jd.com 上的 iframe 结算弹窗 → 澄清 + click（click 工具会自动换算 iframe 坐标）
- `error: "not_found"` → 8 秒内没找到按钮，HARD STOP 告知用户

#### Step 4a — 调 `jd_find_pay_button`（⛔ 禁止自己写 JS）

```
jd_find_pay_button(pageId=1000)
```

返回 `{form, btn:{txt,x,y}}`：
- **single_page 或 iframe 形态** → ⛔ HARD STOP 澄清后等用户确认，回复"支付"才 `click(pageId=1000, x=<x>, y=<y>)`
- **two_phase_submit 形态** → ⛔ HARD STOP 澄清后等用户确认"提交"，回复"提交"才 `click(pageId=1000, x=<x>, y=<y>)`。click 后用 `evaluate_script` 取 `location.href`，若含 `payc.jd` / `cashier` / `pay` → 提交成功，**再调一次 `jd_find_pay_button`** 找"立即支付"按钮，再次澄清 + click
- **error: not_found** → HARD STOP 告知用户"8 秒内未找到支付按钮，可能需要登录或页面异常"

⚠️ **不要在 trade.jd.hk 上查尺码/选尺码**——那里没有尺码元素，尺码必须在详情页（Step 3a）选好。

⛔ click 后**禁止** take_screenshot、take_snapshot（弹窗/订单页 DOM 大且无用）。判断落地只用 `evaluate_script` 取 `location.href` 或 `jd_find_pay_button`。

#### Step 4b — 停在输密码/扫码页


click 立即支付后会弹出支付二维码或要求输入支付密码。**到此停止**——扫码/输密码必须用户用手机/自己完成，agent 无法代替。告知用户：
```
已选好 <尺码>，已点立即支付。请用微信/支付宝扫码，或输入支付密码完成付款。这一步必须你亲自完成。
```

⛔ **禁止调 `load_image`/`take_screenshot`** 读二维码图片（环境未配置会报 `media store not configured`，且二维码本就是要用户手机扫）。直接文字告知即可。

遇到登录墙/验证码时暂停并告知用户，不代替用户登录。
