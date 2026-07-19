// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.config;

/**
 * Network packet compression backend.
 *
 * <p>MUST stay wire-compatible with the Minecraft protocol, whose packet compression is fixed to
 * zlib/DEFLATE. {@code libdeflate} emits identical zlib output, just faster, so it is a drop-in.
 *
 * <p><b>{@code zstd} is intentionally rejected here</b> — using it for packet compression would
 * break every stock client. zstd is only valid for region/disk storage (see docs/phase-1/05-chunks.md
 * and docs/phase-1/04-network-ping.md). This enum encodes that correctness rule so a stray config
 * value fails fast with a helpful message instead of corrupting the wire protocol.
 */
public enum CompressionBackend {
    ZLIB,
    LIBDEFLATE;

    public static CompressionBackend fromConfig(String s) {
        if (s == null) return LIBDEFLATE;
        switch (s.trim().toLowerCase()) {
            case "zlib":       return ZLIB;
            case "libdeflate": return LIBDEFLATE;
            case "zstd":
                throw new IllegalArgumentException(
                        "optimizations.network.compression 'zstd' is invalid: Minecraft packet "
                      + "compression is fixed to zlib/DEFLATE, and zstd would break stock clients. "
                      + "Use 'libdeflate' (wire-compatible, faster). zstd is only for region/disk storage.");
            default:
                throw new IllegalArgumentException("unknown optimizations.network.compression: " + s
                      + " (expected zlib|libdeflate)");
        }
    }
}
