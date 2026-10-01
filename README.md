# Apps

Each app lives in its own folder and doesn't share files with the others.

| App | Folder | Live link |
| --- | --- | --- |
| Retro Cam | [`retro-cam/`](retro-cam/) | https://skigoogs-star.github.io/TESTES/retro-cam/ |
| Athletic Cut | [`athletic-cut/`](athletic-cut/) | https://skigoogs-star.github.io/TESTES/athletic-cut/ |
| Aloud | [`aloud/`](aloud/) | https://skigoogs-star.github.io/TESTES/aloud/ |
| DeckRec (Android) | [`deckrec-android/`](deckrec-android/) | APK download: https://github.com/skigoogs-star/TESTES/releases/tag/latest |

DeckRec is an Android app, not a web page, so it has no website link. Its
APK is built by `.github/workflows/android.yml` and published to the
`latest` release from the DeckRec branch (`claude/djm-rec-samsung-app-ixahlr`).

The site root (`index.html`) is a small page linking to every app. The root
`sw.js` exists only to retire the service worker that Retro Cam registered
when it lived at the root; don't add anything to it.

GitHub Pages publishes this repository from the branch chosen under
**Settings → Pages**.
