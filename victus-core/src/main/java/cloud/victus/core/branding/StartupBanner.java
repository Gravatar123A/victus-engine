// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.branding;

import java.util.List;

/** Plain-text-safe startup branding shared by native lifecycle adapters. */
public final class StartupBanner {
    public static final List<String> LINES = List.of(
            "__     _____ ____ _____ _   _ ____     ____ _     ___  _   _ ____ ",
            "\\ \\   / /_ _/ ___|_   _| | | / ___|   / ___| |   / _ \\| | | |  _ \\",
            " \\ \\ / / | | |     | | | | | \\___ \\  | |   | |  | | | | | | | | |",
            "  \\ V /  | | |___  | | | |_| |___) | | |___| |__| |_| | |_| | |_| |",
            "   \\_/  |___|\\____| |_|  \\___/|____/   \\____|_____|\\___/ \\___/|____/",
            "Powered by Victus Cloud — https://victuscloud.com"
    );

    private StartupBanner() {
    }
}
