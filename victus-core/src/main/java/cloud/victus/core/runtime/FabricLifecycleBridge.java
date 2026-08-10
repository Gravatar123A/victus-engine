// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Reflective fallback for Fabric server lifecycle events on Paper-patched startup shapes.
 * This class has no Fabric linkage and is inert unless the Victus Fabric provider is active.
 */
public final class FabricLifecycleBridge {
    private static final String ENABLE_PROPERTY = "victus.fabric.provider";
    private static final String EVENTS = "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents";
    private static final String MIXIN_HOOKS = "net.fabricmc.fabric.impl.event.lifecycle.MinecraftServerHooks";

    private FabricLifecycleBridge() {
    }

    public static void starting(Object server) {
        dispatch(server, "SERVER_STARTING", "onServerStarting");
    }

    public static void started(Object server) {
        dispatch(server, "SERVER_STARTED", "onServerStarted");
    }

    public static void stopping(Object server) {
        dispatch(server, "SERVER_STOPPING", "onServerStopping");
    }

    public static void stopped(Object server) {
        dispatch(server, "SERVER_STOPPED", "onServerStopped");
    }

    private static void dispatch(Object server, String fieldName, String callbackName) {
        if (!Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "false"))) return;
        if (server == null) throw failure(fieldName, new NullPointerException("server"));
        ClassLoader loader = server.getClass().getClassLoader();
        try {
            Class<?> mixinHooks = Class.forName(MIXIN_HOOKS, false, loader);
            if (mixinHooks.isInstance(server)) {
                // Fabric API's normal MinecraftServerMixin owns the event when it transformed successfully.
                return;
            }
        } catch (ClassNotFoundException ignored) {
            // Lifecycle API may still be present without its MinecraftServer mixin; use the source bridge below.
        } catch (LinkageError failure) {
            throw failure(fieldName, failure);
        }

        try {
            Class<?> events = Class.forName(EVENTS, true, loader);
            Object event = events.getField(fieldName).get(null);
            Object invoker = event.getClass().getMethod("invoker").invoke(event);
            Method callback = findCallback(invoker.getClass(), callbackName, server.getClass());
            callback.invoke(invoker, server);
            System.out.println("VICTUS_FABRIC_LIFECYCLE event=" + fieldName + " owner=source-bridge");
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throw failure(fieldName, cause);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw failure(fieldName, failure);
        }
    }

    private static Method findCallback(Class<?> invokerClass, String name, Class<?> serverClass)
            throws NoSuchMethodException {
        for (Method method : invokerClass.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(serverClass)) {
                return method;
            }
        }
        throw new NoSuchMethodException(invokerClass.getName() + "." + name + "(" + serverClass.getName() + ")");
    }

    private static IllegalStateException failure(String event, Throwable cause) {
        return new IllegalStateException("FABRIC_LIFECYCLE_BRIDGE_FAILED: cannot dispatch " + event, cause);
    }
}
