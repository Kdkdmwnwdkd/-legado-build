# Paged.js 0.4.3

Upstream: https://github.com/pagedjs/pagedjs
Package: https://registry.npmjs.org/pagedjs/-/pagedjs-0.4.3.tgz
License: MIT; complete upstream license in `paged.LICENSE.txt`.

The checked-in browser bundle is upstream `dist/paged.js`. The only local patch
exports its internal `Layout` class as `Paged.Layout`; no layout algorithm is
modified. This is a pinned internal API, not a stable upstream public API. The
reader adapter supplies measurable, independently sized template flow regions
and passes each region's returned break token to the next one.

The adapter in `template-runtime.js` also uses `Layout` hooks to retain exact
clone-to-source text positions, compare zero offsets correctly, and keep complete
text/image bounds inside each region. These replace the internal substring
position lookup and print-column overflow assumptions without changing this
vendor bundle. Image waits are bounded and inserted hyphen characters are
disabled so pagination cannot alter the mapped source text.

The application does not call the automatic polyfill or the stock single-body
page generator. Full author HTML, CSS, and JavaScript run in the isolated template
frame. A vendor update must rerun exact-text, repeated-text/zero-offset,
unique-image, and visible glyph/visual-boundary region-pagination probes,
together with the template lifecycle/activation tests. A DOM text round trip by
itself does not prove that the last line is fully visible.

The npm archive was downloaded without executing install scripts and verified
against its published SHA-512 integrity value:

`sha512-YtAN9JAjsQw1142gxEjEAwXvOF5nYQuDwnQ67RW2HZDkMLI+b4RsBE37lULZa9gAr6kDAOGBOhXI4wGMoY3raw==`

Original bundle SHA-256: `4cae0c875c89084b353ceee87bcc742388de3133f2bbf48830b63f1d2d357e4f`

Patched bundle SHA-256: `241c38b4e098f7d51b1eb65fb38de45dd043e12e3127fd731cd7547a8694f78b`
