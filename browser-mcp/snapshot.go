package main

import (
	"fmt"
	"strconv"
)

func snapshotJS(depth int, boxes bool, targetSpec string) string {
	return fmt.Sprintf(`
(function() {
    var MAX_DEPTH    = %d;
    var INCLUDE_BOXES = %t;
    var COORD_SCALE  = 1;
    var targetSpec   = %s;

    var rootEl = document.body;
    if (targetSpec) {
        var prevMap = window.__mcpRefMap;
        if (prevMap && prevMap[targetSpec]) {
            rootEl = prevMap[targetSpec];
        } else {
            try { rootEl = document.querySelector(targetSpec) || document.body; } catch(e) {}
        }
    }

    window.__mcpRefMap = {};
    var counter = 0;

    var SKIP_TAGS = { script:1, style:1, link:1, meta:1, head:1,
                      noscript:1, template:1, svg:1, path:1 };

    function isInteractive(el, cs) {
        var tag = el.tagName.toLowerCase();
        if (tag === 'a' || tag === 'button' || tag === 'select' ||
            tag === 'input' || tag === 'textarea') return true;
        if (cs.cursor === 'pointer') return true;
        var role = el.getAttribute('role') || '';
        if (role === 'button' || role === 'link' || role === 'checkbox' ||
            role === 'menuitem' || role === 'option' || role === 'tab' ||
            role === 'switch' || role === 'slider' || role === 'tablist' ||
            role === 'treeitem' || role === 'progressbar') return true;
        var tabindex = el.getAttribute('tabindex');
        if (tabindex !== null && parseInt(tabindex, 10) >= 0) return true;
        if (el.getAttribute('onclick') || el.getAttribute('ng-click')) return true;
        if (el.getAttribute('data-testid') && (tag === 'button' || tag === 'a' || cs.cursor === 'pointer')) return true;
        return false;
    }

    function classify(el, cs, rect) {
        var tag = el.tagName.toLowerCase();
        if (tag === 'img') {
            var nw = el.naturalWidth || 0;
            var nh = el.naturalHeight || 0;
            if (nw < 100 || nh < 100) return null;
            return 'img';
        }
        var text = (el.innerText || el.textContent || '').trim().replace(/\s+/g, ' ');
        if (isInteractive(el, cs)) {
            if (rect.width >= 80 && rect.height >= 28) return 'action';
            return 'option';
        }
        if (el.children.length <= 2 && text.length > 0 && text.length <= 120) {
            var fs = parseFloat(cs.fontSize) || 0;
            var fw = parseInt(cs.fontWeight) || 400;
            if (fs >= 16 || fw >= 600) return 'content';
            if (el.children.length === 0 && (fs >= 14 || text.length <= 60)) return 'content';
        }
        return null;
    }

    function buildImgGroup(imgChildren, indent) {
        var lines = indent + '- [img-group:gallery] ' + imgChildren.length + '\n';
        for (var i = 0; i < imgChildren.length; i++) {
            var ic = imgChildren[i];
            lines += indent + '  - img ' + ic.nw + 'x' + ic.nh +
                     ' src="' + ic.src + '" [ref=' + ic.ref + ']\n';
        }
        return lines;
    }

    function walk(el, depth) {
        if (depth > MAX_DEPTH) return '';
        if (!el || !el.tagName) return '';
        var tag = el.tagName.toLowerCase();
        if (SKIP_TAGS[tag]) return '';
        var rect = el.getBoundingClientRect();
        if (rect.width === 0 && rect.height === 0) return '';
        var cs   = window.getComputedStyle(el);
        var kind = classify(el, cs, rect);
        var ref    = 'e' + (++counter);
        window.__mcpRefMap[ref] = el;
        var indent = '';
        for (var i = 0; i < depth; i++) indent += '  ';
        if (kind !== null && kind !== 'img') {
            var text = (el.innerText || el.textContent || '').trim().replace(/\s+/g, ' ').substring(0, 80);
            var line = indent + '- ' + tag;
            if (text) line += ' "' + text.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
            line += ' [' + kind + '] [ref=' + ref + ']';
            if (INCLUDE_BOXES) {
                line += ' [box=' + Math.round(rect.left*COORD_SCALE) + ',' + Math.round(rect.top*COORD_SCALE) + ','
                      + Math.round(rect.width*COORD_SCALE) + ',' + Math.round(rect.height*COORD_SCALE) + ']';
            }
            return line + '\n';
        }
        if (kind === 'img') {
            var src = el.src || el.getAttribute('data-src') || '';
            return JSON.stringify({
                __imgLeaf: true, ref: ref,
                src: src.substring(0, 200),
                nw: el.naturalWidth || 0,
                nh: el.naturalHeight || 0
            }) + '\n';
        }
        var childLines = '';
        var imgBuffer  = [];
        var count = Math.min(el.children.length, 80);
        for (var ci = 0; ci < count; ci++) {
            var childOut = walk(el.children[ci], depth + 1);
            if (!childOut) continue;
            var trimmed = childOut.trim();
            if (trimmed.charAt(0) === '{') {
                try {
                    var obj = JSON.parse(trimmed);
                    if (obj.__imgLeaf) { imgBuffer.push(obj); continue; }
                } catch(e) {}
            }
            if (imgBuffer.length > 0) {
                if (imgBuffer.length >= 3) {
                    childLines += buildImgGroup(imgBuffer, indent + '  ');
                } else {
                    for (var bi = 0; bi < imgBuffer.length; bi++) {
                        var ib = imgBuffer[bi];
                        childLines += indent + '  - img ' + ib.nw + 'x' + ib.nh +
                                      ' src="' + ib.src + '" [ref=' + ib.ref + ']\n';
                    }
                }
                imgBuffer = [];
            }
            childLines += childOut;
        }
        if (imgBuffer.length > 0) {
            if (imgBuffer.length >= 3) {
                childLines += buildImgGroup(imgBuffer, indent + '  ');
            } else {
                for (var bi2 = 0; bi2 < imgBuffer.length; bi2++) {
                    var ib2 = imgBuffer[bi2];
                    childLines += indent + '  - img ' + ib2.nw + 'x' + ib2.nh +
                                  ' src="' + ib2.src + '" [ref=' + ib2.ref + ']\n';
                }
            }
        }
        if (!childLines) return '';
        var containerLine = indent + '- ' + tag + ' [ref=' + ref + ']';
        if (INCLUDE_BOXES) {
            containerLine += ' [box=' + Math.round(rect.left*COORD_SCALE) + ',' + Math.round(rect.top*COORD_SCALE) + ','
                           + Math.round(rect.width*COORD_SCALE) + ',' + Math.round(rect.height*COORD_SCALE) + ']';
        }
        return containerLine + '\n' + childLines;
    }

    return walk(rootEl, 0);
})()
`, depth, boxes, strconv.Quote(targetSpec))
}
