// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.branding;

import java.util.List;

/** Dependency-free snapshot test for the native startup banner contract. */
public final class StartupBannerSelfTest {
    private StartupBannerSelfTest() {
    }

    public static void main(String[] args) {
        List<String> expected = List.of(
                "__     _____ ____ _____ _   _ ____     ____ _     ___  _   _ ____ ",
                "\\ \\   / /_ _/ ___|_   _| | | / ___|   / ___| |   / _ \\| | | |  _ \\",
                " \\ \\ / / | | |     | | | | | \\___ \\  | |   | |  | | | | | | | | |",
                "  \\ V /  | | |___  | | | |_| |___) | | |___| |__| |_| | |_| | |_| |",
                "   \\_/  |___|\\____| |_|  \\___/|____/   \\____|_____|\\___/ \\___/|____/",
                "Powered by Victus Cloud — https://victuscloud.com"
        );
        if (!StartupBanner.LINES.equals(expected)) {
            throw new AssertionError("startup banner snapshot changed: " + StartupBanner.LINES);
        }
        long urls = StartupBanner.LINES.stream().filter(line -> line.contains("https://victuscloud.com")).count();
        if (urls != 1) {
            throw new AssertionError("expected exactly one Victus Cloud URL, found " + urls);
        }
        System.out.println("StartupBannerSelfTest: 2 passed, 0 failed");
    }
}
