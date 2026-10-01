# Apps

Each app lives in its own folder and doesn't share files with the others.

| App | Folder | Live link |
| --- | --- | --- |
| Retro Cam | [`retro-cam/`](retro-cam/) | https://skigoogs-star.github.io/TESTES/retro-cam/ |
| Athletic Cut | [`athletic-cut/`](athletic-cut/) | https://skigoogs-star.github.io/TESTES/athletic-cut/ |

The site root (`index.html`) is a small page linking to both. The root
`sw.js` exists only to retire the service worker that Retro Cam registered
when it lived at the root; don't add anything to it.

GitHub Pages publishes this repository from the branch chosen under
**Settings → Pages**.
