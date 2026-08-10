// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.fabric;

import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.lib.gson.JsonReader;
import net.fabricmc.loader.impl.lib.gson.JsonToken;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/** Fail-closed audit of Fabric API mixin targets against the class-bearing Victus runtime. */
final class FabricApiCompatibilityValidator {
    private FabricApiCompatibilityValidator() {
    }

    static void validate(FabricLoaderImpl fabric, Path targetJar, List<Path> targetLibraries) {
        List<Path> gameClassPath = new ArrayList<>(targetLibraries.size() + 1);
        gameClassPath.add(targetJar);
        gameClassPath.addAll(targetLibraries);

        int modules = 0;
        int mixins = 0;
        Map<String, Set<String>> absentTargets = new LinkedHashMap<>();
        Map<String, Set<String>> unverifiableTargets = new LinkedHashMap<>();
        for (ModContainer mod : fabric.getAllMods()) {
            if (!mod.getMetadata().getId().startsWith("fabric-")) continue;
            modules++;
            for (Path root : mod.getRootPaths()) {
                if (!Files.isDirectory(root)) {
                    unverifiableTargets.computeIfAbsent(mod.getMetadata().getId(), ignored -> new LinkedHashSet<>())
                            .add("mod root is not a readable directory: " + root);
                    continue;
                }
                try {
                    Path metadata = root.resolve("fabric.mod.json");
                    if (!Files.isRegularFile(metadata)) {
                        unverifiableTargets.computeIfAbsent(mod.getMetadata().getId(), ignored -> new LinkedHashSet<>())
                                .add("fabric.mod.json missing from " + root);
                        continue;
                    }
                    for (String configName : readServerMixinConfigs(metadata)) {
                        Path config = root.resolve(configName);
                        if (!Files.isRegularFile(config)) {
                            unverifiableTargets.computeIfAbsent(mod.getMetadata().getId(), ignored -> new LinkedHashSet<>())
                                    .add("declared mixin config missing: " + configName);
                            continue;
                        }
                        MixinConfig parsed = readConfig(config);
                        for (String mixin : parsed.serverMixins()) {
                            mixins++;
                            String mixinClass = parsed.packageName().isBlank()
                                    ? mixin : parsed.packageName() + "." + mixin;
                            Path classFile = root.resolve(mixinClass.replace('.', '/') + ".class");
                            if (!Files.isRegularFile(classFile)) {
                                unverifiableTargets.computeIfAbsent(mod.getMetadata().getId(), ignored -> new LinkedHashSet<>())
                                        .add(mixinClass + " (mixin class missing)");
                                continue;
                            }
                            Set<String> targets = readMixinTargets(classFile);
                            if (targets.isEmpty()) {
                                unverifiableTargets.computeIfAbsent(mod.getMetadata().getId(), ignored -> new LinkedHashSet<>())
                                        .add(mixinClass + " (no declared target found)");
                                continue;
                            }
                            for (String target : targets) {
                                // A mixin can also target another Fabric implementation class. Only Minecraft
                                // targets establish compatibility with the class-bearing Victus/Paper runtime.
                                if (target.startsWith("net.minecraft.") && !containsClass(gameClassPath, target)) {
                                    absentTargets.computeIfAbsent(mod.getMetadata().getId(), ignored -> new LinkedHashSet<>())
                                            .add(mixinClass + " -> " + target);
                                }
                            }
                        }
                    }
                } catch (IOException failure) {
                    throw incompatible("cannot inspect module " + mod.getMetadata().getId() + ": " + failure, failure);
                }
            }
        }
        if (!unverifiableTargets.isEmpty() || !absentTargets.isEmpty()) {
            List<String> details = new ArrayList<>();
            if (!absentTargets.isEmpty()) {
                details.add("mixins target classes absent from the Victus/Paper runtime: " + render(absentTargets));
            }
            if (!unverifiableTargets.isEmpty()) {
                details.add("unverifiable mixin declarations: " + render(unverifiableTargets));
            }
            throw incompatible(String.join("; ", details), null);
        }
        System.out.println("VICTUS_FABRIC_API_COMPATIBILITY modules=" + modules + " mixins=" + mixins
                + " absentTargets=0 targetLibraries=" + targetLibraries.size());
    }

    private static IllegalStateException incompatible(String detail, Throwable cause) {
        String message = "FABRIC_API_MODULE_INCOMPATIBLE: " + detail;
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    private static String render(Map<String, Set<String>> failures) {
        List<String> modules = new ArrayList<>();
        failures.forEach((module, entries) -> modules.add(module + "=[" + String.join(", ", entries) + "]"));
        return String.join("; ", modules);
    }

    private static boolean containsClass(Collection<Path> classPath, String className) {
        String entry = className.replace('.', '/') + ".class";
        for (Path path : classPath) {
            if (Files.isDirectory(path)) {
                if (Files.isRegularFile(path.resolve(entry))) return true;
            } else {
                try (JarFile file = new JarFile(path.toFile())) {
                    if (file.getJarEntry(entry) != null) return true;
                } catch (IOException failure) {
                    throw incompatible("cannot inspect runtime classpath entry " + path, failure);
                }
            }
        }
        return false;
    }

    private static Set<String> readMixinTargets(Path classFile) throws IOException {
        Set<String> targets = new LinkedHashSet<>();
        new ClassReader(Files.readAllBytes(classFile)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(descriptor)) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        if (!"value".equals(name) && !"targets".equals(name)) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String ignored, Object value) {
                                if (value instanceof Type type) {
                                    targets.add(type.getClassName());
                                } else if (value instanceof String target) {
                                    targets.add(target.replace('/', '.'));
                                }
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return targets;
    }

    private static List<String> readServerMixinConfigs(Path path) throws IOException {
        List<String> configs = new ArrayList<>();
        try (JsonReader reader = new JsonReader(Files.newBufferedReader(path, StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                if (!"mixins".equals(reader.nextName())) {
                    reader.skipValue();
                    continue;
                }
                reader.beginArray();
                while (reader.hasNext()) {
                    if (reader.peek() == JsonToken.STRING) {
                        configs.add(reader.nextString());
                        continue;
                    }
                    String config = null;
                    String environment = null;
                    reader.beginObject();
                    while (reader.hasNext()) {
                        switch (reader.nextName()) {
                            case "config" -> config = reader.nextString();
                            case "environment" -> environment = reader.nextString();
                            default -> reader.skipValue();
                        }
                    }
                    reader.endObject();
                    if (config != null && !"client".equals(environment)) configs.add(config);
                }
                reader.endArray();
            }
            reader.endObject();
        }
        return List.copyOf(configs);
    }

    private static MixinConfig readConfig(Path path) throws IOException {
        try (JsonReader reader = new JsonReader(Files.newBufferedReader(path, StandardCharsets.UTF_8))) {
            String packageName = "";
            List<String> common = List.of();
            List<String> server = List.of();
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "package" -> packageName = reader.nextString();
                    case "mixins" -> common = readStrings(reader);
                    case "server" -> server = readStrings(reader);
                    default -> reader.skipValue();
                }
            }
            reader.endObject();
            return new MixinConfig(packageName, common, server);
        }
    }

    private static List<String> readStrings(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue();
            return List.of();
        }
        List<String> values = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) values.add(reader.nextString());
        reader.endArray();
        return List.copyOf(values);
    }

    private record MixinConfig(String packageName, List<String> common, List<String> server) {
        List<String> serverMixins() {
            List<String> result = new ArrayList<>(common.size() + server.size());
            result.addAll(common);
            result.addAll(server);
            return result;
        }
    }
}
