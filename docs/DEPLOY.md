# Build artifacts and manual test deployment

The current CI workflow (`.github/workflows/build.yml`) validates the frozen version catalog, applies the 26.2 source patches, builds the server, and uploads a short-lived GitHub Actions artifact. It **does not publish a release, upload to a panel, or restart a server**.

This is deliberate: every entry in `versions/versions.json` currently has `publicationEligible: false`, and the implemented 26.2 line has not yet completed the boot/banner-ordering publication gates documented in `docs/STATUS.md`.

## Retrieve a CI artifact

1. Open the successful `build current source line` workflow run in GitHub Actions.
2. Download the `victus-engine-26.2-<commit>` artifact before its seven-day retention period expires.
3. Treat it as CI evidence only, not as a published Victus release.

## Manual isolated test deployment

An operator may manually place a locally verified CI artifact on an isolated test server. The test server must run **Java 25**; Java 21 cannot start the 26.2 server.

- Set the Pterodactyl egg/image to a Java 25 image.
- Point the startup command at the uploaded jar, for example:
  `java -Xms512M -XX:MaxRAMPercentage=70.0 -jar victus.jar --nogui`
- Accept Mojang's EULA through the panel or with `eula=true` in `eula.txt`. Victus Engine never auto-accepts it.
- Capture startup logs proving Paper's `Done` marker appears before the Victus banner and that the banner/URL appear exactly once.
- Stop the server cleanly after the check and retain the artifact checksum and source commit with the test evidence.

Do not describe this manual test path as a release or automated deployment. A future deployment workflow must be added deliberately only after the catalog's publication gates are satisfied; it must not silently reintroduce panel secrets or deploy side effects into the build workflow.
