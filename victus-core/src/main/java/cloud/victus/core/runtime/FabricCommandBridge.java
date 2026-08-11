// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Source-owned replacement for Fabric Command API's incompatible two-argument Commands constructor mixin. */
public final class FabricCommandBridge {
    private static final String ENABLE_PROPERTY = "victus.fabric.provider";
    private static final String CALLBACK = "net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback";

    private FabricCommandBridge() {
    }

    public static void register(Object dispatcher, Object context, Object selection) {
        if (!Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "false"))) return;
        ClassLoader loader = dispatcher.getClass().getClassLoader();
        try {
            Class<?> callback = Class.forName(CALLBACK, true, loader);
            Object event = callback.getField("EVENT").get(null);
            Object invoker = event.getClass().getMethod("invoker").invoke(event);
            Method register = findRegister(callback, dispatcher, context, selection);
            register.invoke(invoker, dispatcher, context, selection);
            System.out.println("VICTUS_FABRIC_COMMANDS owner=source-bridge");
        } catch (ClassNotFoundException absent) {
            // Command API is optional in curated installs.
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throw bridgeFailure(cause);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw bridgeFailure(failure);
        }
    }

    private static Method findRegister(Class<?> owner, Object dispatcher, Object context, Object selection)
            throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals("register") || method.getParameterCount() != 3) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types[0].isInstance(dispatcher) && types[1].isInstance(context) && types[2].isInstance(selection)) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + ".register(dispatcher,context,selection)");
    }

    private static IllegalStateException bridgeFailure(Throwable cause) {
        return new IllegalStateException("FABRIC_COMMAND_BRIDGE_FAILED: cannot dispatch command registration", cause);
    }
}
