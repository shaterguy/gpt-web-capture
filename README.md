# GPT Web Capture

Standalone Android WebView diagnostics recorder for ChatGPT web UI investigations. It is intentionally separate from SelfRun and the prompt scheduler so web-structure regressions can be captured without changing production automation apps.

## DEV usage

1. Install the DEV APK (`com.shaterguy.gptwebcapture.dev`).
2. Sign in to ChatGPT inside the app WebView.
3. Navigate to the exact page exhibiting the issue, including Project landing/new-chat pages.
4. Tap **전체 캡처**.
5. Choose a destination when Android opens the ZIP save dialog. If the dialog is cancelled, tap **ZIP 저장** to retry.

## Capture package

Every ZIP contains `manifest.json` with capture identity, app/build identity, source URL, per-file size/SHA-256, artifact failures, and the active redaction policy. Capture is best-effort per artifact: one unsupported API does not discard the rest of the package.

Typical contents:

- `screenshots/`: WebView viewport, app window, and best-effort full-page render.
- `page/webarchive.mht`: WebView MHT archive when supported.
- `web/dom/`: sanitized rendered HTML, document/focus/selection state, all element/layout/computed-style inventory, semantic/accessibility-like inventory, forms, and detailed composer candidates.
- `web/shadow/`: open Shadow DOM plus closed roots retained by the document-start hook, with per-root HTML and element/style inventories.
- `web/frames/`: iframe/frame metadata and same-origin frame DOM inventories; cross-origin contents remain blocked by the browser same-origin policy.
- `web/css/`: readable CSSOM rules and cross-origin access errors.
- `web/timeline/`: document-start Mutation/lifecycle/focus/input/click/key/history/error event summaries.
- `web/performance/`: Performance API entries and buffered PerformanceObserver records.
- `web/environment/`: navigator, viewport, feature, user-agent client hints, media-query and browser environment state.
- `web/storage/`, `web/cache/`, `web/service-workers/`: storage/cookie/cache/service-worker metadata without raw secret values.
- `web/resources/`: scripts (URL or inline length/hash), links and metadata.
- `web/framework/`: framework marker key/count evidence without serializing React/Next internal values.
- `network/events.json`: WebView request/HTTP/resource/SSL/page events with sensitive headers and URL parameters redacted.
- `console/events.json`: WebChrome console messages with token-like secrets scrubbed.
- `accessibility/android-tree.json`: Android accessibility-node view of the WebView when exposed by the runtime.
- `native/`: app/device/WebView package, WebSettings, network, display, history, certificate and cookie metadata.
- `limitations.json`: data that cannot be safely or technically captured from an in-app WebView.

## Credential and privacy boundary

The app has no analytics, backend upload, cloud sync, or broad storage permission. Captures are created in app-private cache and leave the app only through an explicit Android document export.

Authentication secrets are not intentionally persisted in plaintext. Cookie values, Authorization/Cookie headers, password/hidden input values, storage values, nonces, inline event handlers, inline script bodies and secret-like URL parameters are represented by redaction markers and/or length/SHA-256 metadata. The rendered page itself can contain personal conversation content, so treat the exported ZIP as private diagnostic data.

## Known technical limits

- WebView interception does not expose request bodies and this app does not proxy authenticated response bodies.
- Cross-origin iframe DOM is inaccessible by the same-origin policy; metadata is still recorded.
- Closed ShadowRoot capture requires AndroidX WebKit document-start script support so the root can be retained when created.
- Chrome DevTools Protocol-only traces, DevTools `getEventListeners()`, and the Chromium AX protocol tree are not available internally; the app records JS/native alternatives.
- Full-page screenshot generation is pixel-capped to prevent process OOM and is marked best-effort; viewport and app-window captures remain separate.

## Build

The development branch uses Android API 36, Java 17, Gradle 9.5, and AGP 9.3.1. CI compiles unit/instrumentation sources and candidates before starting the API 36 emulator. The WebView instrumentation test runs the real capture runtime against a synthetic Project-like composer with open/closed Shadow DOM and verifies credential redaction.
