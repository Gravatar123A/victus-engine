# Module: config auto-migration

Config: `compatibility.migrate-config-on-boot`. Makes switching from Paper/Spigot/Purpur feel safe
and reversible — a key adoption lever (people fear touching server config).

## Behavior
On first boot (marker file absent), detect and import existing configs:
- `server.properties`, `bukkit.yml`, `spigot.yml`, `paper-global.yml` / `paper-world-defaults.yml`,
  and `purpur.yml` if present.
- Map equivalent keys into Victus defaults; leave anything already set by the operator untouched.
- Write a `victus-migration-report.txt`: every key imported, every key with no Victus equivalent,
  and every Victus default that differs from the source (with the reasoning).
- Never overwrite the source files. Drop a `.victus-migrated` marker so it runs once.

## Purpur NBT tolerance
`purpur-nbt-tolerance` suppresses the "unknown NBT tag" noise that appears when migrating worlds
that touched Purpur-only blocks/NBT, so a Purpur → Victus move is clean.

## Reversibility
Migration only writes `victus.yml`; the original Paper/Spigot/Purpur files stay in place, so
reverting is "point the panel back at the old jar." Document this in the migration guide.
