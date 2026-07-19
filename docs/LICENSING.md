# Licensing

Victus Engine is a fork of **PaperMC**, so it inherits Paper's licensing split:

| Code | License | Where |
| --- | --- | --- |
| Server (patches to Paper server, our engine features) | **GPL-3.0-only** | `patches/paper-server`, module code |
| API (patches to Paper API) | **MIT** (mirroring Paper API) | `patches/paper-api` |

## Obligations

- **GPL-3.0** requires that distributed binaries be accompanied by (or offer) the corresponding
  source. If Victus Cloud ships Victus Engine to customers, the modified source must be available.
- You **must vendor the full GPL-3.0 text** into `LICENSE` before any release (the scaffold left a
  placeholder because it was generated offline): <https://www.gnu.org/licenses/gpl-3.0.txt>.
- Add per-file SPDX headers to new source (`// SPDX-License-Identifier: GPL-3.0-only`).

## Minecraft / Mojang

- The Minecraft EULA permits hosting servers and writing server software/plugins, but **forbids
  redistributing Mojang's code** and complete/unmodified obfuscation mappings. paperweight fetches
  and patches Paper on the build machine; **never commit vendored Minecraft/Paper source**
  (enforced by `.gitignore`).
- As of the 26.1 base, Java Edition ships **unobfuscated** with a `LICENSE` inside the jar — this
  does not change the EULA. Bedrock is unaffected.
- EULA bans **pay-to-win** and **pay-gated player queues** — the `eula.no-pay-to-win-guard` config
  surfaces warnings so downstream server owners stay compliant.
