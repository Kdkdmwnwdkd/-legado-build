# Shimmer Reader

[English](English.md) · [中文](README.md)

Shimmer is an Android reader. Import book sources you provide, or read local TXT and EPUB files.

The app does not include books or book sources, does not host any content, and does not provide sources of its own.

> This repository is maintained independently. It does not track any upstream project and does not accept pull requests whose purpose is syncing upstream. Code origin and licensing are stated under "Origin and license" at the end of this file.

## Downloads

- [GitHub Releases](https://github.com/Kdkdmwnwdkd/-legado-build/releases)

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
- [Page templates](docs/reader-templates.md)
- [Network and DNS](docs/doh-network.md)
- [Changelog](CHANGELOG.md)
- [API](api.md)

## Origin and license

The code originates from the open-source Legado reader and its forks. In line with GPLv3, the origin is stated as follows:

- [gedoor/legado](https://github.com/gedoor/legado): the original project.
- [Luoyacheng/legado](https://github.com/Luoyacheng/legado): Reading Sigma.
- [rimvoid/legado](https://github.com/rimvoid/legado) (formerly Rimchars/legado, Reading Archive): the code baseline of this project.

None of these projects is affiliated with this one. This project is not their official release and no longer follows their updates.

See [LICENSE](LICENSE) and the [third-party license notices](app/src/main/assets/LICENSE.md). This project is released under GPLv3 as well.
