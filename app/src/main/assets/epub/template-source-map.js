(function () {
  'use strict';

  // A snapshot describes the author's current source, not the unmodified book.
  // Paged may split that DOM into many regions, but must preserve this snapshot's
  // text and image instances, in order. Canonical offsets are a separate concern.
  // Only attributes are added; wrappers would change author CSS and DOM selectors.
  var BLOCK = 'data-reader-display-block', IMAGE = 'data-reader-display-image';
  var blockIdentities = new WeakMap(), imageIdentities = new WeakMap(), sequence = 0;
  var TEXT = 3, ELEMENT = 1, WALK_CONTENT = 5;

  function integrity(reason, detail) {
    var error = new Error('模板分页未完整保留本轮正文（' + detail + '）');
    error.code = 'READER_TEMPLATE_PAGINATION_INTEGRITY';
    error.reason = reason;
    return error;
  }
  function identity(node, registry, prefix, attribute) {
    var id = registry.get(node);
    if (!id) { id = prefix + (++sequence); registry.set(node, id); }
    // Cloning source is allowed. A clone gets its own identity even if it has
    // copied a marker from an earlier snapshot.
    if (node.getAttribute(attribute) !== id) node.setAttribute(attribute, id);
    return id;
  }
  function isSurrogateBoundary(text, index) {
    return index > 0 && index < text.length &&
      text.charCodeAt(index - 1) >= 0xd800 && text.charCodeAt(index - 1) <= 0xdbff &&
      text.charCodeAt(index) >= 0xdc00 && text.charCodeAt(index) <= 0xdfff;
  }
  function lowerBoundary(text, index) {
    index = Math.max(0, Math.min(text.length, Math.floor(index)));
    return isSurrogateBoundary(text, index) ? index - 1 : index;
  }
  function projector(original, display) {
    var prefix = 0, suffix = 0, common = Math.min(original.length, display.length);
    while (prefix < common && original.charCodeAt(prefix) === display.charCodeAt(prefix)) prefix++;
    if (isSurrogateBoundary(original, prefix) || isSurrogateBoundary(display, prefix)) prefix--;
    while (suffix < common - prefix &&
        original.charCodeAt(original.length - suffix - 1) === display.charCodeAt(display.length - suffix - 1)) suffix++;
    if (isSurrogateBoundary(original, original.length - suffix) ||
        isSurrogateBoundary(display, display.length - suffix)) suffix--;
    var originalSuffix = original.length - suffix, displaySuffix = display.length - suffix;
    return function (index, end) {
      var mapped;
      // Surviving prefix/suffix text keeps exact positions. A changed middle is
      // interpolated in O(1), avoiding a quadratic diff on long chapters. At a
      // deletion, the next fragment starts after it and the prior one ends before it.
      if (index < prefix || (end && index === prefix)) mapped = index;
      else if (index > displaySuffix || (!end && index === displaySuffix)) {
        mapped = originalSuffix + index - displaySuffix;
      } else if (displaySuffix === prefix) mapped = end ? prefix : originalSuffix;
      else mapped = prefix + (index - prefix) * (originalSuffix - prefix) / (displaySuffix - prefix);
      return lowerBoundary(original, mapped);
    };
  }
  function state(parent, node, owner, group) {
    var tag = String(node.localName || '').toLowerCase();
    var resource = tag === 'script' || tag === 'style' || tag === 'noscript';
    return {
      owner: owner,
      group: group,
      ignoreText: parent.ignoreText || resource || node.hasAttribute('data-reader-text-ignore') ||
        node.hasAttribute('data-legado-image-action'),
      ignoreImages: parent.ignoreImages || resource
    };
  }
  function walk(root, initial, visitElement, visitText) {
    var document = root.ownerDocument || root;
    var states = new WeakMap(), walker = document.createTreeWalker(root, WALK_CONTENT), node;
    states.set(root, initial);
    while ((node = walker.nextNode())) {
      var parent = states.get(node.parentNode) || initial;
      if (node.nodeType === ELEMENT) states.set(node, visitElement(node, parent));
      else if (node.nodeType === TEXT && !parent.ignoreText && node.data) visitText(node.data, parent);
    }
  }

  function capture(source, plainText, canonicalRecords) {
    if (!source || typeof source.cloneNode !== 'function' || typeof plainText !== 'string' ||
        !canonicalRecords || typeof canonicalRecords.forEach !== 'function') {
      throw integrity('invalid-source', '正文位置输入无效');
    }
    var originals = new Map(), firstOriginal = null;
    canonicalRecords.forEach(function (value, key) {
      if (!value || typeof value.id !== 'string' || key !== value.id || typeof value.text !== 'string' ||
          !Number.isSafeInteger(value.start) || !Number.isSafeInteger(value.end) ||
          value.start < 0 || value.end < value.start || value.end > plainText.length ||
          (value.canonical && (plainText.slice(value.start, value.start + value.text.length) !== value.text ||
            value.end !== value.start + value.text.length + 1 || plainText.charAt(value.end - 1) !== '\n'))) {
        throw integrity('invalid-canonical-map', '原文位置标记无效');
      }
      var record = {id: value.id, start: value.start, end: value.end, text: value.text, canonical: !!value.canonical};
      originals.set(record.id, record);
      if (!firstOriginal) firstOriginal = record;
    });
    var events = [], groups = [], blockIds = new Set(), imageIds = new Set(), textLength = 0;
    var rootState = {owner: 'reader-display-root', group: null, ignoreText: false, ignoreImages: false};
    blockIds.add(rootState.owner);

    function addElement(node, parent) {
      var owner = identity(node, blockIdentities, 'reader-display-', BLOCK), group = parent.group;
      blockIds.add(owner);
      if (node.hasAttribute('data-reader-block')) {
        group = {record: originals.get(node.getAttribute('data-reader-block')) || null, length: 0, parts: []};
        groups.push(group);
      }
      var entry = state(parent, node, owner, group);
      if (!entry.ignoreImages && String(node.localName).toLowerCase() === 'img') {
        var id = identity(node, imageIdentities, 'reader-image-', IMAGE);
        imageIds.add(id);
        events.push({kind: 'image', id: id, group: group, offset: group ? group.length : 0});
      }
      return entry;
    }
    function addText(text, parent) {
      var group = parent.group;
      events.push({kind: 'text', owner: parent.owner, text: text, group: group, offset: group ? group.length : 0});
      textLength += text.length;
      if (group) { group.parts.push(text); group.length += text.length; }
    }
    if (source.nodeType === ELEMENT) rootState = addElement(source, rootState);
    walk(source, rootState, addElement, addText);
    groups.forEach(function (group) {
      if (group.record && group.record.canonical) group.project = projector(group.record.text, group.parts.join(''));
      group.parts = null;
    });

    function fragment(event, start, end, text) {
      var group = event.group, record = group && group.record;
      if (!record) return {id: event.anchor.id, start: event.anchor.offset, end: event.anchor.offset};
      if (!record.canonical) return {id: record.id, start: record.start, end: record.start};
      var from = event.offset + start, to = event.offset + end;
      var startOffset = record.start + group.project(from, false);
      return {
        id: record.id,
        start: startOffset,
        // Only text ending its paragraph owns the canonical newline. Images are
        // zero-width anchors even when placed after the paragraph's final letter.
        end: !text ? startOffset : to === group.length ? record.end : record.start + group.project(to, true)
      };
    }
    // New blocks without an original ID inherit the preceding surviving anchor,
    // or the next one when inserted at the beginning. This works after deletions
    // and reordering and never invents character offsets for author-added text.
    var next = null;
    for (var index = events.length - 1; index >= 0; index--) {
      var event = events[index];
      if (event.group && event.group.record) {
        var known = fragment(event, 0, event.kind === 'text' ? event.text.length : 0, event.kind === 'text');
        event.known = known;
        next = {id: known.id, offset: known.start};
      } else event.nextAnchor = next;
    }
    var previous = null, defaultAnchor = {id: firstOriginal ? firstOriginal.id : 'reader-template', offset: 0};
    events.forEach(function (event) {
      if (event.known) previous = {id: event.known.id, offset: event.known.end};
      else event.anchor = previous || event.nextAnchor || defaultAnchor;
      delete event.nextAnchor;
      delete event.known;
    });

    function collect(contents, pageCount) {
      if (!Array.isArray(contents) || !Number.isSafeInteger(pageCount) || pageCount < 1 || pageCount > 2048) {
        throw integrity('invalid-pages', '分页结果无效');
      }
      var pages = Array.from({length: pageCount}, function () { return {start: 0, end: 0, fragments: []}; });
      var at = 0, used = 0, lastPage = -1, seenImages = new Set();

      function append(page, part) {
        var last = page.fragments[page.fragments.length - 1];
        if (last && last.id === part.id && last.end === part.start) last.end = part.end;
        else page.fragments.push(part);
      }
      function textIn(page, text, owner) {
        var offset = 0;
        while (offset < text.length) {
          var expected = events[at];
          if (!expected || expected.kind !== 'text' || expected.owner !== owner) {
            throw integrity('content-order', '正文顺序改变或出现额外文字');
          }
          var length = Math.min(text.length - offset, expected.text.length - used);
          if (text.slice(offset, offset + length) !== expected.text.slice(used, used + length)) {
            throw integrity('text-changed', '分页文字缺失、重复或被更改');
          }
          append(page, fragment(expected, used, used + length, true));
          offset += length; used += length;
          if (used === expected.text.length) { at++; used = 0; }
        }
      }
      function imageIn(page, node) {
        var id = node.getAttribute(IMAGE), expected = events[at];
        if (!id || !imageIds.has(id)) throw integrity('unexpected-image', '出现不属于本轮正文的图片');
        if (seenImages.has(id)) throw integrity('duplicate-image', '同一张正文图片出现多次');
        if (!expected || expected.kind !== 'image' || expected.id !== id || used) {
          throw integrity('content-order', '图片顺序改变或前面的正文缺失');
        }
        seenImages.add(id);
        append(page, fragment(expected, 0, 0, false));
        at++;
      }
      contents.forEach(function (entry) {
        if (!entry || !entry.node || !Number.isSafeInteger(entry.pageIndex) ||
            entry.pageIndex < lastPage || entry.pageIndex < 0 || entry.pageIndex >= pageCount) {
          throw integrity('invalid-pages', '正文区域的页面顺序无效');
        }
        lastPage = entry.pageIndex;
        var page = pages[entry.pageIndex];
        var initial = {owner: rootState.owner, group: null, ignoreText: false, ignoreImages: false};
        function readElement(node, parent) {
          var owner = node.getAttribute(BLOCK) || parent.owner;
          if (!blockIds.has(owner)) throw integrity('unknown-block', '正文区域包含过期的位置标记');
          var child = state(parent, node, owner, null);
          if (!child.ignoreImages && String(node.localName).toLowerCase() === 'img') imageIn(page, node);
          return child;
        }
        if (entry.node.nodeType === ELEMENT) initial = readElement(entry.node, initial);
        walk(entry.node, initial, readElement, function (text, parent) { textIn(page, text, parent.owner); });
      });
      if (at !== events.length || used || seenImages.size !== imageIds.size) {
        throw integrity(events[at] && events[at].kind === 'image' ? 'missing-image' : 'missing-text', '部分正文或图片没有进入页面');
      }
      var cursor = 0;
      pages.forEach(function (page) {
        if (!page.fragments.length) { page.start = cursor; page.end = cursor; return; }
        page.start = page.fragments[0].start;
        page.end = page.fragments.reduce(function (end, part) { return Math.max(end, part.end); }, page.start);
        cursor = page.end;
      });
      return pages;
    }
    return Object.freeze({collect: collect, textLength: textLength, imageCount: imageIds.size});
  }
  window.ReaderTemplateSourceMap = Object.freeze({capture: capture});
})();
