// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.logging;

/**
 * Severity of a {@link StructuredLogRecord}, matching the levels the Victus panel's JSON scrapers
 * already understand. The JSON representation is the uppercase {@link #name()} (e.g. {@code "INFO"}),
 * exactly as shown in the Phase&nbsp;2 spec example.
 */
public enum LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}
