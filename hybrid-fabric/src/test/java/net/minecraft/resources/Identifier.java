// SPDX-License-Identifier: GPL-3.0-only
package net.minecraft.resources;

/** Minimal owned-target linkage stub needed by Fabric API's Event class. */
public final class Identifier {
    private final String namespace;
    private final String path;

    private Identifier(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
    }

    public static Identifier fromNamespaceAndPath(String namespace, String path) {
        return new Identifier(namespace, path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
