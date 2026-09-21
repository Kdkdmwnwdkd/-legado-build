(function () {
  'use strict';

  // This runtime belongs to the opaque-origin template frame. Native capability
  // checks and navigation live in template-host.js, outside author JavaScript.
  var init = window.__readerTemplateInit || {};
  var template = init.template || {};
  var channel = 'legado-reader-template';
  var token = init.token;
  var active = true, initialized = false, failure = null;
  var generation = 0, layoutRevision = 0, visualRevision = 0;
  var layoutBusy = true, visualBusy = false, running = false, requested = false;
  var layoutTimer = 0, sourceImagesPending = 0, resourcesReady = false, resourcesFailed = false;
  var pages = [], pageMap = [], pageIndex = 0, committedRoot = null, candidateRoot = null;
  var activationBoundary = '', activationTargetRevision = -1;
  var fields = Object.assign({}, init.fields || {});
  var scrollMode = init.scrollMode === true, sourcePreparing = false;
  var viewport = {width: 1, height: 1};
  var source = document.createDocumentFragment();
  var records = new Map(), imageIds = new Set(), sourceObserver, pageObserver, sizeObserver;
  var hooks = Object.create(null), pendingAuthorWork = [], layoutWaiters = [];
  var geometry = '', commandChain = Promise.resolve(), textImageMode = String(init.textImageMode);
  var imageSequence = 0, interactionSequence = 0, lastImageTap = null, lastImageAction = 0;
  var touch = null, imagePress = null, suppressImageClickUntil = 0;
  var scrollTouch = null, scrollFrame = 0, imageOverlayNode = null, imageOverlayClose = null;
  var authorVisualPending = false, authorVisualSerial = 0, mutationTimer = 0;
  var selectionDeferredLayout = false;
  // Presentation is deliberately separate from layout/readiness. Native snapshots
  // see the settled page; only the committed foreground page may animate afterward.
  var motionState = 'settled';
  var lastMotionPage = null, lastMotionState = null, lastMotionReduced = false;
  var reducedMotion = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
  var stylesheetWork = new WeakMap(), backgroundWork = new Map(), backgroundPixels = new Set(), headObserver;
  var sourceRequests = new Set(), timers = new Set();
  var CANCELLED = {};
  var failedImage = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='32' height='24' viewBox='0 0 32 24'%3E%3Crect x='1' y='1' width='30' height='22' rx='4' fill='%23888888' fill-opacity='.18'/%3E%3Cpath d='m11 7 10 10m0-10L11 17' stroke='%23888888' stroke-width='2'/%3E%3C/svg%3E";

  function later(callback, delay) {
    var id = setTimeout(function () { timers.delete(id); if (active) callback(); }, delay);
    timers.add(id);
    return id;
  }
  function clearLater(id) { clearTimeout(id); timers.delete(id); }
  function frame() { return new Promise(function (resolve) { requestAnimationFrame(resolve); }); }
  async function twoFrames() { await frame(); await frame(); }
  function post(type, value) {
    if (active) parent.postMessage(Object.assign({}, value || {}, {channel: channel, type: type, token: token}), '*');
  }
  function bounded(value, milliseconds, message) {
    return new Promise(function (resolve, reject) {
      var timer = later(function () { reject(new Error(message)); }, milliseconds);
      Promise.resolve(value).then(function (result) { clearLater(timer); resolve(result); },
        function (error) { clearLater(timer); reject(error); });
    });
  }
  function updateViewport() {
    viewport.width = Math.max(1, Math.round(window.innerWidth || (init.viewport || {}).width || 1));
    viewport.height = Math.max(1, Math.round(window.innerHeight || (init.viewport || {}).height || 1));
  }
  function checkRun(expected) {
    if (!active || expected !== generation) throw CANCELLED;
    if (failure) throw failure;
  }
  function textNodes(element) {
    var nodes = [], walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT), node;
    while ((node = walker.nextNode())) {
      var parentElement = node.parentElement;
      if (parentElement && parentElement.closest('script,style,noscript,[data-reader-text-ignore],[data-legado-image-action]')) continue;
      nodes.push(node);
    }
    return nodes;
  }
  function readableText(element) { return textNodes(element).map(function (node) { return node.data; }).join(''); }
  function prepareSource() {
    var parsed = new DOMParser().parseFromString(String(init.sourceHtml || ''), 'text/html');
    Array.prototype.forEach.call(parsed.head.querySelectorAll('style,link[rel~="stylesheet"]'), function (node) {
      var imported = document.importNode(node, true);
      trackStyles(imported);
      document.head.appendChild(imported);
    });
    Array.prototype.forEach.call(parsed.body.childNodes, function (node) { source.appendChild(document.importNode(node, true)); });
    var cursor = 0, plainText = String(init.plainText || '');
    Array.prototype.forEach.call(source.querySelectorAll('[data-reader-block]'), function (node) {
      var id = node.getAttribute('data-reader-block'), kind = node.getAttribute('data-reader-kind');
      if (!id || records.has(id)) throw new Error('正文块标识重复，无法建立阅读位置');
      var rawOffset = node.getAttribute('data-legado-text-offset');
      var start = rawOffset == null ? cursor : Number(rawOffset);
      var text = kind === 'placeholder' ? '' : readableText(node);
      var canonical = rawOffset != null;
      if (!Number.isSafeInteger(start) || start < 0 || start > plainText.length ||
          (canonical && (plainText.slice(start, start + text.length) !== text || plainText.charAt(start + text.length) !== '\n'))) {
        throw new Error('正文文字与阅读位置不一致，已停止模板分页');
      }
      var end = canonical ? start + text.length + 1 : start;
      records.set(id, {id: id, kind: kind, start: start, end: end, text: text, canonical: canonical});
      cursor = Math.max(cursor, end);
    });
    if (!records.size || cursor !== plainText.length) throw new Error('正文缺少完整的位置标记');
    Array.prototype.forEach.call(source.querySelectorAll('img[data-legado-image-id]'), function (image) {
      var id = image.getAttribute('data-legado-image-id');
      if (imageIds.has(id)) throw new Error('正文图片标识重复');
      imageIds.add(id);
      if (image.getAttribute('loading') === 'lazy') image.setAttribute('loading', 'eager');
    });
  }
  async function emitHook(name, value) {
    var callbacks = (hooks[name] || []).slice();
    for (var index = 0; index < callbacks.length; index++) {
      await bounded(callbacks[index](value, api), 8000, '模板 ' + name + ' 回调未完成');
    }
    await drainAuthorWork();
  }
  async function drainAuthorWork() {
    var rounds = 0;
    while (pendingAuthorWork.length) {
      if (++rounds > 32) throw new Error('模板持续创建待处理任务，无法完成分页');
      await bounded(Promise.all(pendingAuthorWork.splice(0)), 8000, '模板等待的资源或脚本超时');
    }
  }
  var api = {
    source: source,
    fields: fields,
    viewport: viewport,
    on: function (name, callback) {
      if (typeof callback !== 'function') throw new TypeError('readerTemplate.on requires a function');
      var list = hooks[name] || (hooks[name] = []);
      list.push(callback);
      return function () { var index = list.indexOf(callback); if (index >= 0) list.splice(index, 1); };
    },
    requestLayout: function () { requestLayout('author'); },
    waitUntil: function (promise) {
      var work = Promise.resolve(promise);
      // Register a handler immediately, even when the author schedules work from
      // an asynchronous event before the next layout callback drains the queue.
      work.catch(function () {});
      pendingAuthorWork.push(work);
      return work;
    },
    get pageIndex() { return pageIndex; },
    get pageCount() { return pages.length; },
    get pages() { return pages.slice(); },
    get currentPage() { return pages[pageIndex] || null; },
    get motionState() { return pages[pageIndex] ? pages[pageIndex].getAttribute('data-reader-motion') : motionState; }
  };
  window.readerTemplate = api;

  function syncMotion() {
    pages.forEach(function (page, index) {
      var next = layoutBusy || (reducedMotion && reducedMotion.matches) ? 'settled' :
        document.hidden || index !== pageIndex ? 'paused' : motionState;
      var entry = page.getAttribute('data-reader-entry') || 'pending';
      if (next === 'running' && entry === 'pending') entry = 'playing';
      else if (next !== 'running' && entry === 'playing') entry = 'done';
      if (page.getAttribute('data-reader-entry') !== entry) page.setAttribute('data-reader-entry', entry);
      if (page.getAttribute('data-reader-motion') !== next) page.setAttribute('data-reader-motion', next);
    });
    var page = pages[pageIndex] || null;
    if (!page || !active || failure) return;
    // New page DOM is announced only after its observers are attached. The old
    // current page still receives settled while pagination stops its animation.
    if (layoutBusy && page !== lastMotionPage) return;
    var state = page.getAttribute('data-reader-motion');
    var reduce = !!(reducedMotion && reducedMotion.matches);
    if (page === lastMotionPage && state === lastMotionState && reduce === lastMotionReduced) return;
    var event = {page: page, pageIndex: pageIndex, pageCount: pages.length, state: state,
      previousPage: lastMotionPage, previousState: lastMotionState, reducedMotion: reduce};
    // Commit the notification identity before author code can request another layout.
    lastMotionPage = page; lastMotionState = state; lastMotionReduced = reduce;
    // Presentation callbacks may return an infinite animation's Promise. Their
    // synchronous pause/static pose runs before the native frame barrier, while
    // asynchronous rejection still follows the normal template-error path.
    (hooks.motionChange || []).slice().forEach(function (callback) {
      if (!active || failure) return;
      try { Promise.resolve(callback(event, api)).catch(fail); } catch (error) { fail(error); }
    });
  }
  function setMotionState(value) {
    if (!/^(settled|running|paused)$/.test(String(value))) return;
    var changed = motionState !== value;
    motionState = value;
    syncMotion();
    // An author callback may already have changed the state by requesting layout.
    if (changed && motionState === value) post('motionState', {state: value});
  }

  function appendStyle(css, id) {
    var style = document.createElement('style');
    if (id) style.id = id;
    style.textContent = String(css || '');
    trackStyles(style);
    document.head.appendChild(style);
    return style;
  }
  async function runScripts(root) {
    var scripts = Array.prototype.slice.call(root.querySelectorAll('script'));
    for (var index = 0; index < scripts.length; index++) {
      var old = scripts[index], script = document.createElement('script');
      Array.prototype.forEach.call(old.attributes, function (attribute) { script.setAttribute(attribute.name, attribute.value); });
      script.textContent = old.textContent;
      var type = (script.getAttribute('type') || '').trim().toLowerCase();
      var executable = !type || /^(?:text|application)\/(?:java|ecma)script$/.test(type) || type === 'module';
      if (executable && (script.src || type === 'module')) {
        await bounded(new Promise(function (resolve, reject) {
          script.onload = resolve;
          script.onerror = function () { reject(new Error('模板脚本加载失败：' + (script.src || 'module'))); };
          old.replaceWith(script);
        }), 8000, '模板脚本加载超时');
      } else old.replaceWith(script);
      if (failure) throw failure;
    }
  }
  function bindFields(page, index, count) {
    Array.prototype.forEach.call(page.querySelectorAll('[data-reader-field]'), function (node) {
      var name = node.getAttribute('data-reader-field');
      var value = name === 'page' ? (index + 1) + '/' + (count || '…') :
        name === 'pageIndex' ? index + 1 : name === 'pageCount' ? count || '…' : fields[name];
      value = value == null ? '' : String(value);
      if (node.textContent !== value) node.textContent = value;
    });
  }
  function orderedSlots(page) {
    return Array.prototype.map.call(page.querySelectorAll('[data-reader-flow]'), function (node, index) {
      if (node.parentElement.closest('[data-reader-flow]')) throw new Error('正文区域不能相互嵌套');
      var value = node.getAttribute('data-reader-order');
      return {node: node, index: index, order: value != null && Number.isFinite(Number(value)) ? Number(value) : index};
    }).sort(function (a, b) { return a.order - b.order || a.index - b.index; }).map(function (entry) { return entry.node; });
  }
  function slotBox(slot) {
    var style = getComputedStyle(slot), rect = slot.getBoundingClientRect();
    var px = function (name) { return parseFloat(style[name]) || 0; };
    return {
      width: Math.max(0, slot.clientWidth - px('paddingLeft') - px('paddingRight')),
      // clientHeight rounds up fractional slots and can admit a clipped last row.
      height: Math.max(0, rect.height - px('borderTopWidth') - px('borderBottomWidth') - px('paddingTop') - px('paddingBottom')),
      left: rect.left + px('borderLeftWidth') + px('paddingLeft'),
      top: rect.top + px('borderTopWidth') + px('paddingTop'),
      font: style.font, lineHeight: style.lineHeight, letterSpacing: style.letterSpacing,
      writingMode: style.writingMode, direction: style.direction
    };
  }
  function geometryOf(list) {
    return JSON.stringify(list.map(function (page) {
      var pageRect = page.getBoundingClientRect();
      return orderedSlots(page).map(function (slot) {
        var box = slotBox(slot);
        // Scrolling changes viewport coordinates, not pagination geometry.
        box.left -= pageRect.left; box.top -= pageRect.top;
        return box;
      });
    }));
  }
  function trackStyles(root) {
    var nodes = root.matches && root.matches('style,link[rel~="stylesheet"]') ? [root] :
      Array.prototype.slice.call(root.querySelectorAll('style,link[rel~="stylesheet"]'));
    nodes.forEach(function (node) {
      if (stylesheetWork.has(node)) return;
      if (node.tagName === 'STYLE' && !/@import\b/i.test(node.textContent)) return;
      var work = new Promise(function (resolve) {
        var settled = false, timer;
        function finish(failed) {
          if (settled) return; settled = true;
          clearLater(timer); node.removeEventListener('load', loaded); node.removeEventListener('error', error);
          if (failed) resourcesFailed = true;
          resolve();
        }
        function loaded() { finish(false); }
        function error() { finish(true); }
        node.addEventListener('load', loaded); node.addEventListener('error', error);
        timer = later(function () { finish(true); }, 3500);
        // An already-connected cached link can have completed before a dynamic
        // author mutation is observed. A sheet is then usable for measurement.
        if (node.isConnected && node.tagName === 'LINK' && node.sheet) finish(false);
      });
      stylesheetWork.set(node, work);
    });
  }
  async function waitStyles(root) {
    trackStyles(root);
    var nodes = Array.prototype.slice.call(root.querySelectorAll('style,link[rel~="stylesheet"]'));
    await Promise.all(nodes.map(function (node) { return stylesheetWork.get(node); }));
  }
  async function waitBackgrounds(root) {
    var nodes = [root].concat(Array.prototype.slice.call(root.querySelectorAll('*'))), promises = [];
    function add(url) {
      if (!url) return;
      if (!backgroundWork.has(url)) {
        var image = new Image(); image.src = url;
        backgroundWork.set(url, imageReady(image, false).then(function (ready) { if (ready) backgroundPixels.add(url); }));
      }
      promises.push(backgroundWork.get(url));
    }
    for (var index = 0; index < nodes.length; index++) {
      var node = nodes[index];
      ['','::before','::after'].forEach(function (pseudo) {
        var style = getComputedStyle(node, pseudo || null), expression = style.backgroundImage + ' ' + style.borderImageSource + ' ' + style.content;
        var pattern = /url\(\s*(?:"([^"]*)"|'([^']*)'|([^)]*))\s*\)/g, match;
        while ((match = pattern.exec(expression))) add((match[1] || match[2] || match[3] || '').trim());
      });
      if (node.namespaceURI === 'http://www.w3.org/2000/svg' && node.localName === 'image') add(node.getAttribute('href') || node.getAttribute('xlink:href'));
      if (index % 200 === 199) await new Promise(function (resolve) { later(resolve, 0); });
    }
    await Promise.all(promises);
  }
  async function imageReady(image, replaceFailure) {
    var url = image.currentSrc || image.getAttribute('src') || image.getAttribute('srcset');
    if (!url) return false;
    try {
      await bounded(new Promise(function (resolve, reject) {
        var settled = false;
        function finish(error) {
          if (settled) return;
          settled = true;
          image.removeEventListener('load', loaded); image.removeEventListener('error', failed);
          if (error) reject(error); else resolve();
        }
        function failed() { finish(new Error('图片加载失败')); }
        function loaded() {
          var decoded = image.decode ? image.decode() : Promise.resolve();
          Promise.resolve(decoded).then(function () {
            finish(image.naturalWidth > 0 && image.naturalHeight > 0 ? null : new Error('图片没有可显示的像素'));
          }, failed);
        }
        image.addEventListener('load', loaded); image.addEventListener('error', failed);
        if (image.complete) loaded();
      }), 6000, '图片解码超时');
      return true;
    } catch (error) {
      resourcesFailed = true;
      if (replaceFailure && image.getAttribute('src') !== failedImage) {
        image.setAttribute('data-legado-image-state', 'failed');
        image.setAttribute('aria-label', '图片加载失败');
        image.src = failedImage;
        return imageReady(image, false);
      }
      return false;
    }
  }
  async function loadFonts(slot) {
    if (!document.fonts) return;
    var style = getComputedStyle(slot);
    try {
      if (document.fonts.load && style.font) await bounded(document.fonts.load(style.font, '正文Aa'), 3500, '模板字体加载超时');
      await bounded(document.fonts.ready, 3500, '模板字体尚未就绪');
    } catch (_) { resourcesFailed = true; }
  }
  function prepareLayoutSource() {
    var clone = source.cloneNode(true), sequence = 0, order = new WeakMap(), elements = new Map();
    var walker = document.createTreeWalker(clone, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT), node;
    while ((node = walker.nextNode())) {
      order.set(node, sequence++);
      if (node.nodeType === Node.ELEMENT_NODE) {
        node.setAttribute('data-ref', 'reader-source-' + sequence);
        elements.set(node.getAttribute('data-ref'), node);
        if (node.id) node.setAttribute('data-id', node.id);
      }
    }
    return {source: clone, order: order, elements: elements};
  }
  function rememberRenderedSource(clone, original, sources) {
    if (clone.nodeType !== original.nodeType) throw new Error('分页器改变了正文节点结构');
    var offset = 0;
    if (clone.nodeType === Node.TEXT_NODE) {
      offset = original.length - clone.length;
      if (offset < 0 || original.data.slice(offset) !== clone.data) throw new Error('分页器改变了正文文字');
    }
    sources.set(clone, {node: original, offset: offset});
    // Paged deep-clones paragraphs and inline elements. Their nested Text nodes
    // do not receive individual renderNode hooks, so retain the entire pairing.
    var clonedChild = clone.firstChild, originalChild = original.firstChild;
    while (clonedChild) {
      if (!originalChild) throw new Error('分页器添加了未知正文节点');
      rememberRenderedSource(clonedChild, originalChild, sources);
      clonedChild = clonedChild.nextSibling; originalChild = originalChild.nextSibling;
    }
  }
  function installSourceBreaks(layout, prepared) {
    var sources = new WeakMap(), originalCreate = layout.createBreakToken;
    layout.hooks.onBreakToken.register(function (value) {
      if (value) value.equals = function (other) {
        return !!other && this.node === other.node && (this.offset == null ? 0 : this.offset) === (other.offset == null ? 0 : other.offset);
      };
      // Paged treats a zero offset as unspecified. A real break from 0 to 190 in
      // the same Text node must count as progress and extract the overflow.
      return value;
    });
    layout.hooks.renderNode.register(function (clone, original) { rememberRenderedSource(clone, original, sources); });
    layout.createBreakToken = function (overflow, rendered, source) {
      var container = overflow.startContainer, offset = overflow.startOffset;
      var node = container.nodeType === Node.TEXT_NODE ? container : container.childNodes[offset];
      var mapped = node && sources.get(node);
      if (!mapped && node && node.nodeType === Node.ELEMENT_NODE) {
        var original = prepared.elements.get(node.getAttribute('data-ref'));
        if (original) mapped = {node: original, offset: 0};
      }
      if (!mapped) return originalCreate.call(this, overflow, rendered, source);
      var absolute = mapped.offset + (container.nodeType === Node.TEXT_NODE ? offset : 0);
      if (absolute === 0 && container.nodeType === Node.TEXT_NODE) {
        // If none of this paragraph fits, remove its empty highlight shells too.
        // Climb only through the same first-child chain in the original source:
        // a continuation wrapper may have omitted earlier children, and moving
        // its token to the original parent would repeat already-read text.
        var boundary = container;
        while (boundary.parentNode !== rendered && !boundary.previousSibling) {
          var parent = sources.get(boundary.parentNode);
          if (!parent || parent.node.firstChild !== mapped.node) break;
          boundary = boundary.parentNode; mapped = parent;
        }
        if (boundary !== container) overflow.setStartBefore(boundary);
      }
      if (mapped.node.nodeType === Node.TEXT_NODE) {
        var boundary = graphemeStart(mapped.node.data, absolute);
        if (boundary < mapped.offset) throw new Error('分页断点位于未完成的字符中');
        if (container.nodeType === Node.TEXT_NODE && boundary !== absolute) overflow.setStart(container, boundary - mapped.offset);
        absolute = boundary;
      }
      // Upstream uses includes/indexOf to recover this location. Repeated
      // sentences and identical text around an image make that search ambiguous.
      // Keep source-node identity and its absolute offset instead.
      return this.breakAt(mapped.node, absolute);
    };
  }
  function tokenPosition(value, order) {
    if (!value) return null;
    var position = order.get(value.node);
    if (position == null) throw new Error('分页器返回了未知正文位置');
    return [position, Number(value.offset) || 0];
  }
  function progressed(previous, next) {
    return !next || !previous || next[0] > previous[0] || (next[0] === previous[0] && next[1] > previous[1]);
  }
  var graphemeSegmenter = typeof Intl !== 'undefined' && Intl.Segmenter ? new Intl.Segmenter(undefined, {granularity: 'grapheme'}) : null;
  function graphemeStart(text, offset) {
    if (offset <= 0) return 0;
    if (offset >= text.length) return text.length;
    if (graphemeSegmenter) {
      var previous = 0;
      for (var segment of graphemeSegmenter.segment(text)) {
        if (segment.index > offset) return previous;
        previous = segment.index;
      }
      return previous;
    }
    // Older WebViews: preserve surrogate pairs, combining marks, emoji skin
    // tones, variation selectors, regional-indicator pairs, and ZWJ sequences.
    var start = 0, index = 0, previousCode = -1, regional = 0;
    while (index < text.length) {
      var code = text.codePointAt(index);
      var mark = code >= 0x300 && code <= 0x36f || code >= 0x1ab0 && code <= 0x1aff ||
        code >= 0x1dc0 && code <= 0x1dff || code >= 0x20d0 && code <= 0x20ff ||
        code >= 0xfe00 && code <= 0xfe0f || code >= 0xfe20 && code <= 0xfe2f ||
        code >= 0x1f3fb && code <= 0x1f3ff || code >= 0xe0100 && code <= 0xe01ef;
      var isRegional = code >= 0x1f1e6 && code <= 0x1f1ff;
      if (!mark && code !== 0x200d && previousCode !== 0x200d && !(isRegional && regional % 2 === 1)) {
        if (index > offset) return start;
        start = index;
      }
      regional = isRegional ? regional + 1 : 0;
      previousCode = code; index += code > 0xffff ? 2 : 1;
    }
    return start;
  }
  function rectOutside(rect, bounds) {
    return rect.width > .1 && rect.height > .1 && (rect.bottom > bounds.bottom + .5 ||
      rect.right > bounds.right + .5 || rect.top < bounds.top - .5 || rect.left < bounds.left - .5);
  }
  function flowContentVisible(node, rendered) {
    var element = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
    var style = getComputedStyle(element);
    if (style.visibility === 'hidden' || style.visibility === 'collapse') return false;
    // Pending pages are deliberately transparent. Only author visibility inside
    // the flow matters here; including the staging parent would skip all checks.
    for (; element; element = element.parentElement) {
      style = getComputedStyle(element);
      if (style.display === 'none' || parseFloat(style.opacity) <= .01) return false;
      if (element === rendered) break;
    }
    return true;
  }
  function fitFirstLineMetrics(rendered, bounds, trimLeading) {
    var walker = document.createTreeWalker(rendered, NodeFilter.SHOW_TEXT), node, first = null, shift = 0;
    while ((node = walker.nextNode())) {
      if (!node.data.trim() || node.parentElement.closest('script,style,noscript,[data-reader-text-ignore]') || !flowContentVisible(node, rendered)) continue;
      var range = document.createRange(); range.selectNodeContents(node);
      var rects = range.getClientRects();
      for (var index = 0; index < rects.length; index++) {
        var rect = rects[index];
        if (rect.width <= .1 || rect.height <= .1) continue;
        if (!first) first = {node: node, rect: rect};
        if (rect.top >= bounds.top - .5) continue;
        var block = node.parentElement;
        while (block !== rendered && /^(inline|contents)$/.test(getComputedStyle(block).display)) block = block.parentElement;
        var blockStyle = getComputedStyle(block);
        if (blockStyle.position === 'absolute' || blockStyle.position === 'fixed' || blockStyle.transform !== 'none' || block.getBoundingClientRect().top < bounds.top - .5) continue;
        shift = Math.max(shift, bounds.top - rect.top);
      }
    }
    // A font's glyph box can be taller than the author's line-height. Give the
    // first line its measured ascent clearance instead of rejecting a valid font
    // or subtracting a fixed line-height from every region.
    if (shift > .5) {
      rendered.style.paddingTop = ((parseFloat(getComputedStyle(rendered).paddingTop) || 0) + shift) + 'px';
      return true;
    }
    if (!trimLeading || !first) return false;
    var paragraph = first.node.parentElement.closest('p.reader-paragraph');
    if (!paragraph || paragraph.querySelector('svg,video,audio,canvas,iframe,object,embed,table,math,ruby,input,button,textarea,select')) return false;
    var style = getComputedStyle(paragraph);
    if (style.writingMode.indexOf('vertical') === 0 || style.direction === 'rtl' ||
        style.position !== 'static' || style.transform !== 'none') return false;
    var leading = Math.min(first.rect.top - bounds.top, first.rect.top - paragraph.getBoundingClientRect().top);
    // Reclaim only the first line's own leading, leaving author margins and
    // preceding titles in place. Images must still fit before any alignment
    // translation; their bounds are never exempted from pagination checks.
    Array.prototype.forEach.call(rendered.querySelectorAll('img,svg,canvas,video,iframe,object,embed,hr'), function (media) {
      if (flowContentVisible(media, rendered)) leading = Math.min(leading, media.getBoundingClientRect().top - bounds.top);
    });
    leading = Math.floor(leading * 64) / 64;
    if (leading <= .5) return false;
    rendered.style.marginTop = ((parseFloat(getComputedStyle(rendered).marginTop) || 0) - leading) + 'px';
    return true;
  }
  function inlineImageLineStart(image, rect) {
    if (!image.matches('.legado-text-inline-image,.legado-text-bubble') && !image.closest('.legado-text-image-frame')) return null;
    var paragraph = image.closest('p.reader-paragraph');
    if (!paragraph) return null;
    var style = getComputedStyle(paragraph);
    if (style.writingMode.indexOf('vertical') === 0 || style.direction === 'rtl') return null;
    var walker = document.createTreeWalker(paragraph, NodeFilter.SHOW_TEXT), node;
    while ((node = walker.nextNode())) {
      if (!(node.compareDocumentPosition(image) & Node.DOCUMENT_POSITION_FOLLOWING)) break;
      if (!node.data.trim()) continue;
      var range = document.createRange(); range.selectNodeContents(node);
      var row = Array.prototype.find.call(range.getClientRects(), function (text) {
        return text.width > .1 && Math.min(text.bottom, rect.bottom) - Math.max(text.top, rect.top) > Math.min(text.height, rect.height) * .5;
      });
      if (!row) continue;
      // Keep the complete visual row with its image. Moving only IMG would
      // leave an orphan above the first text line in the next flow region.
      var low = 1, high = node.length;
      while (low < high) {
        var middle = Math.floor((low + high) / 2); range.setEnd(node, middle);
        var reaches = Array.prototype.some.call(range.getClientRects(), function (text) {
          return text.width > .1 && text.bottom > row.top + row.height * .5;
        });
        if (reaches) high = middle; else low = middle + 1;
      }
      return {node: node, offset: graphemeStart(node.data, low - 1)};
    }
    return null;
  }
  function completeContentOverflow(rendered, bounds, paintBounds) {
    var walker = document.createTreeWalker(rendered, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT), node;
    while ((node = walker.nextNode())) {
      if (!flowContentVisible(node, rendered)) continue;
      var range = document.createRange();
      if (node.nodeType === Node.TEXT_NODE) {
        if (!node.data.trim() || node.parentElement.closest('script,style,noscript,[data-reader-text-ignore]')) continue;
        range.selectNodeContents(node);
        if (!Array.prototype.some.call(range.getClientRects(), function (rect) { return rectOutside(rect, bounds); })) continue;
        // The DOM is never modified during this search. Prefix ranges make the
        // fit predicate monotonic even with bidi text and variable font sizes.
        var low = 0, high = node.length;
        while (low < high) {
          var middle = Math.ceil((low + high) / 2);
          range.setStart(node, 0); range.setEnd(node, middle);
          if (Array.prototype.some.call(range.getClientRects(), function (rect) { return rectOutside(rect, bounds); })) high = middle - 1;
          else low = middle;
        }
        range.setStart(node, graphemeStart(node.data, low));
      } else if (/^(img|svg|canvas|video|iframe|object|embed|hr)$/.test(String(node.localName).toLowerCase())) {
        var inline = node.localName === 'img' && (node.matches('.legado-text-inline-image,.legado-text-bubble') || node.closest('.legado-text-image-frame'));
        var mediaRect = !paintBounds && node.localName === 'img' && window.LegadoPageAlignment && window.LegadoPageAlignment.imageLayoutRect ?
          window.LegadoPageAlignment.imageLayoutRect(node) : node.getBoundingClientRect();
        var mediaBounds = paintBounds && inline ? {top: paintBounds.top, bottom: paintBounds.bottom, left: bounds.left, right: bounds.right} : bounds;
        if (!rectOutside(mediaRect, mediaBounds)) continue;
        var lineStart = node.localName === 'img' && inlineImageLineStart(node, mediaRect);
        if (lineStart) range.setStart(lineStart.node, lineStart.offset);
        else range.setStartBefore(node);
      } else continue;
      range.setEndAfter(rendered.lastChild);
      return range;
    }
    return null;
  }
  function flowPaintBounds(rendered, page, bounds) {
    var pageBounds = page.getBoundingClientRect(), limits = {top: pageBounds.top, bottom: pageBounds.bottom};
    function avoid(rect, share) {
      if (rect.width <= .1 || rect.height <= .1 || rect.right <= bounds.left || rect.left >= bounds.right) return;
      // A tight chrome line box can leave a few pixels of its glyphs inside
      // the flow. Keep the actual ink edge, including that inward extension.
      var center = (rect.top + rect.bottom) / 2;
      if ((share === 1 ? center : rect.bottom) <= bounds.top + .1) limits.top = Math.max(limits.top, bounds.top + (rect.bottom - bounds.top) / share);
      if ((share === 1 ? center : rect.top) >= bounds.bottom - .1) limits.bottom = Math.min(limits.bottom, bounds.bottom + (rect.top - bounds.bottom) / share);
    }
    orderedSlots(page).forEach(function (slot) {
      if (!slot.contains(rendered)) avoid(slot.getBoundingClientRect(), 2);
    });
    var walker = document.createTreeWalker(page, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT), node;
    while ((node = walker.nextNode())) {
      var element = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
      if (element.closest('[data-reader-flow],script,style,noscript') || !flowContentVisible(node, page)) continue;
      if (node.nodeType === Node.TEXT_NODE && node.data.trim()) {
        var range = document.createRange(); range.selectNodeContents(node);
        Array.prototype.forEach.call(range.getClientRects(), function (rect) { avoid(rect, 1); });
      } else if (node.nodeType === Node.ELEMENT_NODE && /^(img|svg|canvas|video|iframe|object|embed|hr)$/.test(node.localName)) {
        avoid(node.getBoundingClientRect(), 1);
      }
    }
    return limits;
  }
  function revealFlowArtwork(rendered, page) {
    var viewportNode = rendered.parentElement, bounds = viewportNode.getBoundingClientRect();
    var outset = {top: 0, right: 0, bottom: 0, left: 0};
    Array.prototype.forEach.call(rendered.querySelectorAll('[data-legado-highlight]'), function (highlight) {
      var style = getComputedStyle(highlight);
      if (style.borderImageSource === 'none') return;
      var values = style.borderImageOutset.split(/\s+/);
      values = [values[0], values[1] || values[0], values[2] || values[0], values[3] || values[1] || values[0]];
      var sides = ['Top', 'Right', 'Bottom', 'Left'];
      values = values.map(function (value, index) {
        var number = parseFloat(value) || 0;
        return /px$/.test(value) ? number : number * (parseFloat(style['border' + sides[index] + 'Width']) || 0);
      });
      Array.prototype.forEach.call(highlight.getClientRects(), function (rect) {
        if (rect.width <= .1 || rect.height <= .1) return;
        outset.top = Math.max(outset.top, bounds.top - rect.top + values[0]);
        outset.right = Math.max(outset.right, rect.right + values[1] - bounds.right);
        outset.bottom = Math.max(outset.bottom, rect.bottom + values[2] - bounds.bottom);
        outset.left = Math.max(outset.left, bounds.left - rect.left + values[3]);
      });
    });
    Array.prototype.forEach.call(rendered.querySelectorAll('img'), function (image) {
      var rect = image.getBoundingClientRect();
      if (rect.width <= .1 || rect.height <= .1) return;
      outset.top = Math.max(outset.top, bounds.top - rect.top);
      outset.right = Math.max(outset.right, rect.right - bounds.right);
      outset.bottom = Math.max(outset.bottom, rect.bottom - bounds.bottom);
      outset.left = Math.max(outset.left, bounds.left - rect.left);
    });
    if (!Object.keys(outset).some(function (side) { return outset[side] > .1; })) return;
    var pageBounds = page.getBoundingClientRect();
    outset.top = Math.min(outset.top, Math.max(0, bounds.top - pageBounds.top));
    outset.right = Math.min(outset.right, Math.max(0, pageBounds.right - bounds.right));
    outset.bottom = Math.min(outset.bottom, Math.max(0, pageBounds.bottom - bounds.bottom));
    outset.left = Math.min(outset.left, Math.max(0, bounds.left - pageBounds.left));
    function avoid(rect, share) {
      if (rect.width <= .1 || rect.height <= .1) return;
      if (rect.right > bounds.left - outset.left && rect.left < bounds.right + outset.right) {
        if (rect.bottom <= bounds.top + .1) outset.top = Math.min(outset.top, Math.max(0, bounds.top - rect.bottom) / share);
        if (rect.top >= bounds.bottom - .1) outset.bottom = Math.min(outset.bottom, Math.max(0, rect.top - bounds.bottom) / share);
      }
      if (rect.bottom > bounds.top - outset.top && rect.top < bounds.bottom + outset.bottom) {
        if (rect.right <= bounds.left + .1) outset.left = Math.min(outset.left, Math.max(0, bounds.left - rect.right) / share);
        if (rect.left >= bounds.right - .1) outset.right = Math.min(outset.right, Math.max(0, rect.left - bounds.right) / share);
      }
    }
    // Neighbouring flows share a gutter. Chrome keeps its actual text/media
    // bounds, so decorations may use blank leading without covering a footer.
    orderedSlots(page).forEach(function (slot) {
      if (!slot.contains(rendered)) avoid(slot.getBoundingClientRect(), 2);
    });
    var walker = document.createTreeWalker(page, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT), node;
    while ((node = walker.nextNode())) {
      var element = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
      if (element.closest('[data-reader-flow],script,style,noscript') || !flowContentVisible(node, page)) continue;
      if (node.nodeType === Node.TEXT_NODE && node.data.trim()) {
        var range = document.createRange(); range.selectNodeContents(node);
        Array.prototype.forEach.call(range.getClientRects(), function (rect) { avoid(rect, 1); });
      } else if (node.nodeType === Node.ELEMENT_NODE && /^(img|svg|canvas|video|iframe|object|embed|hr)$/.test(node.localName)) {
        avoid(node.getBoundingClientRect(), 1);
      }
    }
    // Pagination and the final text/image overflow check have already finished.
    // Expose only the measured decoration allowance, retaining a finite clip.
    viewportNode.style.overflow = 'visible';
    viewportNode.style.clipPath = 'inset(' + ['top', 'right', 'bottom', 'left'].map(function (side) { return -outset[side] + 'px'; }).join(' ') + ')';
  }
  function createStage() {
    var stage = document.createElement('div');
    stage.setAttribute('data-reader-runtime-stage', 'pending');
    document.body.appendChild(stage);
    if (scrollMode) stage.addEventListener('scroll', function () {
      if (stage !== committedRoot || layoutBusy || scrollFrame) return;
      scrollFrame = requestAnimationFrame(function () {
        scrollFrame = 0;
        if (!active || stage !== committedRoot || layoutBusy) return;
        var next = Math.max(0, Math.min(pages.length - 1, Math.floor((stage.scrollTop + 1) / viewport.height)));
        if (next !== pageIndex) {
          setMotionState('settled');
          pageIndex = next; activationTargetRevision = layoutRevision;
          pages.forEach(function (page, index) { page.setAttribute('data-reader-active', index === pageIndex ? 'true' : 'false'); });
          syncMotion();
          var serial = ++authorVisualSerial;
          authorVisualPending = true;
          emitHook('pageChange', {page: pages[pageIndex], pageIndex: pageIndex, pageCount: pages.length}).then(twoFrames).then(function () {
            if (!active || serial !== authorVisualSerial) return;
            authorVisualPending = false; postState();
          }).catch(fail);
        }
        visualRevision++; postState();
      });
    }, {passive: true});
    return stage;
  }

  async function renderFlow(content, viewportNode, prepared, breakToken, alignBottom, extra) {
    var layout = new Paged.Layout(viewportNode);
    installSourceBreaks(layout, prepared);
    // CSS hyphenation may paint hyphens without changing mapped source text.
    layout.hyphenateAtBreak = function () {};
    layout.hooks.onOverflow.register(function (overflow, rendered, bounds, paginator) {
      if (fitFirstLineMetrics(rendered, bounds, alignBottom)) overflow = paginator.findOverflow(rendered, bounds);
      var complete = completeContentOverflow(rendered, bounds);
      return complete && (!overflow || complete.compareBoundaryPoints(Range.START_TO_START, overflow) < 0) ? complete : overflow;
    });
    layout.waitForImages = function (images) {
      return Promise.all(Array.prototype.map.call(images, function (image) { return imageReady(image, true); }));
    };
    var box = viewportNode.getBoundingClientRect();
    var bounds = {top: box.top, bottom: box.bottom + extra, left: box.left, right: box.right, width: box.width, height: box.height + extra};
    return layout.renderTo(content, prepared.source, breakToken, bounds);
  }
  function flowContinues(token, content, prepared) {
    if (!token) return false;
    var node = token.node.nodeType === Node.ELEMENT_NODE ? token.node : token.node.parentElement;
    if (!node.closest('p.reader-paragraph')) return false;
    var paragraphs = content.querySelectorAll('p.reader-paragraph'), last = paragraphs[paragraphs.length - 1];
    var original = last && prepared.elements.get(last.getAttribute('data-ref'));
    if (original && original.contains(token.node) && last.textContent.trim()) return true;
    if (!token.offset && window.LegadoPageAlignment.forcedBreak(node, prepared.source, true)) return false;
    return !last || !window.LegadoPageAlignment.forcedBreak(last, content, false);
  }
  function canFitFlow(content) {
    if (content.querySelector('figure,picture,table,pre,blockquote,svg,video,canvas,iframe,object,embed,math,ruby')) return false;
    return Array.prototype.every.call(content.querySelectorAll('img'), function (image) {
      return image.matches('.legado-text-inline-image,.legado-text-bubble') || image.closest('.legado-text-image-frame');
    });
  }
  async function paginate(stage, expected, countHint, paintInsetsHint) {
    var prepared = prepareLayoutSource(), breakToken, previousPosition;
    var list = [], contents = [], started = performance.now(), lastYield = started;
    var paintInsets = paintInsetsHint || {top: 0, bottom: 0}, needsRefit = false;
    for (var index = 0; ; index++) {
      checkRun(expected);
      if (index >= 2048 || performance.now() - started > 45000) throw new Error('模板分页超过处理上限，请检查正文区域尺寸或改用基础排版');
      var page = document.createElement('div');
      page.className = 'reader-template-page pagedjs_page';
      page.setAttribute('data-reader-page', index === 0 ? 'first' : 'other');
      page.setAttribute('data-reader-page-index', String(index));
      page.setAttribute('data-reader-motion', 'settled');
      page.setAttribute('data-reader-entry', 'pending');
      // A template may contain a complete HTML document or a fragment. Keep its
      // body structure and head resources; scripts are explicitly activated below.
      var parsed = new DOMParser().parseFromString(String(index === 0 ? template.firstPageHtml : template.otherPageHtml), 'text/html');
      Array.prototype.forEach.call(parsed.head.childNodes, function (node) {
        if (node.nodeType !== Node.ELEMENT_NODE || !/^(TITLE|META|BASE)$/.test(node.tagName)) page.appendChild(document.importNode(node, true));
      });
      Array.prototype.forEach.call(parsed.body.childNodes, function (node) { page.appendChild(document.importNode(node, true)); });
      Array.prototype.forEach.call(parsed.body.attributes, function (attribute) {
        if (!/^(class|style)$/.test(attribute.name)) page.setAttribute(attribute.name, attribute.value);
        else if (attribute.name === 'class') page.className += ' ' + attribute.value;
        else page.style.cssText += attribute.value;
      });
      trackStyles(page);
      stage.appendChild(page); list.push(page);
      bindFields(page, index, countHint);
      await waitStyles(page);
      await runScripts(page);
      await emitHook('beforePage', {page: page, pageIndex: index, first: index === 0});
      checkRun(expected);
      var slots = orderedSlots(page);
      if (!slots.length) throw new Error('模板页面缺少 data-reader-flow 正文区域');
      for (var slotIndex = 0; slotIndex < slots.length; slotIndex++) {
        var slot = slots[slotIndex], box = slotBox(slot);
        if (box.width < 2 || box.height < 2) throw new Error('第 ' + (index + 1) + ' 页的正文区域没有可用尺寸');
        await loadFonts(slot);
        checkRun(expected);
        // The inner viewport makes author padding/borders independent of Paged's
        // scroll-overflow calculation. Each slot may have unrelated dimensions.
        var viewportNode = document.createElement('div'), content = document.createElement('div');
        viewportNode.className = 'reader-template-flow-viewport';
        viewportNode.style.width = box.width + 'px'; viewportNode.style.height = box.height + 'px';
        viewportNode.style.setProperty('--reader-flow-height', box.height + 'px');
        content.className = 'reader-template-flow-content';
        viewportNode.appendChild(content); slot.replaceChildren(viewportNode);
        var alignBottom = !scrollMode && init.bottomJustify !== false && window.LegadoPageAlignment &&
          slot.getAttribute('data-reader-bottom-align') !== 'false';
        var result = await renderFlow(content, viewportNode, prepared, breakToken, alignBottom, 0);
        checkRun(expected);
        if (result.error) throw new Error('正文无法放入第 ' + (index + 1) + ' 页的区域：' + result.error.message);
        var nextPosition = tokenPosition(result.breakToken, prepared.order);
        if (!progressed(previousPosition, nextPosition)) throw new Error('模板分页停在同一正文位置，已终止以避免空白页循环');
        var alignment = null, fitExtra = 0, continues = false;
        if (alignBottom) {
          var alignmentBounds = viewportNode.getBoundingClientRect();
          var paintBounds = flowPaintBounds(content, page, alignmentBounds);
          continues = flowContinues(result.breakToken, content, prepared);
          alignment = window.LegadoPageAlignment.align(content, {bounds: alignmentBounds,
            paintBounds: paintBounds, paintInsets: paintInsets, continues: continues,
            selector: 'p.reader-paragraph', lastPage: result.breakToken ? 1 : 0});
          if (continues && !alignment.reason && !alignment.rejectedPages.length && canFitFlow(content) &&
              alignment.pages.some(function (part) { return part.filledBottom && part.gapAdjustment > Math.max(2, part.lineAdvance * .06); })) {
            var baselineContent = content, baselineResult = result, baselineAlignment = alignment;
            for (var trial = 0; trial < 2; trial++) {
              var extra = Math.floor(box.height * (.04 / Math.pow(2, trial)) * 64) / 64;
              var candidateContent = document.createElement('div'); candidateContent.className = 'reader-template-flow-content';
              viewportNode.replaceChildren(candidateContent);
              var candidateResult = await renderFlow(candidateContent, viewportNode, prepared, breakToken, alignBottom, extra);
              checkRun(expected);
              var candidatePosition = tokenPosition(candidateResult.breakToken, prepared.order);
              if (candidateResult.error || !progressed(nextPosition, candidatePosition) || !canFitFlow(candidateContent)) continue;
              var candidateContinues = flowContinues(candidateResult.breakToken, candidateContent, prepared);
              var candidateAlignment = window.LegadoPageAlignment.align(candidateContent, {bounds: alignmentBounds,
                paintBounds: paintBounds, paintInsets: paintInsets, continues: candidateContinues, boundedFit: true,
                selector: 'p.reader-paragraph'});
              if (!candidateAlignment.reason && !candidateAlignment.rejectedPages.length && candidateAlignment.alignedPages &&
                  !completeContentOverflow(candidateContent, alignmentBounds, paintBounds) &&
                  candidateAlignment.maxGapAdjustment <= baselineAlignment.maxGapAdjustment + .1 &&
                  candidateAlignment.spacingCost < baselineAlignment.spacingCost - .0001) {
                content = candidateContent; result = candidateResult; alignment = candidateAlignment;
                nextPosition = candidatePosition; continues = candidateContinues; fitExtra = extra; break;
              }
            }
            if (!fitExtra) { content = baselineContent; result = baselineResult; alignment = baselineAlignment; viewportNode.replaceChildren(content); }
          }
          if (alignment.paintInsets) paintInsets = alignment.paintInsets;
        }
        previousPosition = nextPosition; breakToken = result.breakToken;
        contents.push({pageIndex: index, node: content, paintInsets: alignment && alignment.paintInsets, fitExtra: fitExtra, continues: continues});
        if (!breakToken) break;
      }
      await emitHook('afterPage', {page: page, pageIndex: index, first: index === 0});
      await Promise.all(Array.prototype.map.call(page.querySelectorAll('img'), function (image) { return imageReady(image, true); }));
      await waitBackgrounds(page);
      checkRun(expected);
      if (!breakToken) break;
      if (performance.now() - lastYield > 12) {
        await new Promise(function (resolve) { later(resolve, 0); }); lastYield = performance.now();
      }
    }
    // Match Direct's chapter-wide text edges. A decoration appearing on a later
    // page must not make only that page's first/last text line jump inward.
    contents.forEach(function (entry) {
      var used = entry.paintInsets;
      if (!used || used.top >= paintInsets.top && used.bottom >= paintInsets.bottom) return;
      var bounds = entry.node.parentElement.getBoundingClientRect();
      // Do not repair an afterPage hook that changed already paginated content;
      // the final overflow check must still reject that invalid author layout.
      var paintBounds = flowPaintBounds(entry.node, list[entry.pageIndex], bounds);
      if (completeContentOverflow(entry.node, bounds, paintBounds)) return;
      var aligned = window.LegadoPageAlignment.align(entry.node, {bounds: bounds,
        paintBounds: paintBounds, paintInsets: paintInsets, continues: entry.continues,
        boundedFit: entry.fitExtra > 0, selector: 'p.reader-paragraph'});
      if (aligned.rejectedPages && aligned.rejectedPages.length) needsRefit = true;
    });
    return {pages: list, contents: contents, paintInsets: paintInsets, needsRefit: needsRefit};
  }
  function pageForOffset(offset, map) {
    var nearest = 0, distance = Infinity;
    for (var index = 0; index < map.length; index++) {
      var fragments = map[index].fragments;
      for (var part = 0; part < fragments.length; part++) {
        var range = fragments[part];
        if (range.start <= offset && offset < range.end) return index;
        var candidateDistance = Math.min(Math.abs(range.start - offset), Math.abs(range.end - offset));
        if (candidateDistance < distance) { distance = candidateDistance; nearest = index; }
      }
    }
    return nearest;
  }
  function showPage(index) {
    setMotionState('settled');
    pageIndex = Math.max(0, Math.min(pages.length - 1, Math.floor(Number(index) || 0)));
    pages.forEach(function (page, candidate) {
      page.setAttribute('data-reader-active', candidate === pageIndex ? 'true' : 'false');
      page.setAttribute('aria-hidden', scrollMode || candidate === pageIndex ? 'false' : 'true');
      page.inert = !scrollMode && candidate !== pageIndex;
    });
    syncMotion();
    if (scrollMode && committedRoot) committedRoot.scrollTop = pageIndex * viewport.height;
    activationTargetRevision = layoutRevision;
    visualRevision++;
  }
  function restorePage(anchor, fallback) {
    if (activationBoundary === 'start') return 0;
    if (activationBoundary === 'end') return pages.length - 1;
    if (anchor) {
      for (var index = 0; index < pageMap.length; index++) {
        var found = pageMap[index].fragments.some(function (fragment) {
          return fragment.id === anchor.id && fragment.start <= anchor.start &&
            (fragment.end > anchor.start || fragment.start === fragment.end);
        });
        if (found) return index;
      }
      return pageForOffset(anchor.start, pageMap);
    }
    return fallback;
  }
  function sourceMutation(mutations) {
    if (sourcePreparing) return;
    if (mutations.every(function (mutation) {
      return mutation.type === 'attributes' && /^(data-reader-display-block|data-reader-display-image)$/.test(mutation.attributeName || '');
    })) return;
    requestLayout('source');
  }
  function connectObservers() {
    if (pageObserver) pageObserver.disconnect();
    if (sizeObserver) sizeObserver.disconnect();
    geometry = geometryOf(pages);
    if (window.ResizeObserver) {
      sizeObserver = new ResizeObserver(function () {
        if (!active || layoutBusy || !pages.length) return;
        if (geometryOf(pages) !== geometry) requestLayout('region-resize');
      });
      pages.forEach(function (page) { orderedSlots(page).forEach(function (slot) { sizeObserver.observe(slot); }); });
    }
    if (window.MutationObserver && committedRoot) {
      pageObserver = new MutationObserver(function (mutations) {
        var relevant = false, flowChanged = false;
        mutations.forEach(function (mutation) {
          var element = mutation.target.nodeType === Node.ELEMENT_NODE ? mutation.target : mutation.target.parentElement;
          if (!element || element.closest('[data-reader-field]')) return;
          if (element.classList.contains('reader-template-page') && /^(data-reader-active|data-reader-motion|data-reader-entry|aria-hidden|inert)$/.test(mutation.attributeName || '')) return;
          relevant = true;
          if (element.closest('.reader-template-flow-content,style,link[rel~="stylesheet"]')) flowChanged = true;
        });
        if (!relevant || layoutBusy) return;
        if (flowChanged) { requestLayout('template-flow-mutation'); return; }
        // A button counter, clock, or author animation in the page shell need
        // not rerun the entire chapter when every flow region keeps its shape.
        var serial = ++authorVisualSerial;
        authorVisualPending = true; visualRevision++; postState();
        clearLater(mutationTimer);
        mutationTimer = later(async function () {
          try {
            await Promise.all(Array.prototype.map.call(pages[pageIndex].querySelectorAll('img'), function (image) { return imageReady(image, true); }));
            await twoFrames();
            if (serial !== authorVisualSerial || !active) return;
            authorVisualPending = false;
            if (geometryOf(pages) !== geometry) requestLayout('template-shell-geometry'); else postState();
          } catch (error) { fail(error); }
        }, 0);
      });
      pageObserver.observe(committedRoot, {subtree: true, childList: true, characterData: true, attributes: true});
    }
  }
  function notifyLayoutWaiters(error) {
    layoutWaiters.splice(0).forEach(function (waiter) { if (error) waiter.reject(error); else waiter.resolve(); });
  }
  function waitForLayout() {
    if (failure) return Promise.reject(failure);
    if (!active) return Promise.reject(new Error('模板页面已关闭'));
    if (initialized && !layoutBusy) return Promise.resolve();
    return new Promise(function (resolve, reject) { layoutWaiters.push({resolve: resolve, reject: reject}); });
  }
  function requestLayout(reason) {
    if (!active || failure) return;
    // Source/resource updates can safely wait in the detached source while a
    // real selection still points at the committed DOM. Replacing it mid-copy
    // would discard the selection and its native action-mode ownership.
    if (committedRoot && selectionVisible() && reason !== 'viewport') { selectionDeferredLayout = true; return; }
    if (reason === 'viewport' && selectionVisible()) clearSelection();
    generation++; requested = true; layoutBusy = true;
    setMotionState('settled');
    postState();
    if (!initialized || running || layoutTimer) return;
    layoutTimer = later(function () { layoutTimer = 0; layoutLoop(); }, reason === 'source-image' ? 32 : 16);
  }
  async function layoutLoop() {
    if (running || !active || failure) return;
    running = true;
    var loopStarted = performance.now();
    while (active && requested && !failure) {
      if (performance.now() - loopStarted > 60000) { fail(new Error('模板持续改变布局，未能在限定时间内稳定')); break; }
      requested = false;
      var expected = generation;
      var anchor = pageMap[pageIndex] && pageMap[pageIndex].fragments[0], fallback = pageIndex;
      var stage = null;
      try {
        sourcePreparing = true;
        await emitHook('beforeLayout', {generation: expected, viewport: viewport});
        await drainAuthorWork();
        var sourceSnapshot = ReaderTemplateSourceMap.capture(source, String(init.plainText || ''), records);
        if (sourceObserver) sourceObserver.takeRecords();
        sourcePreparing = false;
        checkRun(expected);
        var countHint = pages.length || 0, result, map, converged = false, paintInsetsHint;
        for (var pass = 0; pass < 4; pass++) {
          if (stage) stage.remove();
          stage = createStage(); candidateRoot = stage;
          result = await paginate(stage, expected, countHint, paintInsetsHint);
          paintInsetsHint = result.paintInsets;
          if (result.needsRefit) { countHint = result.pages.length; continue; }
          var before = geometryOf(result.pages);
          result.pages.forEach(function (page, index) { bindFields(page, index, result.pages.length); });
          await emitHook('afterLayout', {pages: result.pages, pageCount: result.pages.length, generation: expected});
          await twoFrames();
          checkRun(expected);
          var after = geometryOf(result.pages);
          if (before === after) { converged = true; break; }
          countHint = result.pages.length;
        }
        if (!converged) throw new Error('页眉或页数持续改变正文区域尺寸，模板分页无法稳定');
        result.contents.forEach(function (entry) {
          var bounds = entry.node.parentElement.getBoundingClientRect();
          if (completeContentOverflow(entry.node, bounds, entry.paintInsets && flowPaintBounds(entry.node, result.pages[entry.pageIndex], bounds))) {
            throw new Error('模板在分页后改变了正文尺寸，部分文字或图片超出区域；请在 beforePage 中设置正文样式');
          }
          revealFlowArtwork(entry.node, result.pages[entry.pageIndex]);
        });
        map = sourceSnapshot.collect(result.contents, result.pages.length);
        checkRun(expected);
        if (pageObserver) pageObserver.disconnect();
        if (sizeObserver) sizeObserver.disconnect();
        var previous = committedRoot;
        pages = result.pages; pageMap = map; committedRoot = stage; candidateRoot = null;
        committedRoot.setAttribute('data-reader-runtime-stage', 'committed');
        if (scrollMode) pages.forEach(function (page, index) { page.style.top = (index * viewport.height) + 'px'; page.style.bottom = 'auto'; page.style.height = viewport.height + 'px'; });
        layoutRevision++; visualRevision++;
        showPage(restorePage(anchor, fallback));
        if (previous) previous.remove();
        stage = null;
        await twoFrames();
        checkRun(expected);
        resourcesReady = sourceImagesPending === 0; layoutBusy = false; authorVisualPending = false;
        connectObservers();
        syncMotion();
        checkRun(expected);
        postState(null, true);
        if (sourceImagesPending === 0) post('stable');
        notifyLayoutWaiters();
      } catch (error) {
        sourcePreparing = false;
        if (stage) stage.remove();
        candidateRoot = null;
        if (error !== CANCELLED) fail(error);
      }
    }
    running = false;
    if (active && requested && !failure && !layoutTimer) layoutTimer = later(function () { layoutTimer = 0; layoutLoop(); }, 16);
  }
  function visible(element) {
    for (var node = element; node && node.nodeType === Node.ELEMENT_NODE; node = node.parentElement) {
      var style = getComputedStyle(node);
      if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse' || parseFloat(style.opacity) <= .01) return false;
    }
    return true;
  }
  function viewportRect(rect) { return rect.width > .5 && rect.height > .5 && rect.right > 0 && rect.bottom > 0 && rect.left < viewport.width && rect.top < viewport.height; }
  function viewportRenderable() {
    var page = pages[pageIndex];
    if (!page || layoutBusy || visualBusy || authorVisualPending) return false;
    // Full author HTML is renderable too: a chapter with just a custom heading,
    // a canvas, or an image must not be mistaken for an empty body flow.
    var roots = [page];
    for (var index = 0; index < roots.length; index++) {
      var nodes = textNodes(roots[index]);
      for (var part = 0; part < nodes.length; part++) {
        var node = nodes[part];
        if (!node.data.trim() || !visible(node.parentElement)) continue;
        var range = document.createRange(); range.selectNodeContents(node);
        var rects = range.getClientRects();
        for (var rectIndex = 0; rectIndex < rects.length; rectIndex++) if (viewportRect(rects[rectIndex])) return true;
      }
      var images = roots[index].querySelectorAll('img,svg,canvas,video,table,hr,iframe,object,button,input');
      for (var imageIndex = 0; imageIndex < images.length; imageIndex++) {
        var image = images[imageIndex];
        if (image.tagName === 'IMG' && (!image.complete || !image.naturalWidth)) continue;
        if (visible(image) && viewportRect(image.getBoundingClientRect())) return true;
      }
      var elements = [page].concat(Array.prototype.slice.call(page.querySelectorAll('*')));
      for (var backgroundIndex = 0; backgroundIndex < elements.length; backgroundIndex++) {
        var element = elements[backgroundIndex];
        if (!visible(element) || !viewportRect(element.getBoundingClientRect())) continue;
        var backgrounds = getComputedStyle(element).backgroundImage;
        var found = false;
        backgroundPixels.forEach(function (url) { if (backgrounds.indexOf(url) >= 0) found = true; });
        if (found) return true;
      }
    }
    return false;
  }
  function metrics() {
    var target = activationBoundary === 'start' ? 0 : activationBoundary === 'end' ? pages.length - 1 : pageIndex;
    var pendingResources = sourceImagesPending > 0;
    var ready = initialized && !failure && !layoutBusy && !visualBusy && !authorVisualPending && !pendingResources;
    var renderable = ready && viewportRenderable();
    return {pageCount: Math.max(1, pages.length), pageIndex: Math.max(0, pageIndex), ready: ready,
      resourcesReady: resourcesReady && !pendingResources, resourcesFailed: resourcesFailed, layoutRevision: layoutRevision,
      visualRevision: visualRevision, layoutPending: layoutBusy || visualBusy || authorVisualPending || pendingResources, sourceImagesPending: sourceImagesPending,
      activationTargetRevision: activationTargetRevision, activationTargetSatisfied: ready && activationTargetRevision === layoutRevision && pageIndex === target,
      renderable: renderable, viewportRenderable: renderable};
  }
  function postState(requestId, includePages) {
    var value = {metrics: metrics(), offset: pageMap[pageIndex] ? pageMap[pageIndex].start : 0};
    if (Number.isSafeInteger(requestId)) value.requestId = requestId;
    if (includePages) value.pages = pageMap;
    post('state', value);
  }
  function fail(error) {
    if (!active || failure) return;
    failure = error instanceof Error ? error : new Error(String(error || '模板渲染失败'));
    layoutBusy = false; visualBusy = false; requested = false;
    clearLater(layoutTimer); layoutTimer = 0;
    notifyLayoutWaiters(failure);
    post('error', {message: failure.message});
  }

  async function dispatch(command) {
    var args = Array.isArray(command.args) ? command.args : [], method = command.method;
    if (method === 'setToken') {
      setMotionState('settled');
      token = args[0]; imageSequence = 0; lastImageTap = null; lastImageAction = 0;
      closeImageOverlay(); clearSelection(); finishInteraction();
    } else if (command.token !== token) return;
    if (method === 'dismissAnnotation') closeImageOverlay();
    await waitForLayout();
    if (!active || failure) return;
    if (method === 'setTemplateMotionState') {
      setMotionState(String(args[0]));
      // A finite compositor barrier for a requested pose, never the completion
      // of an infinite CSS animation or author-created animation Promise.
      await twoFrames();
      if (active && !failure) postState(command.requestId);
      return;
    }
    visualBusy = true;
    try {
      if (method === 'setPage' || method === 'setActivationPage') {
        activationBoundary = method === 'setActivationPage' && /^(start|end)$/.test(String(args[0])) ? args[0] : '';
        var target = activationBoundary === 'start' ? 0 : activationBoundary === 'end' ? pages.length - 1 :
          method === 'setActivationPage' ? args[1] : args[0];
        showPage(target);
        await emitHook('pageChange', {page: pages[pageIndex], pageIndex: pageIndex, pageCount: pages.length});
      } else if (method === 'setReaderChromeData') {
        var before = geometryOf(pages);
        Object.keys(fields).forEach(function (key) { delete fields[key]; });
        Object.assign(fields, args[0] || {});
        pages.forEach(function (page, index) { bindFields(page, index, pages.length); });
        await emitHook('fieldsChange', {fields: fields});
        await twoFrames();
        if (geometryOf(pages) !== before) requestLayout('field-geometry'); else visualRevision++;
      } else if (method === 'goToFragment') {
        var id = String(args[0] || '').replace(/^#/, ''), match = /^__legado_text_(\d+)$/.exec(id), found = -1;
        if (match) found = pageForOffset(Number(match[1]), pageMap);
        else for (var index = 0; index < pageMap.length; index++) {
          if (pageMap[index].fragments.some(function (entry) { return entry.id === id; })) { found = index; break; }
        }
        if (found >= 0) {
          activationBoundary = ''; showPage(found);
          await emitHook('pageChange', {page: pages[pageIndex], pageIndex: pageIndex, pageCount: pages.length});
        }
      } else if (method === 'clearSelection') clearSelection();
      else if (method === 'setTextImageMode') { textImageMode = String(args[0]); lastImageTap = null; }
      else if (method !== 'report' && method !== 'setToken' && method !== 'dismissAnnotation') throw new Error('未知模板命令：' + method);
      // Commands are serialized, including report. No later report may release
      // an earlier setPage/setToken before its actual DOM and image frame commit.
      for (;;) {
        await waitForLayout();
        var expected = generation;
        await twoFrames();
        if (!layoutBusy && generation === expected) break;
      }
    } finally { visualBusy = false; }
    if (active && !failure) postState(command.requestId, method === 'setToken');
  }
  window.addEventListener('message', function (event) {
    if (!active || event.source !== parent) return;
    var command = event.data;
    if (!command || command.channel !== channel || command.type !== 'command') return;
    commandChain = commandChain.then(function () { return dispatch(command); }).catch(fail);
  });

  function resourceUrl(raw) {
    try {
      var url = new URL(raw, init.baseUrl), base = new URL(init.baseUrl);
      return /^https?:$/.test(url.protocol) && url.origin === base.origin && /^\/text-image\//.test(url.pathname) ? url.href : null;
    } catch (_) { return null; }
  }
  function imageState(url) {
    return new Promise(function (resolve) {
      var xhr = new XMLHttpRequest(), settled = false;
      sourceRequests.add(xhr);
      function finish(value) { if (settled) return; settled = true; sourceRequests.delete(xhr); resolve(value); }
      xhr.open('GET', url + '/state', true); xhr.timeout = 3000;
      xhr.onload = function () { var value = null; if (xhr.status === 200) { try { value = JSON.parse(xhr.responseText); } catch (_) {} } finish(value); };
      xhr.onerror = xhr.ontimeout = xhr.onabort = function () { finish(null); };
      try { xhr.send(); } catch (_) { finish(null); }
    });
  }
  async function resolveSourceImage(image) {
    var url = resourceUrl(image.getAttribute('data-legado-image-resource')), started = Date.now(), loadingSince = 0, attempt = 0;
    function markFailed() {
      image.setAttribute('data-legado-image-state', 'failed');
      image.setAttribute('aria-label', '图片加载失败'); image.setAttribute('title', '图片加载失败，刷新章节可重试');
      image.src = failedImage; resourcesFailed = true;
    }
    if (!url) { markFailed(); return; }
    while (active && !failure && source.contains(image)) {
      if (Date.now() - started > 120000 || (loadingSince && Date.now() - loadingSince > 30000)) { markFailed(); return; }
      var state = await imageState(url);
      if (!active || failure) return;
      if (state && state.state === 'pending') {
        if (state.queued === true) loadingSince = 0; else if (!loadingSince) loadingSince = Date.now();
        await new Promise(function (resolve) { later(resolve, Math.min(750, 100 + (attempt++) * 100)); });
        continue;
      }
      if (!state || state.state !== 'ready') { markFailed(); return; }
      var bubble = state.bubble === true, scale = bubble ? Number(state.scale) : 1;
      image.classList.toggle('legado-text-bubble', bubble);
      image.style.fontSize = scale >= .5 && scale <= 1.5 && scale !== 1 ? (scale * 100) + '%' : '';
      image.src = url;
      var decoded = await imageReady(image, false);
      if (decoded) {
        image.setAttribute('data-legado-image-state', 'ready'); image.removeAttribute('aria-label'); return;
      }
      // Native cache entries can expire between /state and the body fetch.
      var retry = await imageState(url);
      if (retry && retry.state === 'ready') { image.src = url + '?retry=1'; decoded = await imageReady(image, false); }
      if (decoded) { image.setAttribute('data-legado-image-state', 'ready'); image.removeAttribute('aria-label'); }
      else markFailed();
      return;
    }
  }
  function startSourceImages() {
    var images = Array.prototype.slice.call(source.querySelectorAll('img[data-legado-image-resource]')), next = 0;
    sourceImagesPending = images.length;
    var workers = [];
    async function worker() {
      while (active && !failure && next < images.length) {
        var image = images[next++];
        try { await resolveSourceImage(image); } catch (_) { resourcesFailed = true; }
        if (!active) return;
        sourceImagesPending = Math.max(0, sourceImagesPending - 1);
        requestLayout('source-image');
      }
    }
    for (var index = 0; index < Math.min(4, images.length); index++) workers.push(worker());
    postState();
    return Promise.all(workers);
  }
  function clearSelection() { var selection = getSelection(); if (selection) selection.removeAllRanges(); post('selection', {text: '', rects: [], viewportWidth: viewport.width, viewportHeight: viewport.height}); }
  var selectionTimer = 0;
  document.addEventListener('selectionchange', function () {
    clearLater(selectionTimer);
    selectionTimer = later(function () {
      var selection = getSelection(), text = selection ? selection.toString() : '', rects = [];
      if (selection && text) for (var index = 0; index < selection.rangeCount; index++) {
        Array.prototype.forEach.call(selection.getRangeAt(index).getClientRects(), function (rect) {
          if (viewportRect(rect)) rects.push({left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom});
        });
      }
      post('selection', {text: text, rects: rects, viewportWidth: viewport.width, viewportHeight: viewport.height});
      if (!text && selectionDeferredLayout) { selectionDeferredLayout = false; requestLayout('selection-end'); }
    }, 20);
  });
  function imageTarget(target) {
    var element = target && target.nodeType === Node.ELEMENT_NODE ? target : target && target.parentElement;
    if (!element || !element.closest || element.closest('#legado-epub-image-overlay')) return null;
    var image = element.closest('img'), action = element.closest('a[data-legado-image-action]');
    if (!image && action) image = action.querySelector('img[data-legado-image-id]');
    return image && committedRoot && committedRoot.contains(image) ? image : null;
  }
  function closeImageOverlay() {
    if (imageOverlayClose) imageOverlayClose();
  }
  function openImageOverlay(url) {
    if (!imageOverlayNode) {
      var overlay = document.createElement('div'); overlay.id = 'legado-epub-image-overlay';
      overlay.setAttribute('role', 'dialog'); overlay.setAttribute('aria-label', '查看图片');
      var image = document.createElement('img'); image.alt = '';
      var button = document.createElement('button'); button.type = 'button'; button.textContent = '关闭'; button.setAttribute('aria-label', '关闭图片');
      overlay.appendChild(image); overlay.appendChild(button);
      var scale = 1, startDistance = 0, startX = 0, startY = 0, panX = 0, panY = 0, moved = false;
      function apply() { image.style.transform = 'translate(' + panX + 'px,' + panY + 'px) scale(' + scale + ')'; }
      imageOverlayClose = function () {
        if (!overlay.classList.contains('legado-visible')) return;
        overlay.classList.remove('legado-visible'); image.removeAttribute('src');
        scale = 1; panX = panY = 0; apply(); finishInteraction(); post('annotationState', {visible: false});
      };
      button.addEventListener('click', function (event) { event.preventDefault(); event.stopPropagation(); imageOverlayClose(); });
      overlay.addEventListener('click', function (event) {
        event.preventDefault(); event.stopPropagation();
        if (!moved) imageOverlayClose();
      });
      overlay.addEventListener('touchstart', function (event) {
        moved = false;
        if (event.touches.length === 2) {
          var dx = event.touches[0].clientX - event.touches[1].clientX, dy = event.touches[0].clientY - event.touches[1].clientY;
          startDistance = Math.sqrt(dx * dx + dy * dy);
        } else if (event.touches.length === 1) {
          startX = event.touches[0].clientX; startY = event.touches[0].clientY;
        }
      }, {passive: true});
      overlay.addEventListener('touchmove', function (event) {
        if (event.touches.length === 2 && startDistance > 0) {
          var dx = event.touches[0].clientX - event.touches[1].clientX, dy = event.touches[0].clientY - event.touches[1].clientY;
          var distance = Math.sqrt(dx * dx + dy * dy);
          scale = Math.max(1, Math.min(4, scale * distance / startDistance)); startDistance = distance;
          moved = true; apply(); event.preventDefault();
        } else if (event.touches.length === 1 && scale > 1.02) {
          var x = event.touches[0].clientX, y = event.touches[0].clientY;
          panX += x - startX; panY += y - startY; startX = x; startY = y;
          moved = true; apply(); event.preventDefault();
        }
      }, {passive: false});
      overlay.addEventListener('touchend', function () { startDistance = 0; }, {passive: true});
      overlay.addEventListener('touchcancel', function () { startDistance = 0; moved = true; }, {passive: true});
      document.documentElement.appendChild(overlay); imageOverlayNode = overlay;
    }
    clearSelection();
    imageOverlayNode.querySelector('img').src = url;
    imageOverlayNode.classList.add('legado-visible');
    post('annotationState', {visible: true});
  }
  function sendImage(image) {
    var url = image && (image.currentSrc || image.src);
    if (url) { openImageOverlay(url); post('image', {url: url}); }
  }
  function clearImagePress() { if (imagePress) clearLater(imagePress.timer); imagePress = null; }
  function reportInteraction(value) {
    if (!touch || touch.active === value) return;
    touch.active = value; post('embeddedInteraction', {interactionId: touch.id, active: value});
  }
  function finishInteraction() {
    reportInteraction(false);
    touch = null; clearImagePress();
  }
  function interactiveTarget(element) {
    if (!element || !element.closest) return null;
    var hard = !!element.closest('#legado-epub-image-overlay,video,audio,button,input,select,textarea,summary,label,iframe,embed,object,[contenteditable]:not([contenteditable="false"]),[role="button"],[role="slider"],[role="spinbutton"],[role="textbox"],[draggable="true"]');
    var image = imageTarget(element), sourceImage = image && image.hasAttribute('data-legado-image-id');
    // Generated source-image anchors own taps, never the entire drag starting at
    // DOWN. This matches the ordinary reader's bubble/page-turn gesture contract.
    if (!sourceImage && element.closest('a[href],[onclick]')) hard = true;
    var scrollers = [], node = element;
    while (node && node !== committedRoot && node !== document.documentElement) {
      var style = getComputedStyle(node), overflowX = Math.max(0, node.scrollWidth - node.clientWidth), overflowY = Math.max(0, node.scrollHeight - node.clientHeight);
      var x = /^(auto|scroll|overlay)$/.test(style.overflowX) && overflowX > 1;
      var y = /^(auto|scroll|overlay)$/.test(style.overflowY) && overflowY > 1;
      if (x || y) scrollers.push({node: node, rtl: style.direction === 'rtl', x: x, y: y, overflowX: overflowX, overflowY: overflowY});
      node = node.parentElement;
    }
    return hard || scrollers.length ? {hard: hard, scrollers: scrollers} : null;
  }
  function scrollableForGesture(entry, dx, dy) {
    if (Math.abs(dx) > Math.abs(dy) && entry.x) {
      var minimum = entry.rtl ? -entry.overflowX : 0, maximum = entry.rtl ? 0 : entry.overflowX;
      return -dx < 0 ? entry.node.scrollLeft > minimum + 1 : -dx > 0 && entry.node.scrollLeft < maximum - 1;
    }
    if (Math.abs(dy) >= Math.abs(dx) && entry.y) return -dy < 0 ? entry.node.scrollTop > 1 : -dy > 0 && entry.node.scrollTop < entry.overflowY - 1;
    return false;
  }
  function overlayVisible() { return !!(imageOverlayNode && imageOverlayNode.classList.contains('legado-visible')); }
  function selectionVisible() { var selection = getSelection(); return !!(selection && !selection.isCollapsed && selection.toString()); }
  document.addEventListener('touchstart', function (event) {
    finishInteraction(); scrollTouch = null;
    if (!event.isTrusted || event.touches.length !== 1) return;
    var element = event.target.nodeType === Node.ELEMENT_NODE ? event.target : event.target.parentElement;
    var image = imageTarget(element), point = event.touches[0];
    var interactive = interactiveTarget(element);
    if (interactive) {
      touch = {id: ++interactionSequence, x: point.clientX, y: point.clientY, active: false, hard: interactive.hard, scrollers: interactive.scrollers};
      if (interactive.hard) reportInteraction(true);
    }
    if (scrollMode && committedRoot && !overlayVisible() && !selectionVisible() && !(interactive && interactive.hard)) {
      activationBoundary = '';
      scrollTouch = {x: point.clientX, y: point.clientY, top: committedRoot.scrollTop <= 1,
        bottom: committedRoot.scrollTop >= committedRoot.scrollHeight - committedRoot.clientHeight - 1};
    }
    if (image) {
      imagePress = {image: image, x: point.clientX, y: point.clientY, opened: false};
      var state = imagePress;
      state.timer = later(function () {
        if (imagePress !== state) return;
        state.opened = true; suppressImageClickUntil = Date.now() + 700; sendImage(image);
      }, 500);
    }
  }, {capture: true, passive: true});
  document.addEventListener('touchmove', function (event) {
    var point = event.touches[0];
    if (touch && !touch.hard && point) {
      var dx = point.clientX - touch.x, dy = point.clientY - touch.y;
      if (Math.max(Math.abs(dx), Math.abs(dy)) >= 4) reportInteraction(touch.scrollers.some(function (entry) { return scrollableForGesture(entry, dx, dy); }));
    }
    if (imagePress && (!point || Math.abs(point.clientX - imagePress.x) >= 12 || Math.abs(point.clientY - imagePress.y) >= 12)) clearImagePress();
    if (event.touches.length !== 1) scrollTouch = null;
  }, {capture: true, passive: true});
  document.addEventListener('touchend', function (event) {
    var opened = imagePress && imagePress.opened;
    var boundaryTouch = scrollTouch, owned = touch && touch.active, point = event.changedTouches[0];
    scrollTouch = null;
    if (event.isTrusted && boundaryTouch && point && !opened && !owned && !overlayVisible() && !selectionVisible() && !layoutBusy) {
      var dx = point.clientX - boundaryTouch.x, dy = point.clientY - boundaryTouch.y;
      if (Math.abs(dy) > 48 && Math.abs(dy) > Math.abs(dx) * 1.2) {
        var top = committedRoot.scrollTop <= 1, bottom = committedRoot.scrollTop >= committedRoot.scrollHeight - committedRoot.clientHeight - 1;
        if (dy > 0 && boundaryTouch.top && top) post('boundary', {direction: -1});
        else if (dy < 0 && boundaryTouch.bottom && bottom) post('boundary', {direction: 1});
      }
    }
    finishInteraction();
    if (opened) { suppressImageClickUntil = Date.now() + 700; event.preventDefault(); event.stopPropagation(); }
  }, {capture: true, passive: false});
  document.addEventListener('touchcancel', function () { scrollTouch = null; finishInteraction(); }, {capture: true, passive: true});
  window.addEventListener('blur', finishInteraction);
  document.addEventListener('contextmenu', function (event) {
    var image = imageTarget(event.target);
    if (!image || !event.isTrusted) return;
    event.preventDefault(); event.stopPropagation();
    if (Date.now() < suppressImageClickUntil) return;
    suppressImageClickUntil = Date.now() + 700; clearImagePress(); sendImage(image);
  }, true);
  document.addEventListener('click', function (event) {
    var image = imageTarget(event.target);
    if (image && Date.now() < suppressImageClickUntil) { event.preventDefault(); event.stopPropagation(); return; }
    var action = image && image.closest('a[data-legado-image-action]');
    if (image && (action || textImageMode === '1')) {
      event.preventDefault(); event.stopPropagation();
      if (!event.isTrusted || textImageMode === '3' || layoutBusy || visualBusy) return;
      if (textImageMode === '1') { sendImage(image); return; }
      var id = action && action.getAttribute('data-legado-image-action');
      if (!/^image-\d+$/.test(id || '') || !imageIds.has(id)) return;
      var now = Date.now();
      if (textImageMode === '4') {
        var previous = lastImageTap;
        lastImageTap = {id: id, at: now, page: pageIndex, revision: layoutRevision};
        if (!previous || previous.id !== id || now - previous.at > 300 || previous.page !== pageIndex || previous.revision !== layoutRevision) return;
        lastImageTap = null;
      }
      if (now - lastImageAction < 300) return;
      lastImageAction = now; post('sourceImage', {imageId: id, sequence: ++imageSequence}); return;
    }
    // Author click handlers run normally. Only navigation itself is delegated to
    // the host; preventDefault is observed after the event has reached the target.
    var element = event.target.nodeType === Node.ELEMENT_NODE ? event.target : event.target.parentElement;
    var anchor = element && element.closest('a[href]');
    if (anchor && !event.defaultPrevented) {
      var href = anchor.getAttribute('href') || '';
      // JavaScript URLs execute normally in the sandbox and never cross the
      // Android navigation bridge. Author preventDefault is respected as well.
      if (!/^\s*javascript:/i.test(href)) { event.preventDefault(); post('link', {url: anchor.href}); }
    }
  });

  window.addEventListener('resize', function () { updateViewport(); requestLayout('viewport'); });
  document.addEventListener('visibilitychange', syncMotion);
  if (reducedMotion) {
    if (reducedMotion.addEventListener) reducedMotion.addEventListener('change', syncMotion);
    else if (reducedMotion.addListener) reducedMotion.addListener(syncMotion);
  }
  window.addEventListener('error', function (event) {
    if (event.error || event.message) fail(new Error('模板脚本错误：' + (event.message || event.error.message)));
  });
  window.addEventListener('unhandledrejection', function (event) { fail(new Error('模板异步脚本错误：' + String(event.reason && event.reason.message || event.reason))); });
  window.addEventListener('pagehide', function () {
    setMotionState('paused');
    // Disposal callbacks are cleanup, not pagination prerequisites. Their errors
    // must not keep a detached document or a pending command alive.
    (hooks.dispose || []).splice(0).forEach(function (callback) {
      try { Promise.resolve(callback({page: pages[pageIndex] || null}, api)).catch(function () {}); } catch (_) {}
    });
    lastMotionPage = null;
    if (reducedMotion) {
      if (reducedMotion.removeEventListener) reducedMotion.removeEventListener('change', syncMotion);
      else if (reducedMotion.removeListener) reducedMotion.removeListener(syncMotion);
    }
    finishInteraction(); active = false; generation++;
    timers.forEach(clearTimeout); timers.clear();
    sourceRequests.forEach(function (xhr) { xhr.abort(); }); sourceRequests.clear();
    if (sourceObserver) sourceObserver.disconnect(); if (pageObserver) pageObserver.disconnect(); if (sizeObserver) sizeObserver.disconnect();
    if (headObserver) headObserver.disconnect();
    notifyLayoutWaiters(new Error('模板页面已关闭'));
  }, {once: true});

  post('boot');
  (async function () {
    if (!window.Paged || typeof Paged.Layout !== 'function') throw new Error('模板分页组件未加载');
    if (!window.ReaderTemplateSourceMap) throw new Error('模板正文位置组件未加载');
    if (!template.firstPageHtml || !template.otherPageHtml) throw new Error('模板缺少首页或续页 HTML');
    updateViewport();
    var base = document.createElement('base'); base.href = init.baseUrl || location.href; document.head.prepend(base);
    document.body.setAttribute('data-legado-text-reader', 'true');
    document.body.setAttribute('data-reader-scroll', scrollMode ? 'true' : 'false');
    appendStyle('html,body{margin:0;width:100%;height:100%;overflow:hidden;}[data-reader-runtime-stage]{position:fixed;inset:0;overflow:hidden;}[data-reader-runtime-stage="pending"]{opacity:0!important;z-index:-1;pointer-events:none!important;}'+
      'body[data-reader-scroll="true"] [data-reader-runtime-stage="committed"]{overflow-y:auto;overflow-x:hidden;overscroll-behavior:contain;}'+
      '.reader-template-page{position:absolute;inset:0;width:100%;height:100%;box-sizing:border-box;overflow:hidden;}'+
      'body[data-reader-scroll="false"] [data-reader-runtime-stage="committed"]>.reader-template-page[data-reader-active="false"]{visibility:hidden;pointer-events:none;}'+
      '.reader-template-flow-viewport{display:flow-root;position:relative;overflow:hidden;box-sizing:content-box;min-width:0;min-height:0;}'+
      '.reader-template-flow-content{display:flow-root;box-sizing:border-box;width:100%;min-width:0;}'+
      '.reader-template-flow-content img{max-width:100%;max-height:calc(var(--reader-flow-height) - 1em);object-fit:contain;}'+
      '.reader-template-flow-content figure{margin:.4em 0;}'+
      '.reader-template-flow-content [data-split-from]{margin-top:0;text-indent:0;}[data-reader-field]{font-variant-numeric:tabular-nums;}'+
      '#legado-epub-image-overlay{position:fixed!important;inset:0!important;z-index:2147483647!important;display:none;align-items:center;justify-content:center;background:rgba(0,0,0,.94);overflow:hidden;touch-action:none;}'+
      '#legado-epub-image-overlay.legado-visible{display:flex;}#legado-epub-image-overlay>img{max-width:100%;max-height:100%;width:auto;height:auto;object-fit:contain;transform-origin:center;}'+
      '#legado-epub-image-overlay>button{position:absolute;right:12px;top:12px;font-size:16px;padding:8px 14px;color:white;background:#333;border:1px solid #999;border-radius:6px;}', 'reader-template-runtime-style');
    appendStyle(init.baseCss, 'reader-template-base-style');
    prepareSource();
    appendStyle(template.css, 'reader-template-author-style');
    if (template.javascript) {
      var script = document.createElement('script'); script.textContent = String(template.javascript); document.head.appendChild(script);
    }
    await drainAuthorWork();
    if (failure) throw failure;
    await waitStyles(document.head);
    await waitBackgrounds(document.body);
    await Promise.all(Array.prototype.map.call(source.querySelectorAll('img'), function (image) { return imageReady(image, true); }));
    if (window.MutationObserver) {
      sourceObserver = new MutationObserver(sourceMutation);
      sourceObserver.observe(source, {subtree: true, childList: true, characterData: true, attributes: true});
      headObserver = new MutationObserver(function (mutations) {
        if (mutations.some(function (mutation) {
          var element = mutation.target.nodeType === Node.ELEMENT_NODE ? mutation.target : mutation.target.parentElement;
          return element && (element.tagName === 'STYLE' || element.tagName === 'LINK' || mutation.type === 'childList');
        })) {
          waitStyles(document.head).then(function () { requestLayout('author-stylesheet'); }).catch(fail);
        }
      });
      headObserver.observe(document.head, {subtree: true, childList: true, characterData: true, attributes: true});
    }
    if (document.fonts && document.fonts.addEventListener) {
      document.fonts.addEventListener('loadingdone', function () { if (!layoutBusy) requestLayout('fonts'); });
      document.fonts.addEventListener('loadingerror', function () { resourcesFailed = true; if (!layoutBusy) requestLayout('fonts'); });
    }
    // Metadata can change both bubble width and its image pixels. Resolve it
    // before the first pagination, so neither ready/stable nor a command ack can
    // expose a placeholder page whose geometry will change immediately afterward.
    await startSourceImages();
    if (!active || failure) return;
    initialized = true;
    requestLayout('initial');
  })().catch(fail);
})();
