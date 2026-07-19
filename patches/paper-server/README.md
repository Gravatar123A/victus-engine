# patches/paper-server

Feature and fix patches applied to **Paper's server** module by paperweight. **GPL-3.0-only.**

Generated/managed via Gradle — do not hand-edit applied source, edit the checkout then
`./gradlew rebuildPatches`:

```bash
source ../../scripts/dev-env.sh
./gradlew applyPatches        # checks out Paper + applies these patches into paper-server/
# edit paper-server/ ...
./gradlew rebuildPatches      # writes changes back here as .patch files
```

Planned patch groups (Phase 1+; see docs/ARCHITECTURE.md §3–§6):

- `optimizations/` — redstone (Alternate Current), DAB, async pathfinding/tracker, netty/compression, mem dedup
- `threading/`     — parallel + regionized tick modes (opt-in)
- `hosting/`       — per-instance limits, Prometheus exporter, JSON logging, lag-doctor, control hooks
- `hybrid/`        — Fabric/NeoForge loader bridge (isolated)
- `compat/`        — config auto-migration, EULA guard
