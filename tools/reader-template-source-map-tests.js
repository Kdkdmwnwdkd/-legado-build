(function () {
  'use strict';

  // Browser-DOM regression cases. Deliberately use known offsets and damaged
  // pagination outputs as the oracle, not a second copy of the projection code.
  window.runReaderTemplateSourceMapTests = function () {
    var results = [], api = window.ReaderTemplateSourceMap;
    function equal(actual, expected) {
      if (JSON.stringify(actual) !== JSON.stringify(expected)) {
        throw new Error('expected ' + JSON.stringify(expected) + ', got ' + JSON.stringify(actual));
      }
    }
    function assert(value, message) { if (!value) throw new Error(message); }
    function reject(callback, reason) {
      var error;
      try { callback(); } catch (caught) { error = caught; }
      assert(error && error.code === 'READER_TEMPLATE_PAGINATION_INTEGRITY', 'expected integrity failure');
      if (reason) equal(error.reason, reason);
    }
    function test(name, callback) {
      var start = performance.now();
      try { results.push({name: name, passed: true, detail: callback(), milliseconds: performance.now() - start}); }
      catch (error) { results.push({name: name, passed: false, error: String(error.stack || error), milliseconds: performance.now() - start}); }
    }
    function book(texts) {
      var source = document.createDocumentFragment(), records = new Map(), plain = '';
      texts.forEach(function (text, index) {
        var id = 'block-' + index, node = document.createElement('p'), start = plain.length;
        node.setAttribute('data-reader-block', id);
        node.setAttribute('data-reader-kind', 'paragraph');
        node.setAttribute('data-legado-text-offset', String(start));
        node.textContent = text;
        source.appendChild(node);
        plain += text + '\n';
        records.set(id, {id: id, kind: 'paragraph', start: start, end: plain.length, text: text, canonical: true});
      });
      return {source: source, records: records, plain: plain};
    }
    function capture(fixture) { return api.capture(fixture.source, fixture.plain, fixture.records); }
    function region(nodes, pageIndex) {
      var node = document.createElement('div');
      nodes.forEach(function (part) { node.appendChild(part.cloneNode(true)); });
      return {pageIndex: pageIndex || 0, node: node};
    }
    function whole(fixture) { return region([fixture.source], 0); }
    function slice(node, from, to) {
      var nodes = [], walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT), current;
      while ((current = walker.nextNode())) nodes.push(current);
      function point(offset) {
        for (var index = 0; index < nodes.length; index++) {
          if (offset <= nodes[index].data.length) return [nodes[index], offset];
          offset -= nodes[index].data.length;
        }
        throw new Error('fixture slice is outside text');
      }
      var start = point(from), end = point(to), range = document.createRange(), clone = node.cloneNode(false);
      range.setStart(start[0], start[1]); range.setEnd(end[0], end[1]);
      clone.appendChild(range.cloneContents());
      return clone;
    }
    function ranges(pages) { return pages.map(function (page) { return [page.start, page.end]; }); }
    function picture(id) {
      var image = document.createElement('img');
      if (id) image.setAttribute('data-legado-image-id', id);
      image.setAttribute('src', 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" width="2" height="2"/%3E');
      return image;
    }

    test('unchanged Unicode paragraphs across three pages and two regions', function () {
      var fixture = book(['甲乙丙丁', '海😀山']), snapshot = capture(fixture);
      var a = fixture.source.childNodes[0], b = fixture.source.childNodes[1];
      var pages = snapshot.collect([
        region([slice(a, 0, 2)], 0),
        region([slice(a, 2, 4)], 1),
        region([slice(b, 0, 1)], 1),
        region([slice(b, 1, 4)], 2)
      ], 3);
      equal(ranges(pages), [[0, 2], [2, 6], [6, 10]]);
      equal(pages[1].fragments, [{id: 'block-0', start: 2, end: 5}, {id: 'block-1', start: 5, end: 6}]);
    });
    test('inline nested spans preserve exact original positions', function () {
      var fixture = book(['AB😀CD']), node = fixture.source.firstChild;
      node.innerHTML = 'A<span>B<em>😀</em>C</span>D';
      var snapshot = capture(fixture);
      equal(ranges(snapshot.collect([
        region([slice(node, 0, 2)], 0), region([slice(node, 2, 4)], 1), region([slice(node, 4, 6)], 2)
      ], 3)), [[0, 2], [2, 4], [4, 7]]);
    });
    test('author insertion maps added characters without shifting surviving suffix', function () {
      var fixture = book(['abcdef']), node = fixture.source.firstChild;
      node.textContent = 'abc++def';
      var snapshot = capture(fixture);
      equal(ranges(snapshot.collect([
        region([slice(node, 0, 3)], 0), region([slice(node, 3, 5)], 1), region([slice(node, 5, 8)], 2)
      ], 3)), [[0, 3], [3, 3], [3, 7]]);
    });
    test('author deletion preserves both sides of the removed canonical gap', function () {
      var fixture = book(['abcXYZdef']), node = fixture.source.firstChild;
      node.textContent = 'abcdef';
      var snapshot = capture(fixture);
      equal(ranges(snapshot.collect([
        region([slice(node, 0, 3)], 0), region([slice(node, 3, 6)], 1)
      ], 2)), [[0, 3], [6, 10]]);
    });
    test('changed middle preserves exact prefix and suffix anchors', function () {
      var fixture = book(['开头ABCDEFG结尾']), node = fixture.source.firstChild;
      node.textContent = '开头改后的文字结尾';
      var snapshot = capture(fixture);
      equal(ranges(snapshot.collect([
        region([slice(node, 0, 2)], 0), region([slice(node, 2, 7)], 1), region([slice(node, 7, 9)], 2)
      ], 3)), [[0, 2], [2, 9], [9, 12]]);
    });
    test('reordered, deleted, inserted and copied source blocks are legal', function () {
      var fixture = book(['甲甲', '乙乙', '丙丙']), a = fixture.source.childNodes[0], c = fixture.source.childNodes[2];
      var added = document.createElement('p'); added.textContent = '作者新增说明';
      fixture.source.replaceChildren(c, added, a, c.cloneNode(true));
      var snapshot = capture(fixture);
      var entries = Array.from(fixture.source.childNodes).map(function (node, index) { return region([node], index); });
      equal(ranges(snapshot.collect(entries, 4)), [[6, 9], [9, 9], [0, 3], [6, 9]]);
      assert(new Set(Array.from(fixture.source.children).map(function (node) {
        return node.getAttribute('data-reader-display-block');
      })).size === 4, 'source copies must get separate display identities');
    });
    test('nested original blocks do not count their inner text twice', function () {
      var fixture = book(['AB', 'CD']), a = fixture.source.childNodes[0], b = fixture.source.childNodes[1];
      a.replaceChildren(document.createTextNode('A'), b, document.createTextNode('B'));
      var snapshot = capture(fixture), pages = snapshot.collect([whole(fixture)], 1);
      equal(pages[0].fragments, [
        {id: 'block-0', start: 0, end: 1}, {id: 'block-1', start: 3, end: 6}, {id: 'block-0', start: 1, end: 3}
      ]);
      equal(ranges(pages), [[0, 6]]);
    });
    test('bare and new unmarked text uses neighboring canonical anchors', function () {
      var fixture = book(['甲乙']), first = fixture.source.firstChild;
      fixture.source.insertBefore(document.createTextNode('新增开头'), first);
      fixture.source.appendChild(document.createTextNode('新增结尾'));
      var added = document.createElement('p'); added.textContent = '尾注'; fixture.source.appendChild(added);
      var snapshot = capture(fixture);
      var entries = Array.from(fixture.source.childNodes).map(function (node, index) { return region([node], index); });
      equal(ranges(snapshot.collect(entries, 4)), [[0, 0], [0, 3], [3, 3], [3, 3]]);
    });
    test('source can replace every canonical node with author content', function () {
      var fixture = book(['原文']), node = document.createElement('section'); node.textContent = '作者完全改写';
      fixture.source.replaceChildren(node);
      var snapshot = capture(fixture);
      equal(ranges(snapshot.collect([whole(fixture)], 1)), [[0, 0]]);
    });
    test('capture does not wrap nodes and is mutation-idempotent', function () {
      var fixture = book(['ABC']), node = fixture.source.firstChild;
      node.innerHTML = 'A<span>B</span>C';
      var nodes = Array.from(node.childNodes), snapshot = capture(fixture);
      equal(Array.from(node.childNodes).map(function (child, index) { return child === nodes[index]; }), [true, true, true]);
      var observer = new MutationObserver(function () {});
      observer.observe(fixture.source, {attributes: true, subtree: true, childList: true, characterData: true});
      var second = capture(fixture);
      equal(observer.takeRecords().length, 0);
      observer.disconnect();
      equal(snapshot.collect([whole(fixture)], 1), second.collect([whole(fixture)], 1));
    });
    test('page-only inline wrapping and normalized text nodes remain lossless', function () {
      var fixture = book(['abcdef']), snapshot = capture(fixture), output = whole(fixture), node = output.node.firstChild;
      var middle = node.firstChild.splitText(2), tail = middle.splitText(2), span = document.createElement('span');
      node.insertBefore(span, tail); span.appendChild(middle);
      equal(ranges(snapshot.collect([output], 1)), [[0, 7]]);
    });
    test('deleted source images are allowed and copied image IDs become unique instances', function () {
      var fixture = book(['前后']), node = fixture.source.firstChild, original = picture('image-0');
      node.replaceChildren(document.createTextNode('前'), original, document.createTextNode('后'));
      capture(fixture);
      original.remove();
      var one = picture('image-0'); node.appendChild(one); node.appendChild(one.cloneNode(true));
      var snapshot = capture(fixture), images = node.querySelectorAll('img');
      assert(images[0].getAttribute('data-reader-display-image') !== images[1].getAttribute('data-reader-display-image'), 'duplicated source image IDs need new instance IDs');
      equal(snapshot.imageCount, 2);
      equal(ranges(snapshot.collect([whole(fixture)], 1)), [[0, 3]]);
    });
    test('image-only pages and empty-chapter placeholder retain zero-width anchors', function () {
      var fixture = book([]), figure = document.createElement('figure');
      figure.setAttribute('data-reader-block', 'photo'); figure.appendChild(picture('image-0'));
      fixture.source.appendChild(figure);
      fixture.records.set('photo', {id: 'photo', kind: 'image', start: 0, end: 0, text: '', canonical: false});
      var snapshot = capture(fixture), pages = snapshot.collect([whole(fixture)], 1);
      equal(pages[0].fragments, [{id: 'photo', start: 0, end: 0}]);
      var placeholder = document.createElement('p'); placeholder.textContent = '本章暂无正文';
      placeholder.setAttribute('data-reader-block', 'empty'); fixture.source.replaceChildren(placeholder);
      fixture.records = new Map([['empty', {id: 'empty', kind: 'placeholder', start: 0, end: 0, text: '', canonical: false}]]);
      snapshot = capture(fixture);
      equal(snapshot.collect([whole(fixture)], 1)[0].fragments, [{id: 'empty', start: 0, end: 0}]);
    });
    test('image at a deleted middle has a valid zero-width canonical anchor', function () {
      var fixture = book(['abcXYZdef']), node = fixture.source.firstChild;
      node.replaceChildren(document.createTextNode('abc'), picture('image-0'), document.createTextNode('def'));
      var snapshot = capture(fixture), before = node.cloneNode(false), only = node.cloneNode(false), after = node.cloneNode(false);
      before.appendChild(node.childNodes[0].cloneNode(true)); only.appendChild(node.childNodes[1].cloneNode(true)); after.appendChild(node.childNodes[2].cloneNode(true));
      equal(ranges(snapshot.collect([region([before], 0), region([only], 1), region([after], 2)], 3)), [[0, 3], [6, 6], [6, 10]]);
    });
    test('scripts and ignored action labels do not become book text but images still count', function () {
      var fixture = book(['前后']), node = fixture.source.firstChild, action = document.createElement('a');
      action.setAttribute('data-legado-image-action', 'image-0'); action.appendChild(picture('image-0')); action.appendChild(document.createTextNode('查看大图'));
      var style = document.createElement('style'); style.textContent = '.x{color:red}';
      var script = document.createElement('script'); script.textContent = 'throw new Error("not executed")';
      var button = document.createElement('button'); button.setAttribute('data-reader-text-ignore', ''); button.textContent = '更多';
      node.replaceChildren(document.createTextNode('前'), action, document.createTextNode('后'), style, script, button);
      var snapshot = capture(fixture), output = whole(fixture);
      output.node.querySelector('button').textContent = '作者更改按钮';
      equal(snapshot.textLength, 2); equal(snapshot.imageCount, 1);
      equal(ranges(snapshot.collect([output], 1)), [[0, 3]]);
    });
    test('complete author deletion produces a valid empty snapshot', function () {
      var fixture = book(['全部删去']), snapshot;
      fixture.source.replaceChildren(); snapshot = capture(fixture);
      equal(snapshot.textLength, 0);
      equal(snapshot.collect([whole(fixture)], 2), [{start: 0, end: 0, fragments: []}, {start: 0, end: 0, fragments: []}]);
    });
    test('one missing character is rejected even when page positions look plausible', function () {
      var fixture = book(['ABCDEF']), snapshot = capture(fixture), output = whole(fixture);
      output.node.firstChild.textContent = 'ABDEF';
      reject(function () { snapshot.collect([output], 1); }, 'text-changed');
    });
    test('a repeated character is rejected', function () {
      var fixture = book(['ABCDEF']), snapshot = capture(fixture), output = whole(fixture);
      output.node.firstChild.textContent = 'ABCCDEF';
      reject(function () { snapshot.collect([output], 1); }, 'text-changed');
    });
    test('same-text paragraphs swapped after capture are rejected', function () {
      var fixture = book(['重复句', '重复句']), snapshot = capture(fixture), output = whole(fixture);
      output.node.insertBefore(output.node.lastChild, output.node.firstChild);
      reject(function () { snapshot.collect([output], 1); }, 'content-order');
    });
    test('truncated final paragraph cannot silently commit', function () {
      var fixture = book(['ABCDEF']), snapshot = capture(fixture);
      reject(function () { snapshot.collect([region([slice(fixture.source.firstChild, 0, 4)], 0)], 1); }, 'missing-text');
    });
    test('missing final image is rejected', function () {
      var fixture = book(['ABC']); fixture.source.firstChild.appendChild(picture('image-0'));
      var snapshot = capture(fixture), output = whole(fixture); output.node.querySelector('img').remove();
      reject(function () { snapshot.collect([output], 1); }, 'missing-image');
    });
    test('pagination duplicating an image is rejected', function () {
      var fixture = book([]); fixture.source.appendChild(picture('image-0'));
      var snapshot = capture(fixture), output = whole(fixture); output.node.appendChild(output.node.firstChild.cloneNode(true));
      reject(function () { snapshot.collect([output], 1); }, 'duplicate-image');
    });
    test('image before its source text is rejected', function () {
      var fixture = book(['ABC']); fixture.source.firstChild.appendChild(picture('image-0'));
      var snapshot = capture(fixture), output = whole(fixture), p = output.node.firstChild;
      p.insertBefore(p.lastChild, p.firstChild);
      reject(function () { snapshot.collect([output], 1); }, 'content-order');
    });
    test('an output-only image cannot bypass completeness validation', function () {
      var fixture = book(['ABC']), snapshot = capture(fixture), output = whole(fixture);
      output.node.appendChild(picture('image-0'));
      reject(function () { snapshot.collect([output], 1); }, 'unexpected-image');
    });
    test('snapshot remains immutable when source or canonical records later change', function () {
      var fixture = book(['ABC']), snapshot = capture(fixture), output = whole(fixture);
      fixture.source.firstChild.textContent = 'XYZ'; fixture.records.get('block-0').start = 200;
      equal(ranges(snapshot.collect([output], 1)), [[0, 4]]);
      reject(function () { snapshot.collect([whole(fixture)], 1); }, 'text-changed');
    });
    test('stale display markers are rejected', function () {
      var fixture = book(['ABC']), snapshot = capture(fixture), output = whole(fixture);
      output.node.firstChild.setAttribute('data-reader-display-block', 'from-another-snapshot');
      reject(function () { snapshot.collect([output], 1); }, 'unknown-block');
    });
    test('invalid page order and page count are rejected', function () {
      var fixture = book(['ABC', 'DEF']), snapshot = capture(fixture), nodes = fixture.source.childNodes;
      reject(function () { snapshot.collect([region([nodes[0]], 1), region([nodes[1]], 0)], 2); }, 'invalid-pages');
      reject(function () { snapshot.collect([whole(fixture)], 0); }, 'invalid-pages');
    });
    test('malformed canonical offsets are rejected before capture changes DOM', function () {
      var fixture = book(['ABC']); fixture.records.get('block-0').start = 1;
      reject(function () { capture(fixture); }, 'invalid-canonical-map');
      equal(fixture.source.firstChild.hasAttribute('data-reader-display-block'), false);
    });
    test('proportional mapping never splits a canonical surrogate pair', function () {
      var fixture = book(['A😀😀😀Z']), node = fixture.source.firstChild;
      node.textContent = 'A12345Z';
      var snapshot = capture(fixture), entries = [];
      for (var index = 0; index < 7; index++) entries.push(region([slice(node, index, index + 1)], index));
      var pages = snapshot.collect(entries, 7), invalid = new Set([2, 4, 6]);
      pages.forEach(function (page) {
        page.fragments.forEach(function (fragment) {
          assert(!invalid.has(fragment.start) && !invalid.has(fragment.end), 'canonical surrogate pair was split');
          assert(fragment.start <= fragment.end && fragment.end <= fixture.plain.length, 'invalid projected range');
        });
      });
      equal(pages[6].start, 7); equal(pages[6].end, 9);
    });
    test('long chapter is linear-size and retains every final offset', function () {
      var text = '长段正文 ABC 😀 与样式变化不能损失位置。'.repeat(28), texts = [];
      for (var index = 0; index < 1500; index++) texts.push(String(index) + ':' + text);
      var fixture = book(texts), started = performance.now(), snapshot = capture(fixture), captured = performance.now();
      var nodes = Array.from(fixture.source.childNodes), entries = [];
      for (var offset = 0; offset < nodes.length; offset += 100) entries.push(region(nodes.slice(offset, offset + 100), offset / 100));
      var pages = snapshot.collect(entries, entries.length);
      equal(pages[0].start, 0); equal(pages[pages.length - 1].end, fixture.plain.length);
      equal(pages.reduce(function (total, page) { return total + page.fragments.length; }, 0), 1500);
      return {canonicalUtf16Length: fixture.plain.length, captureMs: captured - started, totalMs: performance.now() - started};
    });
    return {passed: results.filter(function (result) { return result.passed; }).length,
      failed: results.filter(function (result) { return !result.passed; }).length, results: results, physicalDeviceVerified: false};
  };
})();
