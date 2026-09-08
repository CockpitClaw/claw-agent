---
name: taobao-shopping
description: >
  当用户明确要求在淘宝购买商品时触发本 skill。
  明确触发：用户说"在淘宝买 X"、"淘宝搜一下 X"、"帮我淘宝购买 X"、"去淘宝买"等。
  ⛔ 用户未指定平台时默认走 jd-shopping，不走本 skill。
user-invocable: false
---

# Taobao Shopping on browser (via browser MCP)

This skill is your playbook for buying on **淘宝** through the `browser` MCP.

⚠️ **淘宝反爬严格**：未登录时搜索会被重定向到登录页，或触发 `bot_check` 滑块验证。遇到登录墙/验证码时**暂停并告知用户**，不代替用户登录。建议用户先在 Chrome 里手动登录淘宝，再发购买 query。

## 环境

- **pageId=1000**，整个流程都在这个页面。
- CSS px == viewport px，`physScale=1`，click 坐标直接用 `getBoundingClientRect()` 返回值。
- 淘宝商品详情页的 SKU 面板是**同源**的（不是跨域 iframe），用 `evaluate_script` 即可，不需要 `evaluate_script_in_frame`。
- 支付宝支付页是跨域 iframe（`https://cashier.alipay.com`），如需在支付页操作用 `evaluate_script_in_frame`。但**走到支付页即停止**，不代替用户付款。

## 工具调用方式

直接用 browser MCP 原生工具：

```
navigate_page(pageId=1000, url="...")
evaluate_script(pageId=1000, script="<JS>")
click(pageId=1000, target="<ref 或 CSS selector>")  // 或 click(pageId=1000, x=<x>, y=<y>)
take_screenshot(pageId=1000)
```

## The canonical steps

### Step 1. Search

直接 navigate 搜索 URL（淘宝搜索结果页）：

```
navigate_page(pageId=1000, url="https://s.taobao.com/search?q=<url-encoded keyword>&imgfile=&js=1&stats_click=search_radio_all%3A1&initiative_id=staobaoz_2024")
```

导航后用 `evaluate_script` 提取商品列表：

```
evaluate_script(pageId=1000, script=`(function(){
  var links = document.querySelectorAll('a[href*="item.taobao.com"], a[href*="detail.tmall.com"]');
  var out = [];
  for (var i = 0; i < links.length && out.length < 5; i++) {
    var lk = links[i];
    var href = lk.href || "";
    var m = href.match(/[?&]id=(\d+)/) || href.match(/item\.htm\?id=(\d+)/);
    if (!m) continue;
    var container = lk.closest("[class*=item],[class=card],[class*=product]") || lk;
    var titleEl = container.querySelector("[class*=title],[class=Title],h3,h2") || lk;
    var priceEl = container.querySelector("[class*=price],[class*=Price],[class*=money]");
    var r = lk.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) continue;
    out.push({
      index: out.length,
      id: m[1],
      title: (titleEl.innerText || lk.innerText || "").trim().slice(0, 60),
      price: priceEl ? priceEl.innerText.trim() : "",
      itemType: href.indexOf("tmall.com") !== -1 ? "tmall" : "taobao"
    });
  }
  return JSON.stringify({ok: out.length > 0, count: out.length, items: out});
})()`)
```

- `ok:true` → 进 Step 2
- `ok:false` → 可能被重定向到登录页。用 `evaluate_script` 检查 `location.href` 是否含 `login`，是则告知用户"淘宝需要登录，请在 Chrome 里登录后重试"。
- 关键词规则：直接用用户描述的商品名，不附加尺码。

### Step 2. Pick a product

信息足够时（品牌/款式明确）自动选 `items[0]`，直接 navigate：
```
navigate_page(pageId=1000, url="https://item.taobao.com/item.htm?id=<id>")
```

信息不足时展示前 5 个商品等用户选。用户说"第 N 个" → `items[N-1].id`。

### Step 3. 点购买按钮（立即购买/领券购买）

在详情页找购买按钮。淘宝按钮文案可能是「立即购买」「立即拥有」「领券购买」等：

```
evaluate_script(pageId=1000, script=`(function(){
  var allEls = document.querySelectorAll("a,button,div,span");
  var btnTexts = ["立即购买","立即拥有","领券购买","加入购物车"];
  for (var i = 0; i < allEls.length; i++) {
    var el = allEls[i];
    var t = (el.innerText || "").trim();
    if (btnTexts.indexOf(t) === -1) continue;
    var r = el.getBoundingClientRect();
    if (r.width < 30 || r.height < 15) continue;
    return JSON.stringify({x: Math.round(r.left + r.width/2), y: Math.round(r.top + r.height/2), text: t});
  }
  return JSON.stringify({x:0, y:0, error:"button_not_found"});
})()`)
```

拿到坐标后 click：
```
click(pageId=1000, x=<x>, y=<y>)
```

点击后会弹出 SKU 面板（选尺码/颜色）。进 Step 4。

### Step 4. 选 SKU（尺码/颜色）

SKU 面板的规格项 class 含 `valueItem` 或 `value-item`，面板 class 含 `sku` 或 `prop`：

```
evaluate_script(pageId=1000, script=`(function(){
  var panels = document.querySelectorAll('[class*=sku],[class*=Sku],[class*=prop],[class*=Prop]');
  var out = [];
  for (var p = 0; p < panels.length; p++) {
    var panel = panels[p];
    var labels = panel.querySelectorAll('[class*=label],[class*=Label],[class*=propName],[class*=prop-name]');
    var items = panel.querySelectorAll('[class*=valueItem--],[class*=value-item]');
    if (items.length === 0) continue;
    var labelText = labels.length > 0 ? (labels[0].innerText || "").trim() : ("规格" + (out.length+1));
    var opts = [];
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      var r = it.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0) continue;
      var cls = it.className || "";
      opts.push({
        text: (it.innerText || "").trim(),
        x: Math.round(r.left + r.width/2),
        y: Math.round(r.top + r.height/2),
        selected: cls.indexOf("active") !== -1 || cls.indexOf("selected") !== -1,
        disabled: cls.indexOf("disable") !== -1
      });
    }
    if (opts.length > 0) out.push({label: labelText, options: opts});
  }
  return JSON.stringify({found: out.length > 0, panels: out});
})()`)
```

- 用户已指定尺码 → 找到对应 option 的 `(x,y)`，click 选它
- 用户未指定 → 展示可选项，问用户"请问要哪个尺码/颜色？"，HARD STOP
- 全选完后，SKU 面板底部会出现「确认」按钮，用 Step 3 的 JS 找「确认」按钮并 click

### Step 5. 确认订单 + 停在支付页

选完 SKU 后会跳到订单确认页（`buy.tmall.com` 或 `buy.taobao.com`）。在订单页找「提交订单」/「立即支付」按钮并 click：

```
evaluate_script(pageId=1000, script=`(function(){
  var allEls = document.querySelectorAll("a,button,div,span");
  var btnTexts = ["提交订单","立即支付","确认订单","提交"];
  for (var i = 0; i < allEls.length; i++) {
    var el = allEls[i];
    var t = (el.innerText || "").trim();
    if (btnTexts.indexOf(t) === -1) continue;
    var r = el.getBoundingClientRect();
    if (r.width < 60 || r.height < 20) continue;
    return JSON.stringify({x: Math.round(r.left + r.width/2), y: Math.round(r.top + r.height/2), text: t});
  }
  return JSON.stringify({x:0, y:0, error:"button_not_found"});
})()`)
```

click 后会跳到支付宝支付页。⛔ **走到支付页即停止**，告知用户：
```
订单已提交，支付宝支付页已打开。请在 Chrome 里自行完成支付。
```

不代替用户付款。遇到登录墙/验证码/滑块时暂停并告知用户。
