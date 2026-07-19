# build-data

paperweight fork metadata. Depending on the paperweight-patcher version this may hold
`dev-imports.txt` (extra classes to import from upstream for patching), mapping notes, and
per-version build config.

Since the 26.1 base ships **unobfuscated**, the historical mappings/remap steps are largely gone —
one of the reasons this base was chosen. Confirm exactly what belongs here against the current
paperweight-patcher docs when you first build online.
