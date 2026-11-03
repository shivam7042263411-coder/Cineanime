# Third-party notices

## Phisher AnimePahe — Cloudflare bypass flow

The Cloudflare bypass WebView flow in
`CineStream/src/main/kotlin/com/megix/settings/SettingsWebview.kt` is adapted
from Phisher's AnimePahe plugin (`com.phisher98.CloudflareWebViewDialog`),
distributed under the GNU General Public License, version 3 or later.

- Source project: https://github.com/phisher98/cloudstream-extensions-phisher
- Reference artifact: `AnimePahe.cs3` v48 (`com.phisher98.AnimePaheProviderPlugin`)

The adapted behaviour includes purging stale Cloudflare cookies, harvesting
cookies across the AnimePahe mirrors, polling the page title and cookie jar,
persisting `cf_clearance` together with the solving User-Agent, and
auto-dismissing the dialog.

This repository is distributed under the GNU General Public License, version 3
or later. See `LICENSE`.
