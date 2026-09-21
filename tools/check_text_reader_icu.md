Run the image script marker pattern through native ICU before releasing a reader
change. Android's regular expression implementation uses ICU; a pattern that
compiles in the desktop JDK can still fail when an Android class initializes.

The checker reads and decodes `TextReaderImageSource.scriptPattern` directly from
the production Kotlin file. It does not maintain a second copy of the fixed
pattern. It also compiles the previous unescaped pattern as a negative control:
the check fails if that known-bad pattern is accepted by the selected ICU engine.

```powershell
python tools/check_text_reader_icu.py --output output/text-linkage-icu-green.json
python tools/check_text_reader_icu.py --source output/text-linkage-before/TextReaderImageResource.kt --output output/text-linkage-icu-red.json
```

The first command must exit 0 with `passed: true`. The preserved broken source
must exit 1 and report an ICU compilation error. The red snapshot is optional;
every normal invocation includes the legacy negative control.

Windows uses `%SystemRoot%\System32\icu.dll` by default. Other platforms search
their installed `icui18n`/`icucore` library. Use `--icu-library /path/to/library`
(or `ICU_LIBRARY`) to select a particular ICU version. Versioned C exports such
as `uregex_open_72` are supported. No packages are downloaded.

Missing ICU fails with exit 2. An environment that intentionally lacks native
ICU can pass `--missing-icu=skip`; that produces `status: skipped`, `passed: false`
and exit 0. A release gate should keep the default failure policy and require
the report's `passed` field, so a skip cannot be reported as validation.

To check the pattern extracted from the actual APK's class initializer, first
run `tools/check_text_reader_apk_linkage.py`, then pass its JSON:

```powershell
python tools/check_text_reader_icu.py --linkage-report output/text-linkage-apk-linkage.json --output output/text-linkage-apk-icu.json
```

This additionally verifies the APK hash and equality of the decoded source/DEX
pattern and RegexOption. Evidence contains source/checker/library hashes, ICU
version, compilation error positions, UTF-16 match spans and individual cases.

The match cases cover `{{...}}`, `<js>`, `@js:`, multiline/case handling, URL
fragments and literal data URI text. Pattern searches must not change their input.
Two raw SVG metadata cases deliberately match marker text: the production
`data:image` routing guard decides that this remains literal image content.
`TextReaderImageResolverTest` covers that guard, source options, and click/pclick
exclusions. This tool tests the native pattern engine, not the application routing
policy, ART class loading, or APK startup. Its ICU version is recorded because it
does not automatically reproduce every Android release's ICU version.
