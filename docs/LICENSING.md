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
- NeoForge hybrid tooling tracks only locks, scripts, owned fixtures, and bridge patches. The NeoForge
  installer, NeoForm data/tools, FML/NeoForge libraries, Minecraft jars, reconstructed source, and merged
  game layers are downloaded/generated below `hybrid-neoforge/build/` and must not be committed. NeoForge/FML
  components retain their upstream LGPL/MIT/BSD/Apache licenses; Minecraft content remains governed by Mojang.
- EULA clearly bans **pay-to-win** (any "competitive gameplay advantage"). The rule for paid access
  is "one charge, the same for everyone, for access to the server as a whole," and access can't be
  gated by out-of-game purchases. **Priority/queue monetization is a genuine gray area** — a
  standalone "skip the queue" SKU is arguably non-compliant, but rank/subscriber priority queues sit
  in a contested, largely-unenforced zone (Hypixel runs them). Don't treat priority queues as flatly
  banned. The `eula.no-pay-to-win-guard` config warns on clear P2W patterns (and optionally on
  standalone queue-skip SKUs), not on rank-based priority.
- All these permissions are **discretionary and revocable by Mojang at any time.**
