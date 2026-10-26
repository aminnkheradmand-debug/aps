# Generated App — JavaScript API (APS V4.5)

This file ships inside your app as `assets/www/asp-api.md`. All bridges are
feature-detected: when hardware or configuration is missing, calls degrade
gracefully instead of crashing the page.

## Always available — window.AndroidBridge

```js
AndroidBridge.showToast(message)
AndroidBridge.vibrate(durationMs)              // 10..5000ms
AndroidBridge.shareText(title, text)
AndroidBridge.copyToClipboard(text)
AndroidBridge.showNotification(title, body)    // needs notifications permission
AndroidBridge.printPage()                      // system print dialog
AndroidBridge.saveBase64File(dataUrl, mime)    // save to Downloads
const info = JSON.parse(AndroidBridge.getDeviceInfo())
```

## window.ASPPen — S Pen / stylus hover API (generated apps)

Bridged by the native `PenHoverBridge` class + the `asp-pen.js` shim. On devices
without a stylus digitizer the API is present but no events fire (silent degrade).

Events (all dispatched on the element under the pen / its ancestors):
- `pointerenter` / `pointerover` / `pointermove` / `pointerout` / `pointerleave` —
  W3C Pointer Events with `pointerType: "pen"`, `pressure`, `tiltX`, `tiltY`.
- `asp-pen-hover` — CustomEvent on `document` with `detail =
  { x, y, pressure, tiltX, tiltY, timestamp, source: "pen" }` (fallback channel
  for pages that do not consume Pointer Events).

Programmatic API:
```js
window.ASPPen.supported            // true when the shim is active
window.ASPPen.last()               // last hover payload or null
window.ASPPen.configure({ pointerEvents: true, customEvent: true, throttleMs: 16 })
const off = window.ASPPen.onHoverMove((payload, element) => { ... })
const offE = window.ASPPen.onHoverEnter((payload, element) => { ... })
const offL = window.ASPPen.onHoverLeave((payload) => { ... })
off(); offE(); offL()              // unsubscribe
```

Minimal consumer:
```js
document.addEventListener("asp-pen-hover", (ev) => {
  const { x, y, pressure } = ev.detail;
  reticle.style.transform = `translate(${x}px, ${y}px)`;
  reticle.style.opacity = 0.4 + pressure * 0.6;
});
```

## window.ASPHandwriting — handwriting-to-text API (generated apps)

Bridged by the native `HandwritingBridge` class + the `asp-handwriting.js` shim.
Preferred engine: **Google ML Kit Digital Ink** (on-device, offline after a one-time
model download). Fallback: the **system IME** (Samsung Keyboard handwriting mode).

Ink stroke format (ML Kit-compatible):
```js
const strokeData = {
  strokes: [
    { points: [ { x: 120.5, y: 88.0, t: 1730000000123 }, /* … */ ] },
    { points: [ /* second stroke */ ] }
  ]
};
```

API:
```js
const text1 = await window.ASPHandwriting.recognize(strokeData); // ML Kit engine
const text2 = await window.ASPHandwriting.openSystemInput();      // IME fallback:
                                                                   // opens a native
                                                                   // input with the
                                                                   // handwriting keyboard
window.ASPHandwriting.engine()     // "mlkit" | "ime" | "auto" | "none"
window.ASPHandwriting.modelReady() // true once the ML Kit model is local
window.ASPHandwriting.onResult(cb) // floating-button results (cb({ text }))
document.addEventListener("asp-handwriting-result", ev => { … });
document.addEventListener("asp-handwriting-engine", ev => { … });
```

Errors reject with `BAD_INK_JSON`, `HANDWRITING_TIMEOUT`, `CANCELLED`, or
`HANDWRITING_FAILED` (engine unavailable). The ML Kit model downloads once on
first recognition when online and stays on-device afterwards — no persistent
network dependency.

## Offline & storage behavior

- Local content is served from `https://appassets.androidplatform.net/assets/www/`
  via `WebViewAssetLoader` — fetch/XHR your bundled files exactly like HTTPS.
- `offline.html` is shown by the no-internet dialog when remote URLs fail.
- Download and upload flows (DownloadManager, file chooser) are always enabled.
