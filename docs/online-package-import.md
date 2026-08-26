# App-level paragraph rule and bubble imports

Legado accepts the following app links:

```text
legado://import/paragraphRule?src=<percent-encoded-http-or-https-url>
legado://import/paragraphRules?src=<percent-encoded-http-or-https-url>
legado://import/bubblePackage?src=<percent-encoded-http-or-https-url>
```

`yuedu://` is accepted as a compatible scheme. The legacy bubble link remains supported:

```text
legado:///bubble?src=<percent-encoded-http-or-https-url>
```

New routes require the `import` host. Existing book source, RSS source, theme, read-config, and other app links are unchanged.

## Paragraph rule payloads

For compatibility, a single existing `ParagraphRule` JSON object or an array of existing rule objects can be imported. The versioned format can also carry rule variables:

```json
{
  "format": "legado.paragraph-rules",
  "schemaVersion": 1,
  "rules": [
    {
      "exportId": "optional-stable-export-id",
      "rule": {
        "name": "Example",
        "script": "return content;",
        "timeoutMillisecond": 3000
      },
      "vars": {
        "key": "value"
      }
    }
  ]
}
```

Book bindings are intentionally not imported. If names conflict, the user chooses whether to rename imported rules, skip conflicts, or overwrite existing rules. Overwrite preserves the existing database ID, order, and book bindings. Rules and variables are committed in one Room transaction.

## Bubble payloads

A bubble payload is a ZIP containing exactly one `bubble.json`. The manifest may be inside one wrapper directory, and files beside that manifest are installed as the package contents.

The importer enforces path traversal protection, entry and extraction quotas, compression-ratio limits, a bounded manifest, safe single-segment package directory names, and SVG restrictions. Installation uses same-parent staging and backup directories; an existing package is restored if moving or verifying the replacement fails.

## Network and confirmation policy

Downloads are bounded and streamed to temporary files. Only HTTP and HTTPS are accepted. Redirects are checked per hop, HTTPS cannot downgrade to HTTP, loopback/link-local/multicast addresses are rejected, and private-network destinations require an explicit second confirmation. A final preview shows the payload type, source, final address, size, and private-network status before import.

No database schema or entity is changed by this feature, so no database migration is required.
