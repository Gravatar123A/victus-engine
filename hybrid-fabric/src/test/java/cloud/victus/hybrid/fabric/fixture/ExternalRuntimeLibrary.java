// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.fabric.fixture;

/** Class packaged only in the integration test's external target-library jar. */
public final class ExternalRuntimeLibrary {
    private ExternalRuntimeLibrary() {
    }

    public static String marker() {
        return "VICTUS_EXTERNAL_LIBRARY_VISIBLE";
    }
}
