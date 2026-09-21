(function (bootstrap) {
  'use strict';
  var init = bootstrap.init;
  if (window.__legadoEpub && window.__legadoEpub.templateHost) {
    window.__legadoEpub.setToken(init.token);
    window.__legadoEpub.setTextImageMode(init.textImageMode);
    window.__legadoEpub.replayRuntimeTerminal(init.token);
    return;
  }
  var secret = bootstrap.secret;
  var channel = 'legado-reader-template';
  var token = init.token, serial = 0, pending = 0, acknowledged = 0, visualRevision = 0;
  var childReady = false, disposed = false, terminal = '', lastError = '';
  var pageMap = [], fields = init.fields || {}, selectionActive = false, annotationVisible = false;
  var queued = [], lastFragmentPage = -1, lastStateKey = '', imageMode = init.textImageMode;
  var motionState = 'settled';
  var cached = {pageCount: 1, pageIndex: 0, ready: false, resourcesReady: false,
    resourcesFailed: false, layoutRevision: 0, visualRevision: 0, layoutPending: true,
    sourceImagesPending: 0, activationTargetRevision: -1, activationTargetSatisfied: false,
    renderable: false, viewportRenderable: false};
  var frame = document.createElement('iframe');
  frame.title = '阅读页面模板';
  frame.setAttribute('sandbox', 'allow-scripts allow-forms');
  frame.setAttribute('referrerpolicy', 'no-referrer');

  function nativeMessage(type, value) {
    if (disposed) return;
    var bridge = window.LegadoTemplateHost;
    if (!bridge || typeof bridge.post !== 'function') return;
    var message = Object.assign({}, value || {}, {type: type, token: token});
    bridge.post(secret, JSON.stringify(message));
  }
  function metrics() {
    var value = Object.assign({}, cached);
    value.visualRevision = visualRevision;
    value.layoutPending = !!cached.layoutPending || pending > acknowledged;
    if (value.layoutPending) value.viewportRenderable = false;
    return value;
  }
  function renderState() {
    var m = metrics();
    nativeMessage('renderState', {visualRevision: m.visualRevision, layoutPending: m.layoutPending});
  }
  function report() {
    var m = metrics();
    renderState();
    if (!m.ready || m.layoutPending) return;
    nativeMessage('metrics', {pageCount: m.pageCount, pageIndex: m.pageIndex, layoutRevision: m.layoutRevision});
    var entry = pageMap[m.pageIndex];
    if (entry && Number.isFinite(entry.start)) {
      nativeMessage('textPosition', {page: m.pageIndex, revision: m.layoutRevision, offset: entry.start});
    }
  }
  function send(method, args, changesPage) {
    if (disposed) return 0;
    var id = ++serial;
    if (changesPage) {
      pending = id;
      visualRevision++;
      renderState();
    }
    var message = {channel: channel, type: 'command', method: method, args: args || [], requestId: id, token: token};
    if (childReady) frame.contentWindow.postMessage(message, '*');
    else queued.push(message);
    return id;
  }
  function flush() {
    if (childReady) return;
    childReady = true;
    queued.splice(0).forEach(function (message) { frame.contentWindow.postMessage(message, '*'); });
  }
  function terminalReport() {
    if (terminal === 'error') nativeMessage('error', {message: lastError});
    else if (terminal === 'stable' && metrics().ready && !metrics().layoutPending) nativeMessage('stable');
  }
  function boundedPage(index) { return Math.max(0, Math.min(cached.pageCount - 1, Math.floor(Number(index) || 0))); }
  function setPage(index) {
    if (selectionActive) return false;
    index = boundedPage(index);
    if (cached.pageIndex === index && !metrics().layoutPending && cached.ready) return true;
    cached.pageIndex = index;
    cached.activationTargetRevision = -1;
    cached.activationTargetSatisfied = false;
    send('setPage', [index], true);
    return true;
  }
  function pageForOffset(offset) {
    offset = Math.max(0, Math.min(init.plainText.length, Number(offset) || 0));
    // The fragments come from canonical source positions, not the author's chrome.
    // Prefer a range containing the character, then its nearest following range.
    var following = -1, previous = 0;
    for (var p = 0; p < pageMap.length; p++) {
      var entry = pageMap[p];
      var ranges = entry.fragments || [entry];
      for (var r = 0; r < ranges.length; r++) {
        var range = ranges[r];
        if (range.start <= offset && offset < range.end) return p;
        if (range.start <= offset) previous = p;
        if (following < 0 && range.start > offset) following = p;
      }
    }
    return following >= 0 ? following : previous;
  }
  function fragmentPage(id) {
    var match = /^__legado_text_(\d+)$/.exec(id);
    if (match) return pageForOffset(Number(match[1]));
    for (var p = 0; p < pageMap.length; p++) {
      var fragments = pageMap[p].fragments || [];
      for (var f = 0; f < fragments.length; f++) if (fragments[f].id === id) return p;
    }
    return -1;
  }
  function normalText(value) {
    var text = '', raw = [], space = false;
    for (var i = 0; i < value.length; i++) {
      var c = value.charAt(i);
      if (c === '\u200b' || c === '\ufeff') continue;
      if (/\s|\u00a0/.test(c)) { space = text.length > 0; continue; }
      if (space) { text += ' '; raw.push(i); space = false; }
      text += c; raw.push(i);
    }
    return {text: text, raw: raw};
  }
  var spokenIndex = null;
  window.addEventListener('message', function (event) {
    if (disposed || event.source !== frame.contentWindow) return;
    var message = event.data;
    if (!message || message.channel !== channel) return;
    // Promotion can advance the host token before srcdoc has installed its listener.
    if (message.type === 'boot') { flush(); return; }
    if (message.token !== token) return;
    flush();
    if (message.type === 'motionState' && /^(settled|running|paused)$/.test(message.state)) {
      motionState = message.state;
      return;
    }
    if (message.type === 'state' && message.metrics) {
      var incoming = message.metrics;
      if (Object.prototype.hasOwnProperty.call(message, 'requestId') &&
          (!Number.isSafeInteger(message.requestId) || message.requestId < 1 ||
           message.requestId > serial || message.requestId < acknowledged)) return;
      if (!Number.isSafeInteger(incoming.pageCount) || incoming.pageCount < 1 || incoming.pageCount > 100000 ||
          !Number.isSafeInteger(incoming.pageIndex) || incoming.pageIndex < 0 || incoming.pageIndex >= incoming.pageCount ||
          !Number.isSafeInteger(incoming.layoutRevision) || incoming.layoutRevision < cached.layoutRevision) return;
      if (Number.isSafeInteger(message.requestId)) acknowledged = Math.max(acknowledged, message.requestId);
      // An older command's acknowledgement cannot make a newer target captureable.
      if (pending > acknowledged) {
        cached.ready = incoming.ready === true;
        cached.resourcesReady = incoming.resourcesReady === true;
        cached.sourceImagesPending = incoming.sourceImagesPending || 0;
        if (Array.isArray(message.pages)) pageMap = message.pages;
        renderState();
        return;
      }
      var key = JSON.stringify(incoming);
      if (key !== lastStateKey) { visualRevision++; lastStateKey = key; }
      cached = Object.assign({}, incoming);
      if (Array.isArray(message.pages)) pageMap = message.pages;
      report();
      if (cached.ready && !cached.layoutPending && terminal !== 'error') terminal = 'stable';
      terminalReport();
      return;
    }
    if (message.type === 'stable') { terminal = 'stable'; terminalReport(); return; }
    if (message.type === 'error') {
      terminal = 'error';
      lastError = String(message.message || '模板渲染失败').slice(0, 2000);
      cached.ready = false; cached.layoutPending = false;
      pending = acknowledged;
      terminalReport(); return;
    }
    if (message.type === 'selection') {
      selectionActive = !!message.text;
      nativeMessage('selection', {text: String(message.text || ''), rects: message.rects || [],
        viewportWidth: message.viewportWidth, viewportHeight: message.viewportHeight}); return;
    }
    if (message.type === 'annotationState') {
      annotationVisible = message.visible === true;
      nativeMessage('annotationState', {visible: annotationVisible});
      return;
    }
    if (message.type === 'embeddedInteraction') {
      nativeMessage('embeddedInteraction', {interactionId: message.interactionId, active: message.active === true}); return;
    }
    var m = metrics();
    if (!m.ready || m.layoutPending) return;
    if (message.type === 'boundary') {
      if (init.scrollMode && !selectionActive && !annotationVisible &&
          (message.direction === -1 || message.direction === 1) &&
          (message.direction < 0 ? m.pageIndex === 0 : m.pageIndex === m.pageCount - 1)) {
        nativeMessage('boundary', {direction: message.direction});
      }
    } else if (message.type === 'sourceImage') {
      nativeMessage('sourceImage', {page: m.pageIndex, revision: m.layoutRevision,
        imageId: message.imageId, sequence: message.sequence});
    } else if (message.type === 'link' || message.type === 'image') {
      nativeMessage(message.type, {url: message.url});
    }
  });
  window.__legadoEpub = {
    templateHost: true,
    get token() { return token; },
    lastFragmentPage: function () { return lastFragmentPage; },
    get commandRevision() { return pending; },
    get committedCommandRevision() { return acknowledged; },
    setToken: function (next) {
      if (token === next) return;
      token = next; selectionActive = false; annotationVisible = false;
      send('setToken', [next], true);
    },
    metrics: metrics,
    report: function () { report(); send('report'); },
    replayRuntimeTerminal: function (expected) { if (expected === token) { report(); terminalReport(); } },
    setPage: setPage,
    setActivationPage: function (boundary, index) {
      if (selectionActive) return false;
      var target = boundary === 'end' ? cached.pageCount - 1 : boundary === 'start' ? 0 : boundedPage(index);
      if (cached.pageIndex === target && cached.activationTargetRevision === cached.layoutRevision &&
          cached.activationTargetSatisfied && !metrics().layoutPending) return true;
      cached.pageIndex = target;
      cached.activationTargetSatisfied = false;
      send('setActivationPage', [boundary, target], true);
      return true;
    },
    setReaderChrome: function () {},
    setReaderChromeData: function (value) {
      if (JSON.stringify(fields) === JSON.stringify(value)) return;
      fields = Object.assign({}, value);
      send('setReaderChromeData', [fields], true);
    },
    setTextImageMode: function (mode) {
      if (imageMode === mode) return;
      imageMode = mode; send('setTextImageMode', [mode]);
    },
    setTemplateMotionState: function (state) {
      if (!/^(settled|running|paused)$/.test(String(state))) return false;
      if (state === motionState) return true;
      motionState = state;
      // Snapshot settling is acknowledged before native capture. Playing a
      // decoration must never invalidate that settled snapshot every frame.
      send('setTemplateMotionState', [state], state === 'settled');
      return true;
    },
    clearSelection: function () { selectionActive = false; send('clearSelection'); },
    goToFragment: function (id) {
      if (!cached.ready || !pageMap.length) return false;
      var target = fragmentPage(String(id || '').replace(/^#/, ''));
      if (target < 0) return false;
      lastFragmentPage = target; return setPage(target);
    },
    locateReadAloud: function (cue, offset, progress) {
      if (selectionActive) return {accepted: false, reason: 'selection'};
      if (!pageMap.length) return {accepted: false, reason: 'layout-pending'};
      if (!spokenIndex) spokenIndex = normalText(init.plainText);
      var normalized = normalText(String(cue || ''));
      if (!normalized.text) return {accepted: false, reason: 'empty-cue'};
      var cueOffset = 0;
      while (cueOffset + 1 < normalized.raw.length && normalized.raw[cueOffset + 1] <= offset) cueOffset++;
      var expected = Math.max(0, Math.round(Math.min(1, Math.max(0, Number(progress) || 0)) * spokenIndex.text.length) - cueOffset);
      var before = spokenIndex.text.lastIndexOf(normalized.text, expected);
      var after = spokenIndex.text.indexOf(normalized.text, expected);
      var occurrence = before < 0 ? after : after < 0 ? before : expected - before <= after - expected ? before : after;
      if (occurrence < 0) return {accepted: false, reason: 'cue-not-found'};
      var page = pageForOffset(spokenIndex.raw[occurrence + cueOffset] || 0);
      return {accepted: true, pageIndex: page, changed: page !== cached.pageIndex};
    },
    dismissAnnotation: function () {
      var wasVisible = annotationVisible;
      if (wasVisible) { annotationVisible = false; send('dismissAnnotation', [], true); }
      return wasVisible;
    },
    annotationVisible: function () { return annotationVisible; }
  };
  function scriptJson(value) {
    return JSON.stringify(value).replace(/</g, '\\u003c').replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
  }
  var origin = new URL(init.baseUrl).origin;
  frame.srcdoc = '<!doctype html><html><head><meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width,initial-scale=1"><base href="' +
    init.baseUrl.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;') + '">' +
    '</head><body data-legado-text-reader="true"><script>window.__readerTemplateInit=' + scriptJson(init) + ';</script>' +
    '<script src="' + origin + '/__reader_template__/paged.js"></script>' +
    '<script src="' + origin + '/__reader_template__/source-map.js"></script>' +
    '<script src="' + origin + '/__reader_template__/page-alignment.js"></script>' +
    '<script src="' + origin + '/__reader_template__/runtime.js"></script></body></html>';
  document.body.appendChild(frame);
  window.addEventListener('pagehide', function () { disposed = true; queued.length = 0; secret = ''; }, {once: true});
})(/*__READER_TEMPLATE_BOOTSTRAP__*/);
