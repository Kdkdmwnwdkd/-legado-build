# Reading Archive

[English](English.md) · [中文](README.md)

Reading Archive builds on the Legado branch maintained by Lyc, extending the open-source Android reader with reading layouts, themes, text-to-speech, AI tools and scheduled tasks.

The app does not include books or book sources. Add your own sources or import local TXT and EPUB files.

## Downloads

- [GitHub Releases](https://github.com/Kdkdmwnwdkd/-legado-build/releases)
- [Gitee Releases](https://gitee.com/zziji/legado/releases)
- [Gitee update channel](https://gitee.com/zziji/legado/releases/tag/latest-arm64-release)

Version 14, `3.26.09160852-smooth`, is available as an arm64-v8a APK. See the release pages for downloads and full release notes.

## Features

- Read from configurable book sources or local files, with bookmarks, chapter caching and reading progress.
- Organize books with groups, custom tags, batch management and an immersive details page.
- Use native rendering or EPUB rendering for ordinary text, with separate layout settings.
- Import local Reeden `.red` highlight rules, search and edit rules, and select fonts and background images.
- Customize first and continuation pages with HTML, CSS and JavaScript. Two built-in examples demonstrate horizontal and vertical layouts.
- Share locally imported images and fonts between highlight rules and page templates.
- Preload nearby chapters and reuse laid-out WebViews to reduce work during page turns. Smooth mode prepares up to four nearby chapters; Extreme mode prepares up to six.
- Configure themes, backgrounds, advanced headers and footers, comment bubbles and reading controls.
- Use system or network TTS, text following, floating playback controls, and comic or video entry points.
- Configure AI services and reading tools, scheduled tasks, backups, WebDAV and object storage.
- Configure DNS/DoH providers, routing by feature, domain exceptions and DNS measurements.

## Rendering differences

Complex highlight artwork and CSS use EPUB rendering; native text supports basic text highlighting. Ordinary text in EPUB mode currently does not run paragraph rules or `pclick`; original image `click` actions and replacement rules remain supported. Page templates apply to ordinary text in EPUB mode and have a separate library backup.

DoH is off by default and configuration changes require an app restart. Requests made internally by WebView pages and system or third-party TTS do not automatically follow native-client DNS routing.

## Documentation

- [Full feature guide (Chinese)](docs/features.md)
- [Version 14 release notes (Chinese)](docs/releases/2026-09-17-v14.md)
- [Page templates](docs/reader-templates.md)
- [Network and DNS](docs/doh-network.md)
- [Changelog](CHANGELOG.md)
- [API](api.md)

This release has passed automated checks, browser rendering comparisons and APK validation. Android device appearance, touch responsiveness and whole-app frame rate have not been measured.

## Credits

Thanks to [gedoor/legado](https://github.com/gedoor/legado), [Luoyacheng/legado](https://github.com/Luoyacheng/legado) and their contributors. Thanks to Mingyue for the scheduled task contribution.

See [LICENSE](LICENSE) and the [third-party license notices](app/src/main/assets/LICENSE.md).
