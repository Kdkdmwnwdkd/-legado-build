(function () {
  'use strict';
  if (window.LegadoPageAlignment) return;
  var states = new WeakMap();
  var imageOffsets = new WeakMap();
  var excluded = 'svg,video,audio,canvas,iframe,object,embed,table,math,ruby,input,button,textarea,select,script,style';

  function inlineReaderImage(image) {
    return image.matches('.legado-text-inline-image,.legado-text-bubble') || !!image.closest('.legado-text-image-frame');
  }

  function textRects(element) {
    var walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT), node, result = [];
    while ((node = walker.nextNode())) {
      var range = document.createRange(); range.selectNodeContents(node);
      Array.prototype.forEach.call(range.getClientRects(), function (rect) {
        if (rect.width > .1 && rect.height > .1) result.push(rect);
      });
    }
    return result;
  }
  function leading(root, selector) {
    var maximum = 0, visited = 0, styles = new WeakMap();
    var paragraphs = root.querySelectorAll(selector || 'p');
    for (var index = 0; index < paragraphs.length; index++) {
      var paragraph = paragraphs[index], walker = document.createTreeWalker(paragraph, NodeFilter.SHOW_TEXT), node;
      while ((node = walker.nextNode())) {
        if (++visited > 20000) return 0;
        if (!node.data.trim()) continue;
        var range = document.createRange(); range.selectNodeContents(node);
        var rects = range.getClientRects(), rect = null;
        for (var part = 0; part < rects.length; part++) {
          if (rects[part].width > .1 && rects[part].height > .1) { rect = rects[part]; break; }
        }
        if (!rect) continue;
        var element = node.parentElement, line = rect.height;
        while (element && paragraph.contains(element)) {
          if (!styles.has(element)) styles.set(element, Number.parseFloat(getComputedStyle(element).lineHeight) || 0);
          line = Math.max(line, styles.get(element)); element = element.parentElement;
        }
        // A mixed chapter can also contain text already as tall as its line
        // box. Do not borrow room globally from those deliberately tight lines.
        if (line <= rect.height + .5) return 0;
        // Native columns reserve both halves of the line's leading at their
        // edges, although only the complete glyphs need to fit inside the text
        // bounds. Measure it from the actual font, including highlight styles.
        maximum = Math.max(maximum, line - rect.height);
      }
    }
    return maximum;
  }
  function textFits(root, bounds, selector) {
    return Array.prototype.every.call(root.querySelectorAll(selector || 'p'), function (paragraph) {
      if (paragraph.closest('[data-legado-reader-chrome]')) return true;
      return textRects(paragraph).every(function (rect) { return rect.top >= bounds.top - .5 && rect.bottom <= bounds.bottom + .5; });
    });
  }
  function linePaintBounds(line, metrics) {
    var result = {top: line.top, bottom: line.bottom, safety: 2};
    function fontSafety(style) {
      var font = style.fontStyle + ' ' + style.fontWeight + ' ' + style.fontSize + ' ' + style.fontFamily;
      if (!metrics.fonts.has(font)) {
        var descent = 0;
        try {
          if (!metrics.canvas) metrics.canvas = document.createElement('canvas').getContext('2d');
          if (metrics.canvas) {
            metrics.canvas.font = font;
            var measured = metrics.canvas.measureText('国gjpq');
            descent = measured.fontBoundingBoxDescent || measured.actualBoundingBoxDescent || 0;
          }
        } catch (_) {}
        // Match the native fullLineRequestHeight guard: keep a little room for
        // descenders/rasterization instead of treating a DOM Range as painted ink.
        metrics.fonts.set(font, Math.max(2, descent * .25));
      }
      result.safety = Math.max(result.safety, metrics.fonts.get(font));
    }
    fontSafety(getComputedStyle(line.span));
    Array.prototype.forEach.call(line.span.querySelectorAll('img,[data-legado-highlight]'), function (element) {
      var style = getComputedStyle(element), top = 0, bottom = 0;
      if (element.tagName !== 'IMG') fontSafety(style);
      if (style.borderImageSource !== 'none') {
        var parts = style.borderImageOutset.split(/\s+/);
        function outset(value, border) {
          var number = Number.parseFloat(value) || 0;
          return /px$/.test(value) ? number : number * (Number.parseFloat(border) || 0);
        }
        top = outset(parts[0], style.borderTopWidth);
        bottom = outset(parts[2] || parts[0], style.borderBottomWidth);
      }
      Array.prototype.forEach.call(element.getClientRects(), function (rect) {
        if (rect.width <= .1 || rect.height <= .1) return;
        result.top = Math.min(result.top, rect.top - top);
        result.bottom = Math.max(result.bottom, rect.bottom + bottom);
      });
    });
    return result;
  }
  function clear(root) {
    var state = states.get(root);
    if (!state) return false;
    state.forEach(function (item) {
      item.images.forEach(function (image) { imageOffsets.delete(image.node); });
      if (!root.contains(item.container)) return;
      // Text may have been changed by an author hook. Keep those changes rather
      // than restoring a stale source. Reader-created wrappers have no text.
      if (item.container.textContent !== item.text) {
        item.spans.forEach(function (span) {
          if (span.parentNode === item.container) span.replaceWith.apply(span, Array.prototype.slice.call(span.childNodes));
        });
      } else {
        item.images.forEach(function (image) { image.placeholder.replaceWith(image.node); });
        item.container.replaceChildren.apply(item.container, item.originals);
      }
    });
    states.delete(root);
    return true;
  }
  function imageLayoutRect(image) {
    var rect = image.getBoundingClientRect(), saved = imageOffsets.get(image);
    if (!saved || !saved.span.contains(image)) return rect;
    // Pagination still validates the unshifted inline image. Its translated
    // paint can extend past the aligned glyphs, just like highlight decoration.
    return {left: rect.left, right: rect.right, top: rect.top - saved.shift,
      bottom: rect.bottom - saved.shift, width: rect.width, height: rect.height};
  }
  function anchor(node, offset) {
    var block = node && (node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement);
    block = block && block.closest('p');
    if (!block || !block.contains(node)) return null;
    var range = document.createRange(); range.selectNodeContents(block);
    try { range.setEnd(node, offset || 0); }
    catch (_) { return null; }
    return {block: block, offset: range.toString().length};
  }
  function resolve(saved) {
    if (!saved || !saved.block || !saved.block.isConnected) return null;
    var walker = document.createTreeWalker(saved.block, NodeFilter.SHOW_TEXT), node, last, remaining = saved.offset;
    while ((node = walker.nextNode())) {
      if (remaining < node.length) return {node: node, offset: remaining};
      remaining -= node.length; last = node;
    }
    return last && {node: last, offset: last.length};
  }

  function forcedBreak(element, root, before) {
    var property = before ? 'breakBefore' : 'breakAfter';
    var attribute = before ? 'data-break-before' : 'data-break-after';
    function ignored(node) {
      if (node.nodeType === Node.TEXT_NODE) return !node.data.trim();
      if (node.nodeType !== Node.ELEMENT_NODE) return true;
      var display = getComputedStyle(node).display;
      return display === 'none' || /^(SCRIPT|STYLE|TEMPLATE|NOSCRIPT)$/.test(node.tagName) || !display && node.hidden;
    }
    for (; element && element !== root; element = element.parentElement) {
      if (/^(page|column|always|left|right|recto|verso)$/.test(element.getAttribute(attribute) || getComputedStyle(element)[property])) return true;
      // A break on an ancestor applies only at that ancestor's edge.
      var sibling = before ? element.previousSibling : element.nextSibling;
      while (sibling && ignored(sibling)) sibling = before ? sibling.previousSibling : sibling.nextSibling;
      if (sibling) break;
    }
    return false;
  }

  function spacingShifts(rows, topShift, remaining, boundedFit) {
    var gaps = rows.length - 1, shifts = [topShift];
    if (!gaps) return shifts;
    var capacity = [], total = 0;
    if (boundedFit && remaining < 0) {
      for (var index = 1; index < rows.length; index++) {
        var previous = rows[index - 1], line = rows[index];
        // Borrow only real inter-line room. Preserve at least half the painted
        // gap and limit each advance to a small change; glyphs are never scaled.
        var room = Math.max(0, Math.min(line.top - previous.bottom, line.paint.top - previous.paint.bottom));
        var limit = Math.min(room * .5, (line.top - previous.top) * .06);
        capacity.push(limit); total += limit;
      }
      if (-remaining > total + .1) return null;
    }
    for (var part = 0; part < gaps; part++) {
      var change = capacity.length ? remaining * (total ? capacity[part] / total : 0) : remaining / gaps;
      shifts.push(shifts[part] + change);
    }
    return shifts;
  }

  /** Retain native column breaks; move whole rendered lines without scaling glyphs. */
  function align(root, options) {
    clear(root);
    var bounds = options.bounds, pageWidth = options.pageWidth || 0;
    var pageFor = function (rect) { return pageWidth ? Math.max(0, Math.floor((rect.left - bounds.left + .5) / pageWidth)) : 0; };
    var sameLine = function (one, two) {
      return pageFor(one) === pageFor(two) &&
        Math.min(one.bottom, two.bottom) - Math.max(one.top, two.top) > Math.min(one.height, two.height) * .5;
    };
    var rectangles = function (range) {
      return Array.prototype.filter.call(range.getClientRects(), function (rect) { return rect.width > .1 && rect.height > .1; });
    };
    var paragraphs = Array.prototype.slice.call(root.querySelectorAll(options.selector || 'p'));
    var plans = [], lineCount = 0;
    for (var p = 0; p < paragraphs.length; p++) {
      var paragraph = paragraphs[p];
      if (paragraph.querySelector(excluded) || !String(paragraph.textContent || '').trim() && !paragraph.querySelector('img')) continue;
      if (Array.prototype.some.call(paragraph.querySelectorAll('img'), function (image) { return !inlineReaderImage(image); })) continue;
      var flow = paragraph.querySelector('[data-legado-highlight-flow]');
      var container = flow && paragraph.children.length === 1 ? flow : paragraph;
      var style = getComputedStyle(container);
      if (style.writingMode.indexOf('vertical') === 0 || style.direction === 'rtl') continue;
      var walker = document.createTreeWalker(container, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT), pieces = [], node;
      while ((node = walker.nextNode())) {
        if (node.nodeType === Node.ELEMENT_NODE) {
          if (node.tagName === 'IMG') {
            var imageRect = node.getBoundingClientRect();
            if (imageRect.width > .1 && imageRect.height > .1) pieces.push({node: node, start: 0, end: 0, rect: imageRect, image: true});
          }
          continue;
        }
        var start = 0;
        while (start < node.length) {
          var range = document.createRange(); range.setStart(node, start); range.setEnd(node, node.length);
          var rects = rectangles(range);
          if (!rects.length) break;
          var firstRect = rects[0], low = start + 1, high = node.length;
          if (rects.every(function (rect) { return sameLine(rect, firstRect); })) low = high;
          while (low < high) {
            var middle = Math.ceil((low + high) / 2); range.setEnd(node, middle);
            if (rectangles(range).every(function (rect) { return sameLine(rect, firstRect); })) low = middle;
            else high = middle - 1;
          }
          // A range can end within a surrogate pair. Keep the pair in its line.
          if (low < node.length && /[\uDC00-\uDFFF]/.test(node.data.charAt(low))) low++;
          pieces.push({node: node, start: start, end: low, rect: firstRect}); start = low;
        }
      }
      var groups = [];
      pieces.forEach(function (piece) {
        var group = groups[groups.length - 1];
        if (!group || !sameLine(group.rect, piece.rect)) {
          group = {rect: piece.rect, pieces: []}; groups.push(group);
        }
        group.pieces.push(piece);
      });
      lineCount += groups.length;
      // Bound pathological input before changing any live nodes.
      if (lineCount > 16000) return {alignedPages: 0, reason: 'line-budget'};
      if (!groups.length) continue;
      var seenIds = Object.create(null);
      var lines = groups.map(function (group, index) {
        var range = document.createRange(), first = group.pieces[0];
        var next = groups[index + 1] && groups[index + 1].pieces[0];
        if (index === 0) range.setStart(container, 0);
        else if (first.image) range.setStartBefore(first.node);
        else range.setStart(first.node, first.start);
        if (next && next.start === 0) {
          // Ending inside the first text node of the next inline copies an empty
          // ancestor. Its border-image still paints, despite having no glyphs.
          // End before that subtree so its decoration and fragment ID stay with
          // the next line, where the actual text is rendered.
          var boundary = next.node;
          while (boundary.parentNode !== container && !boundary.previousSibling) boundary = boundary.parentNode;
          range.setEndBefore(boundary);
        } else if (next) range.setEnd(next.node, next.start);
        else range.setEnd(container, container.childNodes.length);
        var content = range.cloneContents(), ancestor = range.commonAncestorContainer;
        if (ancestor.nodeType === Node.TEXT_NODE) ancestor = ancestor.parentElement;
        while (ancestor && ancestor !== container) {
          var shell = ancestor.cloneNode(false); shell.appendChild(content); content = shell; ancestor = ancestor.parentElement;
        }
        var span = document.createElement('span'); span.setAttribute('data-legado-page-line', '');
        span.style.setProperty('position', 'relative', 'important'); span.appendChild(content);
        Array.prototype.forEach.call(span.querySelectorAll('[data-legado-highlight]'), function (element) {
          if (!element.textContent && !element.children.length) element.remove();
        });
        Array.prototype.forEach.call(span.querySelectorAll('[id]'), function (element) {
          if (seenIds[element.id]) element.removeAttribute('id'); else seenIds[element.id] = true;
        });
        var spacing = first.node.parentElement.closest('[data-legado-highlight-spacing]');
        if (spacing && !first.image) {
          var prefix = document.createRange(); prefix.selectNodeContents(spacing); prefix.setEnd(first.node, first.start);
          if (prefix.toString().length) {
            var left = span.querySelector('[data-legado-highlight-spacing]');
            if (left) left.style.setProperty('margin-left', '0', 'important');
          }
        }
        var last = group.pieces[group.pieces.length - 1];
        spacing = last.node.parentElement.closest('[data-legado-highlight-spacing]');
        if (spacing && !last.image) {
          var suffix = document.createRange(); suffix.selectNodeContents(spacing); suffix.setStart(last.node, last.end);
          if (suffix.toString().length) {
            var endings = span.querySelectorAll('[data-legado-highlight-spacing]');
            if (endings.length) endings[endings.length - 1].style.setProperty('margin-right', '0', 'important');
          }
        }
        // An inline image can wrap onto a row with no text, including across a
        // column break. Give that row its own position instead of attaching an
        // image on the next page to the preceding line's paint bounds.
        var text = group.pieces.filter(function (piece) { return !piece.image; });
        var measured = text.length ? text : group.pieces;
        return {span: span, page: pageFor(group.rect), before: measured[0].rect, imageOnly: !text.length,
          paragraph: paragraph, firstInParagraph: index === 0, lastInParagraph: index === groups.length - 1,
          right: Math.max.apply(null, measured.map(function (piece) { return piece.rect.right; }))};
      });
      var images = Array.prototype.slice.call(container.querySelectorAll('img'));
      var copies = [];
      lines.forEach(function (line) { copies.push.apply(copies, Array.prototype.slice.call(line.span.querySelectorAll('img'))); });
      if (images.length !== copies.length || images.some(function (image, index) { return image.src !== copies[index].src; })) {
        return {alignedPages: 0, reason: 'image-structure'};
      }
      plans.push({container: container, originals: Array.prototype.slice.call(container.childNodes),
        text: container.textContent, spans: lines.map(function (line) { return line.span; }), lines: lines,
        images: images.map(function (image, index) { return {node: image, copy: copies[index], before: image.getBoundingClientRect()}; })});
    }
    var blockedBottom = Object.create(null);
    var planned = plans.map(function (plan) { return plan.container.closest('p'); });
    Array.prototype.forEach.call(root.querySelectorAll('p,h1,h2,h3,h4,h5,h6,figure,table,pre,blockquote,img,svg,video,canvas'), function (element) {
      if (planned.indexOf(element) >= 0 || element.closest('[data-legado-reader-chrome]')) return;
      if (element.tagName === 'IMG' && inlineReaderImage(element) && planned.indexOf(element.closest('p')) >= 0) return;
      Array.prototype.forEach.call(element.getClientRects(), function (rect) {
        if (rect.width > .1 && rect.height > .1) {
          var page = pageFor(rect); blockedBottom[page] = Math.max(blockedBottom[page] || -Infinity, rect.bottom);
        }
      });
    });
    plans.forEach(function (plan) {
      // Preserve live image identity, decoding and source-image callbacks. New
      // IMG nodes dispatch load events and would keep requesting another reflow.
      plan.images.forEach(function (image) {
        image.placeholder = document.createComment('legado-inline-image');
        image.node.replaceWith(image.placeholder); image.copy.replaceWith(image.node);
      });
      plan.container.replaceChildren.apply(plan.container, plan.spans);
    });
    states.set(root, plans);
    var pages = Object.create(null), invalid = false;
    plans.forEach(function (plan) {
      plan.images.forEach(function (image) {
        var rect = image.node.getBoundingClientRect();
        if (['left', 'top', 'width', 'height'].some(function (side) { return Math.abs(rect[side] - image.before[side]) > 1; })) invalid = true;
      });
      plan.lines.forEach(function (line) {
        var rs = line.imageOnly ? Array.prototype.map.call(line.span.querySelectorAll('img'), function (image) {
          return image.getBoundingClientRect();
        }) : textRects(line.span);
        if (!rs.length) { invalid = true; return; }
        if (rs.some(function (rect) { return !sameLine(rect, line.before); }) ||
          Math.abs(Math.min.apply(null, rs.map(function (rect) { return rect.left; })) - line.before.left) > 1 ||
          Math.abs(Math.max.apply(null, rs.map(function (rect) { return rect.right; })) - line.right) > 1) invalid = true;
        line.top = Math.min.apply(null, rs.map(function (rect) { return rect.top; }));
        line.bottom = Math.max.apply(null, rs.map(function (rect) { return rect.bottom; }));
        (pages[line.page] || (pages[line.page] = [])).push(line);
      });
    });
    if (invalid) { clear(root); return {alignedPages: 0, reason: 'geometry-changed'}; }
    var paintBounds = options.paintBounds || bounds, metrics = {fonts: new Map(), canvas: null};
    var pixel = Math.max(1 / (window.devicePixelRatio || 1), 1 / 64), overhangTop = 0, overhangBottom = 0;
    plans.forEach(function (plan) {
      plan.lines.forEach(function (line) {
        line.paint = linePaintBounds(line, metrics);
        // Apply the native safety beyond the whole painted line, including
        // artwork. One device pixel alone is lost to inline raster snapping.
        var safety = Math.max(line.paint.safety, pixel);
        overhangTop = Math.max(overhangTop, line.top - line.paint.top + safety);
        overhangBottom = Math.max(overhangBottom, line.paint.bottom - line.bottom + safety);
      });
    });
    if (options.paintInsets) {
      overhangTop = Math.max(overhangTop, options.paintInsets.top);
      overhangBottom = Math.max(overhangBottom, options.paintInsets.bottom);
    }
    // Keep one text edge for the whole column set, including pages whose last
    // line has no artwork. Use existing padding first, reserving extra room only
    // when the real page/chrome clip would otherwise cut the glyph or decoration.
    var textTop = Math.ceil(Math.max(bounds.top, paintBounds.top + overhangTop) * 64) / 64;
    var textBottom = Math.floor(Math.min(bounds.bottom, paintBounds.bottom - overhangBottom) * 64) / 64;
    var aligned = [], rejected = [], spacingCost = 0, maxGapAdjustment = 0;
    Object.keys(pages).forEach(function (key) {
      var rows = pages[key].sort(function (a, b) { return a.top - b.top; });
      if (blockedBottom[key] != null) rows = rows.filter(function (line) { return line.top >= blockedBottom[key] - .5; });
      if (!rows.length) return;
      var first = rows[0], last = rows[rows.length - 1], surplus = textBottom - last.bottom;
      // Text defines the reading edges; artwork uses the surrounding padding
      // or the shared safety inset. A preceding title/media block keeps its space.
      var topShift = blockedBottom[key] == null ? textTop - first.top : 0;
      // TextPage.upLinesPosition only spreads small leftovers on nearly full
      // pages. Apply that guard to every page, including explicit page breaks
      // and short text below a title, not just the final document page.
      var lastLineHeight = Math.max(last.bottom - last.top, last.paint.bottom - last.paint.top,
        Number.parseFloat(getComputedStyle(last.span).lineHeight) || 0);
      var advances = [];
      rows.forEach(function (line, index) {
        if (index && line.span.parentElement === rows[index - 1].span.parentElement) advances.push(line.top - rows[index - 1].top);
      });
      if (advances.length) {
        advances.sort(function (a, b) { return a - b; });
        lastLineHeight = Math.max(lastLineHeight, advances[Math.floor(advances.length / 2)]);
      }
      var remaining = surplus - topShift;
      var glyphHeight = Math.min(first.bottom - first.top, last.bottom - last.top);
      var gapLimit = Math.max(2, Math.min(lastLineHeight * .15, glyphHeight * .5));
      var nextRows = pages[Number(key) + 1], next = nextRows && nextRows[0];
      var continues = options.continues === true || next &&
        (!next.firstInParagraph || !forcedBreak(next.paragraph, root, true)) &&
        (!last.lastInParagraph || !forcedBreak(last.paragraph, root, false)) && blockedBottom[Number(key) + 1] == null;
      // A natural break can leave the next paragraph's margin behind as well as
      // a line. It is still a full reading page. The native near-full test is
      // sufficient for endings/forced breaks; a per-gap cap must not silently
      // disable alignment on large fonts or short landscape viewports.
      var fillBottom = rows.length > 1 && (continues || remaining < lastLineHeight + glyphHeight);
      var bottomShift = fillBottom ? surplus : topShift;
      var shifts = spacingShifts(rows, topShift, bottomShift - topShift, options.boundedFit);
      if (!shifts) { rejected.push(Number(key)); return; }
      // Fonts can extend beyond a tight line-height at either edge. Small
      // inward corrections are valid too; reject only a region that cannot
      // contain its text or a correction that would reverse the line order.
      if (rows.some(function (line, index) {
        var top = line.top + shifts[index], bottom = line.bottom + shifts[index];
        return top < textTop - .5 || bottom > textBottom + .5 ||
          index > 0 && top < rows[index - 1].bottom + shifts[index - 1] + Math.min(0, line.top - rows[index - 1].bottom) - .5;
      })) { rejected.push(Number(key)); return; }
      // Layout is unchanged: relative positioning shifts painting, selection and
      // hit testing together, while each line retains its original column.
      rows.forEach(function (line, index) {
        line.span.style.setProperty('top', shifts[index] + 'px', 'important');
        if (index) {
          var adjustment = shifts[index] - shifts[index - 1];
          spacingCost += Math.pow(adjustment / lastLineHeight, 2);
          maxGapAdjustment = Math.max(maxGapAdjustment, Math.abs(adjustment));
        }
        Array.prototype.forEach.call(line.span.querySelectorAll('img'), function (image) {
          imageOffsets.set(image, {span: line.span, shift: shifts[index]});
        });
      });
      aligned.push({page: Number(key), rows: rows.length,
        imageOnlyRows: rows.filter(function (line) { return line.imageOnly; }).length, top: first.top + topShift,
        bottom: last.bottom + bottomShift, surplus: surplus, topShift: topShift, filledBottom: fillBottom,
        gapAdjustment: rows.length > 1 ? (bottomShift - topShift) / (rows.length - 1) : 0,
        gapLimit: gapLimit, lineAdvance: lastLineHeight});
    });
    return {alignedPages: aligned.length, pages: aligned, lines: lineCount,
      rejectedPages: rejected, spacingCost: spacingCost, maxGapAdjustment: maxGapAdjustment,
      paintInsets: {top: overhangTop, bottom: overhangBottom},
      textBounds: {top: textTop, bottom: textBottom}, paintBounds: {top: paintBounds.top, bottom: paintBounds.bottom}};
  }
  window.LegadoPageAlignment = {align: align, clear: clear, anchor: anchor, resolve: resolve,
    imageLayoutRect: imageLayoutRect, leading: leading, textFits: textFits, forcedBreak: forcedBreak};
}());
