# patches/paper-api

Patches to the **Paper API** by paperweight. **MIT** (mirroring Paper's API license).

Keep this surface small and additive — the product promise is that existing Bukkit/Spigot/Paper
plugins work unchanged. New API here is only for:

- Victus-specific events/hooks (e.g. lag-doctor findings, hosting-limit throttle events)
- Folia-aware scheduler markers for our own hub/lobby/discovery plugins (regionized mode)

Same workflow as `paper-server`: edit `paper-api/` after `applyPatches`, then `rebuildPatches`.
