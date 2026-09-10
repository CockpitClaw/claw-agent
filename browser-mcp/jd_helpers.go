package main

// JS scripts for JD size selection on product detail pages (item.jd.com /
// npcitem.jd.hk). Sizes render as `specification-item-sku` elements with
// `--sel` suffix when selected. On trade.jd.hk (order confirm page) there are
// NO size elements — sizes must be chosen on the detail page BEFORE clicking
// "立即购买".

// jdGetSizesScript returns the full selectable size list with coords +
// selected/outOfStock flags. Runs on the main frame of the detail page.
const jdGetSizesScript = `(function(){
  function isValidSize(txt){
    if(!txt)return false;
    txt=txt.trim();
    if(txt.length>6)return false;
    if(/[一-鿿\-]/.test(txt))return false;
    return /^(3[0-9]|4[0-9]|5[0-5])(?:\.5)?$/.test(txt)||/^(XS|S|M|L|XL|2XL|3XL|XXL|XXXL)$/i.test(txt);
  }
  function extractSizeToken(raw){
    if(!raw)return null;
    var token=raw.split(/\s/)[0].replace(/\.$/,"");
    return isValidSize(token)?token.toUpperCase():null;
  }
  var sizes=[];
  var seen=new Set();
  // Primary: JD detail page uses specification-item-sku (also covers
  // settlement iframe childItem/child-item if we ever run there).
  var items=document.querySelectorAll("[class*=specification-item-sku],[class*=childItem],[class*=child-item]");
  for(var i=0;i<items.length;i++){
    var e=items[i];var r=e.getBoundingClientRect();
    if(r.width<=0||r.height<=0||r.width>200||r.height>80)continue;
    var raw=(e.innerText||"").trim().replace(/\s*无货\s*$/,"").trim();
    var sz=extractSizeToken(raw);
    if(!sz)continue;
    if(seen.has(sz))continue;
    seen.add(sz);
    var cls=e.className||"";
    sizes.push({text:sz,x:Math.round(r.left+r.width/2),y:Math.round(r.top+r.height/2),
      selected:/--sel|-sel|active|selected|curr/i.test(cls),
      outOfStock:/outOfStock|disable|sold/i.test(cls)||(e.innerText||"").indexOf("无货")!==-1});
  }
  // Fallback: any leaf element whose innerText is a pure size token. Catches
  // JD international pages that use hash class names (e.g. item-5d3a50).
  if(sizes.length===0){
    var all=document.querySelectorAll("div,span,button,a,li");
    for(var j=0;j<all.length;j++){
      var el=all[j];
      if(el.children.length>0)continue;
      var t=(el.innerText||"").trim();
      if(!isValidSize(t))continue;
      if(seen.has(t.toUpperCase()))continue;
      var rr=el.getBoundingClientRect();
      if(rr.width<=0||rr.height<=0||rr.width>120)continue;
      seen.add(t.toUpperCase());
      var c=el.className||"";
      sizes.push({text:t.toUpperCase(),x:Math.round(rr.left+rr.width/2),y:Math.round(rr.top+rr.height/2),
        selected:/--sel|-sel|active|selected|curr/i.test(c),
        outOfStock:/outOfStock|disable|sold/i.test(c)||(el.innerText||"").indexOf("无货")!==-1});
    }
  }
  return JSON.stringify({found:sizes.length>0,sizes:sizes});
})()`

// jdSelectSizeScriptTemplate clicks the size element whose text matches the
// target. Uses DOM .click() (most reliable on JD SPA) then falls back to
// dispatching mouse events. Placeholder SELECTED_SIZE is substituted by Go.
const jdSelectSizeScriptTemplate = `(function(){
  var target=SELECTED_SIZE;
  var els=document.querySelectorAll("[class*=specification-item-sku],[class*=childItem],[class*=child-item]");
  for(var i=0;i<els.length;i++){
    var raw=(els[i].innerText||"").trim().replace(/\s*无货\s*$/,"").trim();
    var tok=raw.split(/\s/)[0].replace(/\.$/,"");
    if(tok.toUpperCase()===target){
      var r=els[i].getBoundingClientRect();
      var cx=r.left+r.width/2, cy=r.top+r.height/2;
      try{els[i].click();}catch(e){}
      ["mousedown","mouseup","click"].forEach(function(t){
        els[i].dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,clientX:cx,clientY:cy}));
      });
      return JSON.stringify({clicked:true,text:tok,x:Math.round(cx),y:Math.round(cy)});
    }
  }
  // Fallback: leaf elements with pure size text (JD intl hash classes)
  var all=document.querySelectorAll("div,span,button,a,li");
  for(var j=0;j<all.length;j++){
    var el=all[j];
    if(el.children.length>0)continue;
    var t=(el.innerText||"").trim();
    if(t.toUpperCase()===target){
      var rr=el.getBoundingClientRect();
      var bx=rr.left+rr.width/2, by=rr.top+rr.height/2;
      try{el.click();}catch(e){}
      ["mousedown","mouseup","click"].forEach(function(t2){
        el.dispatchEvent(new MouseEvent(t2,{bubbles:true,cancelable:true,clientX:bx,clientY:by}));
      });
      return JSON.stringify({clicked:true,text:t,x:Math.round(bx),y:Math.round(by)});
    }
  }
  return JSON.stringify({clicked:false});
})()`

// jdFindBuyNowScript finds the "立即购买" / "立即抢购" button coords on a
// product detail page.
const jdFindBuyNowScript = `(function(){
  var nodes=document.querySelectorAll("a,button,div,span");
  var btnTexts=["立即购买","立即抢购"];
  for(var i=0;i<nodes.length;i++){
    var el=nodes[i];var t=(el.innerText||"").trim();
    if(btnTexts.indexOf(t)===-1)continue;
    var r=el.getBoundingClientRect();
    if(r.width>30&&r.width<500&&r.height>20&&r.height<100)
      return JSON.stringify({x:Math.round(r.left+r.width/2),y:Math.round(r.top+r.height/2)});
  }
  return JSON.stringify({x:0,y:0,error:"button_not_found"});
})()`
