# Deploy: GitHub → build → your Minecraft server

The CI workflow (`.github/workflows/build.yml`) builds Victus Engine on every push to `main` and,
if enabled, **auto-deploys the jar to a test Minecraft server on your Victus (Pterodactyl) panel and
restarts it.**

## What you do on your side (one-time)

### 1. Create the GitHub repo + push
```bash
# on a machine with git (this repo already has commits):
cd /e/victus-engine
git remote add origin git@github.com:<you>/victus-engine.git
git push -u origin main
```
Pushing triggers the build automatically.

### 2. Turn on auto-deploy (GitHub → repo → Settings → Secrets and variables → Actions)

**Variables** (Variables tab):
| Name | Value |
| --- | --- |
| `DEPLOY_ENABLED` | `true` |
| `SERVER_JAR_NAME` | `server.jar` *(or whatever your egg's startup jar is named)* |

**Secrets** (Secrets tab):
| Name | Value |
| --- | --- |
| `PANEL_URL` | `https://control.victuscloud.com` |
| `PANEL_CLIENT_API_KEY` | a Pterodactyl **client** API key — panel → Account → API Credentials → `ptlc_...` |
| `PANEL_SERVER_ID` | the short server id from the panel URL (e.g. `1a2b3c4d`) |

That's it — the next push builds and deploys to that server.

### 3. IMPORTANT: the test server must run **Java 25**

Paper 26.2 (our base) requires **JDK 25**. Set the test server's egg/docker image to a Java 25
image (e.g. a `java_25` / `openjdk:25` Pterodactyl image), and its startup command to run
`server.jar` (or your `SERVER_JAR_NAME`). A Java 21 image will refuse to start the jar.

## What the pipeline does

1. **build** job — Ubuntu + JDK 25, `./gradlew applyAllPatches build`, uploads the jar artifact.
   (Runs on every push and PR; this is the reliable build environment — clean Linux, no Windows
   cert/temp quirks.)
2. **deploy** job — only on `main` with `DEPLOY_ENABLED=true`: uploads the jar to the server via the
   Pterodactyl client API and sends a `restart` power signal.

## Notes

- Deploy is skipped automatically on PRs and when `DEPLOY_ENABLED` isn't `true`, so the build stays
  green without secrets.
- For a real rollout later, the engine's own safe-restart/drain hook (docs/phase-2/05) replaces the
  blunt `restart` signal so players get moved to the hub via the proxy instead of dropped.
- Keep the `PANEL_CLIENT_API_KEY` scoped to just the test server if your panel supports it.
