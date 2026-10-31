# Ferry

Send files and clipboard text between an Android phone and a Mac on the same Wi-Fi.

- `mac/` — menu bar app (Swift, SwiftUI `MenuBarExtra`). Build with `mac/build.sh`.
- `android/` — Android app (Kotlin, Compose). Build with `./gradlew assembleDebug`.

## Using it

1. Start Ferry on the Mac (an icon appears in the menu bar).
2. Open Ferry on the phone and tap the Mac in the list.
3. The Mac asks "Pair with <phone>?" and shows a 4-digit code. Check it matches the phone, then click Allow.

After that:

| From | How |
|---|---|
| Phone → Mac, files | Share any file → Ferry, or "Send files" in the app. Lands in `~/Downloads`. |
| Phone → Mac, clipboard | Quick Settings tile "Send clipboard", the button in Ferry's notification, or the app. Text or an image. |
| Mac → phone, files | Drop files on the Ferry menu window, or "Send files…". Lands in `Download/Ferry`. |
| Mac → phone, clipboard | "Send clipboard". Sends files copied in Finder, else an image (screenshots), else text. |

Android doesn't let background apps read the clipboard, so phone → Mac clipboard always takes one tap.

## Protocol (v1)

Bonjour service type `_ferry._tcp`. TXT record: `id` (device UUID), `name`, `kind` (`mac` or `android`).
The Mac listens on TCP 47820, the phone on 47821. Either falls back to any free port and Bonjour carries the real one.

One request per TCP connection:

```
{"v":1,"type":"…", …}\n      ← header: one line of JSON
<size bytes of payload>       ← only for "text" and "file"
{"ok":true}\n                 ← reply, or {"ok":false,"error":"…"}
```

| type | header fields | payload |
|---|---|---|
| `pair` | `id`, `name`, `port`, `code` | none; reply adds `id`, `name`, `token` |
| `text` | `id`, `token`, `port`, `size`, `clip` | UTF-8 text (max 10 MB) |
| `file` | `id`, `token`, `port`, `size`, `name`, `clip` | file bytes; with `clip`, an image also goes on the receiver's clipboard |

Pairing is sent by the phone. The Mac asks its user to allow it, then returns a random token that both sides
store. Every later request carries the sender's `id` and the shared `token`; anything else is refused.

**Not yet encrypted.** Traffic is authenticated by the token but travels in the clear on the local network.
Fine for home Wi-Fi; TLS (or AES-GCM framing with the shared key) is the next step before using it on shared networks.
