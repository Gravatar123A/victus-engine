// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.function.Function;

/**
 * Reflective replacement for Fabric Registry Sync's incompatible BootstrapMixin.
 *
 * <p>Paper owns built-in registry creation and freeze ordering. This hook runs after that sequence,
 * installs Fabric's state/item trackers, and marks registry bootstrap complete. It has no Fabric
 * linkage and is inert unless the Victus Fabric provider is active.
 */
public final class FabricRegistryBridge {
    private static final String ENABLE_PROPERTY = "victus.fabric.provider";
    private static final String REGISTRY_SYNC = "net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager";
    private static final String STATE_TRACKER = "net.fabricmc.fabric.impl.registry.sync.trackers.StateIdTracker";
    private static final String BLOCK_ITEM_TRACKER = "net.fabricmc.fabric.impl.registry.sync.trackers.vanilla.BlockItemTracker";
    private static final String BUILT_INS = "net.minecraft.core.registries.BuiltInRegistries";
    private static final String BLOCK = "net.minecraft.world.level.block.Block";
    private static final String FLUID = "net.minecraft.world.level.material.Fluid";
    private static final String ITEMS = "net.minecraft.world.item.Items";
    private static boolean initialized;

    private FabricRegistryBridge() {
    }

    public static synchronized void afterBootstrap() {
        if (!Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "false")) || initialized) return;
        ClassLoader loader = FabricRegistryBridge.class.getClassLoader();
        try {
            Class<?> syncManager;
            try {
                syncManager = Class.forName(REGISTRY_SYNC, false, loader);
            } catch (ClassNotFoundException absent) {
                // Registry Sync is optional. Leaving the source bridge inert preserves disabled/minimal installs.
                return;
            }
            Class<?> builtIns = Class.forName(BUILT_INS, true, loader);
            Object blockRegistry = field(builtIns, "BLOCK").get(null);
            Object fluidRegistry = field(builtIns, "FLUID").get(null);
            Object itemRegistry = field(builtIns, "ITEM").get(null);

            Class<?> block = Class.forName(BLOCK, true, loader);
            Class<?> fluid = Class.forName(FLUID, true, loader);
            // Match Fabric's deliberate static initialization accesses before registering trackers.
            Class.forName("net.minecraft.world.level.block.Blocks", true, loader);
            Class.forName("net.minecraft.world.level.material.Fluids", true, loader);
            Class.forName(ITEMS, true, loader);

            Class<?> stateTracker = Class.forName(STATE_TRACKER, true, loader);
            Method stateRegister = findStatic(stateTracker, "register", 3);
            Function<Object, Collection<?>> blockStates = stateGetter(block);
            Function<Object, Collection<?>> fluidStates = stateGetter(fluid);
            stateRegister.invoke(null, blockRegistry, field(block, "BLOCK_STATE_REGISTRY").get(null), blockStates);
            stateRegister.invoke(null, fluidRegistry, field(fluid, "FLUID_STATE_REGISTRY").get(null), fluidStates);

            Class<?> itemTracker = Class.forName(BLOCK_ITEM_TRACKER, true, loader);
            findStatic(itemTracker, "register", 1).invoke(null, itemRegistry);
            findStatic(syncManager, "bootstrapRegistries", 0).invoke(null);
            initialized = true;
            System.out.println("VICTUS_FABRIC_REGISTRY_SYNC owner=source-bridge stateTrackers=2 itemTracker=true postBootstrap=true");
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throw failure(cause);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw failure(failure);
        }
    }

    private static Function<Object, Collection<?>> stateGetter(Class<?> valueType) throws NoSuchMethodException {
        Method stateDefinition = valueType.getMethod("getStateDefinition");
        return value -> {
            try {
                Object definition = stateDefinition.invoke(value);
                @SuppressWarnings("unchecked")
                Collection<?> states = (Collection<?>) definition.getClass().getMethod("getPossibleStates").invoke(definition);
                return states;
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                throw failure(cause);
            } catch (ReflectiveOperationException | LinkageError failure) {
                throw failure(failure);
            }
        };
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getField(name);
        if (!Modifier.isStatic(field.getModifiers())) {
            throw new IllegalStateException(owner.getName() + "." + name + " is not static");
        }
        return field;
    }

    private static Method findStatic(Class<?> owner, String name, int parameters) throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters
                    && Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + " with " + parameters + " parameters");
    }

    private static IllegalStateException failure(Throwable cause) {
        return new IllegalStateException("FABRIC_REGISTRY_BRIDGE_FAILED: cannot initialize Fabric registry sync after Paper freeze", cause);
    }
}
