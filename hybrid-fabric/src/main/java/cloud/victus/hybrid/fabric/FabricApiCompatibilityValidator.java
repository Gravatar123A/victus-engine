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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Fail-closed audit of Fabric API mixin classes, target methods, descriptors and bytecode injection
 * points against the class-bearing Victus/Paper runtime.
 */
public final class FabricApiCompatibilityValidator {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final Set<String> INJECTORS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
            "Lcom/llamalad7/mixinextras/injector/WrapOperation;",
            "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;"
    );

    private FabricApiCompatibilityValidator() {
    }

    static void validate(FabricLoaderImpl fabric, Path targetJar, List<Path> targetLibraries, Path reportPath) {
        List<ModuleRoot> roots = new ArrayList<>();
        Set<String> modules = new LinkedHashSet<>();
        List<Issue> discoveryIssues = new ArrayList<>();
        for (ModContainer mod : fabric.getAllMods()) {
            String module = mod.getMetadata().getId();
            if (!module.startsWith("fabric-")) continue;
            modules.add(module);
            for (Path root : mod.getRootPaths()) {
                if (Files.isDirectory(root)) {
                    roots.add(new ModuleRoot(module, new DirectoryRoot(root)));
                } else if (Files.isRegularFile(root)) {
                    roots.add(new ModuleRoot(module, new JarRoot(root)));
                } else {
                    discoveryIssues.add(new Issue(module, "", "", "UNREADABLE_MODULE_ROOT", root.toString(), ""));
                }
            }
        }
        runAudit(roots, modules, targetJar, targetLibraries, reportPath, discoveryIssues);
    }

    /** Audits the nested Fabric API aggregate used by the distribution prepare step. */
    public static void validateAggregate(Path aggregate, Path targetJar, List<Path> targetLibraries, Path reportPath) {
        Path temporary = null;
        try {
            temporary = Files.createTempDirectory("victus-fabric-api-audit");
            List<ModuleRoot> roots = new ArrayList<>();
            Set<String> modules = new LinkedHashSet<>();
            try (JarFile outer = new JarFile(aggregate.toFile())) {
                var entries = outer.stream()
                        .filter(entry -> !entry.isDirectory() && entry.getName().startsWith("META-INF/jars/")
                                && entry.getName().endsWith(".jar"))
                        .sorted(Comparator.comparing(JarEntry::getName))
                        .toList();
                for (JarEntry entry : entries) {
                    Path nested = temporary.resolve(Path.of(entry.getName()).getFileName().toString());
                    try (InputStream input = outer.getInputStream(entry)) {
                        Files.copy(input, nested, StandardCopyOption.REPLACE_EXISTING);
                    }
                    JarRoot root = new JarRoot(nested);
                    String id = readModId(root);
                    if (id.startsWith("fabric-")) {
                        modules.add(id);
                        roots.add(new ModuleRoot(id, root));
                    }
                }
            }
            runAudit(roots, modules, targetJar, targetLibraries, reportPath, List.of());
        } catch (IOException failure) {
            throw incompatible("cannot inspect Fabric API aggregate " + aggregate + ": " + failure, failure);
        } finally {
            if (temporary != null) deleteTree(temporary);
        }
    }

    /** Command-line entry used by scripts/hybrid/prepare-fabric-runtime.py. */
    public static void main(String[] args) {
        Path aggregate = null;
        Path target = null;
        Path report = null;
        List<Path> libraries = new ArrayList<>();
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--aggregate" -> aggregate = Path.of(args[++index]).toAbsolutePath().normalize();
                case "--target" -> target = Path.of(args[++index]).toAbsolutePath().normalize();
                case "--target-library" -> libraries.add(Path.of(args[++index]).toAbsolutePath().normalize());
                case "--report" -> report = Path.of(args[++index]).toAbsolutePath().normalize();
                default -> throw new IllegalArgumentException("unknown Fabric compatibility audit argument: " + args[index]);
            }
        }
        if (aggregate == null || target == null || report == null) {
            throw new IllegalArgumentException("usage: --aggregate <jar> --target <jar> [--target-library <jar>] --report <json>");
        }
        validateAggregate(aggregate, target, List.copyOf(libraries), report);
    }

    private static void runAudit(List<ModuleRoot> roots, Set<String> modules, Path targetJar,
                                 List<Path> targetLibraries, Path reportPath, List<Issue> discoveryIssues) {
        List<Path> gameClassPath = new ArrayList<>(targetLibraries.size() + 1);
        gameClassPath.add(targetJar);
        gameClassPath.addAll(targetLibraries);
        RuntimeClasses runtime = new RuntimeClasses(gameClassPath);
        Counters counters = new Counters();
        List<Issue> issues = new ArrayList<>(discoveryIssues);

        for (ModuleRoot moduleRoot : roots) {
            try {
                auditModule(moduleRoot, runtime, counters, issues);
            } catch (IOException failure) {
                issues.add(new Issue(moduleRoot.module(), "", "", "UNREADABLE_MODULE",
                        failure.toString(), ""));
            }
        }

        issues.sort(Comparator.comparing(Issue::module).thenComparing(Issue::mixin)
                .thenComparing(Issue::type).thenComparing(Issue::selector).thenComparing(Issue::member));
        writeReport(reportPath, modules.size(), targetLibraries.size(), counters, issues);
        if (!issues.isEmpty()) {
            String details = issues.stream().limit(20).map(Issue::render)
                    .collect(java.util.stream.Collectors.joining("; "));
            if (issues.size() > 20) details += "; and " + (issues.size() - 20) + " more (see " + reportPath + ")";
            throw incompatible("mixin bytecode audit found " + issues.size() + " incompatible target(s): " + details, null);
        }
        System.out.println("VICTUS_FABRIC_API_COMPATIBILITY modules=" + modules.size()
                + " mixins=" + counters.mixins + " methodSelectors=" + counters.methodSelectors
                + " injectionPoints=" + counters.injectionPoints + " issues=0 targetLibraries="
                + targetLibraries.size() + " report=" + reportPath);
    }

    private static void auditModule(ModuleRoot source, RuntimeClasses runtime, Counters counters,
                                    List<Issue> issues) throws IOException {
        if (!source.root().exists("fabric.mod.json")) {
            issues.add(new Issue(source.module(), "", "", "MISSING_MOD_METADATA", "fabric.mod.json", ""));
            return;
        }
        for (String configName : readServerMixinConfigs(source.root().readText("fabric.mod.json"))) {
            counters.configs++;
            if (!source.root().exists(configName)) {
                issues.add(new Issue(source.module(), "", "", "MISSING_MIXIN_CONFIG", configName, ""));
                continue;
            }
            MixinConfig config = readConfig(source.root().readText(configName));
            for (String mixin : config.serverMixins()) {
                counters.mixins++;
                String mixinClass = config.packageName().isBlank() ? mixin : config.packageName() + "." + mixin;
                String classResource = mixinClass.replace('.', '/') + ".class";
                if (!source.root().exists(classResource)) {
                    issues.add(new Issue(source.module(), mixinClass, "", "MISSING_MIXIN_CLASS", classResource, ""));
                    continue;
                }
                auditMixin(source.module(), mixinClass, source.root().read(classResource), runtime, counters, issues);
            }
        }
    }

    private static void auditMixin(String module, String mixinClass, byte[] bytes, RuntimeClasses runtime,
                                   Counters counters, List<Issue> issues) {
        ClassNode mixin = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(mixin, ClassReader.SKIP_FRAMES);
        Set<String> targets = readMixinTargets(bytes);
        if (targets.isEmpty()) {
            issues.add(new Issue(module, mixinClass, "", "MISSING_MIXIN_TARGET", "", ""));
            return;
        }
        for (String target : targets) {
            if (!target.startsWith("net.minecraft.")) continue;
            counters.targetClasses++;
            ClassNode targetClass;
            try {
                targetClass = runtime.read(target);
            } catch (IOException failure) {
                issues.add(new Issue(module, mixinClass, target, "UNREADABLE_TARGET_CLASS", failure.toString(), ""));
                continue;
            }
            if (targetClass == null) {
                // Server validation intentionally ignores client-only targets accidentally declared in a common
                // config; Loader's environment stripping owns those and a dedicated server has no client bytecode.
                if (!target.startsWith("net.minecraft.client.")) {
                    issues.add(new Issue(module, mixinClass, target, "MISSING_TARGET_CLASS", "", ""));
                }
                continue;
            }
            auditInjectors(module, mixinClass, target, mixin, targetClass, counters, issues);
        }
    }

    private static void auditInjectors(String module, String mixinClass, String targetName, ClassNode mixin,
                                       ClassNode target, Counters counters, List<Issue> issues) {
        for (MethodNode handler : mixin.methods) {
            for (AnnotationNode annotation : annotations(handler)) {
                if (!INJECTORS.contains(annotation.desc)) continue;
                List<String> selectors = strings(value(annotation, "method"));
                if (selectors.isEmpty()) continue;
                counters.methodSelectors += selectors.size();
                List<MethodNode> selected = new ArrayList<>();
                for (String selector : selectors) {
                    selected.addAll(selectMethods(target, selector));
                }
                selected = selected.stream().distinct().toList();
                if (selected.isEmpty()) {
                    issues.add(new Issue(module, mixinClass, targetName, "MISSING_TARGET_METHOD",
                            String.join("|", selectors), handler.name + handler.desc));
                    continue;
                }
                List<AnnotationNode> points = annotationNodes(value(annotation, "at"));
                for (AnnotationNode point : points) {
                    String atKind = string(value(point, "value"));
                    String member = string(value(point, "target"));
                    if (member.isBlank()) continue;
                    counters.injectionPoints++;
                    int ordinal = integer(value(point, "ordinal"), -1);
                    int matches = countInjectionTargets(selected, atKind, member);
                    if (matches == 0 || ordinal >= matches) {
                        String selector = String.join("|", selectors);
                        String detail = ordinal < 0 ? member : member + " ordinal=" + ordinal;
                        issues.add(new Issue(module, mixinClass, targetName, "MISSING_INJECTION_TARGET",
                                selector, atKind + " " + detail));
                    }
                }
            }
        }
    }

    private static List<MethodNode> selectMethods(ClassNode target, String rawSelector) {
        String selector = rawSelector.trim();
        int ownerEnd = selector.indexOf(';');
        if (selector.startsWith("L") && ownerEnd >= 0) selector = selector.substring(ownerEnd + 1);
        int quantifier = selector.indexOf('{');
        if (quantifier >= 0) selector = selector.substring(0, quantifier);
        if (selector.startsWith("/") && selector.lastIndexOf('/') > 0) {
            return selectRegexMethods(target, selector);
        }
        int descriptorAt = selector.indexOf('(');
        String name = descriptorAt < 0 ? selector : selector.substring(0, descriptorAt);
        String descriptor = descriptorAt < 0 ? "" : selector.substring(descriptorAt);
        boolean wildcard = name.endsWith("*");
        if (wildcard) name = name.substring(0, name.length() - 1);
        List<MethodNode> matches = new ArrayList<>();
        for (MethodNode method : target.methods) {
            boolean nameMatches = wildcard ? method.name.startsWith(name) : method.name.equals(name);
            if (nameMatches && (descriptor.isBlank() || method.desc.equals(descriptor))) matches.add(method);
        }
        return matches;
    }

    private static List<MethodNode> selectRegexMethods(ClassNode target, String selector) {
        int expressionEnd = selector.indexOf('/', 1);
        String expression = selector.substring(1, expressionEnd);
        String suffix = selector.substring(expressionEnd + 1).trim();
        String descriptorEnd = null;
        int descriptorMarker = suffix.indexOf("desc=");
        if (descriptorMarker >= 0) {
            String descriptorPattern = suffix.substring(descriptorMarker + 5).trim();
            if (descriptorPattern.startsWith("/") && descriptorPattern.endsWith("/")
                    && descriptorPattern.length() > 1) {
                descriptorEnd = descriptorPattern.substring(1, descriptorPattern.length() - 1);
            }
        }
        java.util.regex.Pattern namePattern = java.util.regex.Pattern.compile(expression);
        List<MethodNode> matches = new ArrayList<>();
        for (MethodNode method : target.methods) {
            if (namePattern.matcher(method.name).find()
                    && (descriptorEnd == null || java.util.regex.Pattern.compile(descriptorEnd).matcher(method.desc).find())) {
                matches.add(method);
            }
        }
        return matches;
    }

    private static int countInjectionTargets(List<MethodNode> methods, String atKind, String target) {
        MemberReference reference = MemberReference.parse(target);
        int matches = 0;
        if (atKind.equals("NEW")) {
            for (MethodNode method : methods) {
                for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                     instruction = instruction.getNext()) {
                    if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                            && reference.newType().equals(type.desc)) matches++;
                }
            }
            return matches;
        }
        for (MethodNode method : methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                 instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call && reference.matches(call)) {
                    matches++;
                } else if (instruction instanceof FieldInsnNode field && reference.matches(field)) {
                    matches++;
                }
            }
        }
        return matches;
    }

    private static List<AnnotationNode> annotations(MethodNode method) {
        List<AnnotationNode> result = new ArrayList<>();
        if (method.visibleAnnotations != null) result.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) result.addAll(method.invisibleAnnotations);
        return result;
    }

    private static Object value(AnnotationNode annotation, String name) {
        if (annotation.values == null) return null;
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (name.equals(annotation.values.get(index))) return annotation.values.get(index + 1);
        }
        return null;
    }

    private static List<String> strings(Object value) {
        if (value instanceof String string) return List.of(string);
        if (value instanceof List<?> list) return list.stream().filter(String.class::isInstance)
                .map(String.class::cast).toList();
        return List.of();
    }

    private static List<AnnotationNode> annotationNodes(Object value) {
        if (value instanceof AnnotationNode annotation) return List.of(annotation);
        if (value instanceof List<?> list) return list.stream().filter(AnnotationNode.class::isInstance)
                .map(AnnotationNode.class::cast).toList();
        return List.of();
    }

    private static String string(Object value) {
        return value instanceof String string ? string : "";
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Integer number ? number : fallback;
    }

    private static Set<String> readMixinTargets(byte[] bytes) {
        Set<String> targets = new LinkedHashSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!MIXIN.equals(descriptor)) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        if (!"value".equals(name) && !"targets".equals(name)) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String ignored, Object value) {
                                if (value instanceof Type type) targets.add(type.getClassName());
                                else if (value instanceof String target) targets.add(target.replace('/', '.'));
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return targets;
    }

    private static String readModId(ResourceRoot root) throws IOException {
        if (!root.exists("fabric.mod.json")) return "";
        try (JsonReader reader = new JsonReader(new StringReader(root.readText("fabric.mod.json")))) {
            reader.beginObject();
            while (reader.hasNext()) {
                if ("id".equals(reader.nextName())) return reader.nextString();
                reader.skipValue();
            }
            reader.endObject();
        }
        return "";
    }

    private static List<String> readServerMixinConfigs(String json) throws IOException {
        List<String> configs = new ArrayList<>();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
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

    private static MixinConfig readConfig(String json) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
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

    private static void writeReport(Path path, int modules, int targetLibraries, Counters counters,
                                    List<Issue> issues) {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"format\": 1,\n  \"compatible\": ").append(issues.isEmpty())
                .append(",\n  \"modules\": ").append(modules)
                .append(",\n  \"mixinConfigs\": ").append(counters.configs)
                .append(",\n  \"mixins\": ").append(counters.mixins)
                .append(",\n  \"targetClasses\": ").append(counters.targetClasses)
                .append(",\n  \"methodSelectors\": ").append(counters.methodSelectors)
                .append(",\n  \"injectionPoints\": ").append(counters.injectionPoints)
                .append(",\n  \"targetLibraries\": ").append(targetLibraries)
                .append(",\n  \"issues\": [");
        for (int index = 0; index < issues.size(); index++) {
            Issue issue = issues.get(index);
            if (index > 0) json.append(',');
            json.append("\n    {\"module\":\"").append(escape(issue.module()))
                    .append("\",\"mixin\":\"").append(escape(issue.mixin()))
                    .append("\",\"targetClass\":\"").append(escape(issue.targetClass()))
                    .append("\",\"type\":\"").append(escape(issue.type()))
                    .append("\",\"selector\":\"").append(escape(issue.selector()))
                    .append("\",\"member\":\"").append(escape(issue.member())).append("\"}");
        }
        if (!issues.isEmpty()) json.append('\n').append("  ");
        json.append("]\n}\n");
        try {
            Path parent = path.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw incompatible("cannot write compatibility report " + path, failure);
        }
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.toString();
    }

    private static IllegalStateException incompatible(String detail, Throwable cause) {
        String message = "FABRIC_API_MODULE_INCOMPATIBLE: " + detail;
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup after an audit; the compatibility result is more important.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    private interface ResourceRoot {
        boolean exists(String name) throws IOException;

        byte[] read(String name) throws IOException;

        default String readText(String name) throws IOException {
            return new String(read(name), StandardCharsets.UTF_8);
        }
    }

    private record DirectoryRoot(Path path) implements ResourceRoot {
        @Override
        public boolean exists(String name) {
            return Files.isRegularFile(path.resolve(name));
        }

        @Override
        public byte[] read(String name) throws IOException {
            return Files.readAllBytes(path.resolve(name));
        }
    }

    private record JarRoot(Path path) implements ResourceRoot {
        @Override
        public boolean exists(String name) throws IOException {
            try (JarFile jar = new JarFile(path.toFile())) {
                return jar.getJarEntry(name) != null;
            }
        }

        @Override
        public byte[] read(String name) throws IOException {
            try (JarFile jar = new JarFile(path.toFile())) {
                JarEntry entry = jar.getJarEntry(name);
                if (entry == null) throw new IOException(name + " missing from " + path);
                try (InputStream input = jar.getInputStream(entry)) {
                    return input.readAllBytes();
                }
            }
        }
    }

    private static final class RuntimeClasses {
        private final List<Path> classPath;
        private final Map<String, ClassNode> found = new HashMap<>();
        private final Set<String> absent = new LinkedHashSet<>();

        private RuntimeClasses(List<Path> classPath) {
            this.classPath = List.copyOf(classPath);
        }

        ClassNode read(String className) throws IOException {
            if (found.containsKey(className)) return found.get(className);
            if (absent.contains(className)) return null;
            String entry = className.replace('.', '/') + ".class";
            byte[] bytes = null;
            for (Path path : classPath) {
                if (Files.isDirectory(path)) {
                    Path classFile = path.resolve(entry);
                    if (Files.isRegularFile(classFile)) {
                        bytes = Files.readAllBytes(classFile);
                        break;
                    }
                } else {
                    try (JarFile jar = new JarFile(path.toFile())) {
                        JarEntry jarEntry = jar.getJarEntry(entry);
                        if (jarEntry != null) {
                            try (InputStream input = jar.getInputStream(jarEntry)) {
                                bytes = input.readAllBytes();
                            }
                            break;
                        }
                    }
                }
            }
            if (bytes == null) {
                absent.add(className);
                return null;
            }
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
            found.put(className, node);
            return node;
        }
    }

    private record ModuleRoot(String module, ResourceRoot root) {
    }

    private record MixinConfig(String packageName, List<String> common, List<String> server) {
        List<String> serverMixins() {
            List<String> result = new ArrayList<>(common.size() + server.size());
            result.addAll(common);
            result.addAll(server);
            return result;
        }
    }

    private record Issue(String module, String mixin, String targetClass, String type,
                         String selector, String member) {
        String render() {
            String location = module + "/" + mixin + " -> " + targetClass;
            return type + " " + location + (selector.isBlank() ? "" : " method=" + selector)
                    + (member.isBlank() ? "" : " target=" + member);
        }
    }

    private record MemberReference(String owner, String name, String descriptor, boolean field) {
        static MemberReference parse(String target) {
            String value = target.trim();
            int ownerEnd = value.indexOf(';');
            if (!value.startsWith("L") || ownerEnd < 0) {
                if (value.startsWith("(")) return new MemberReference("", "<init>", value, false);
                String owner = value.replace('.', '/');
                if (owner.startsWith("L") && owner.endsWith(";")) owner = owner.substring(1, owner.length() - 1);
                return new MemberReference(owner, "", "", false);
            }
            String owner = value.substring(1, ownerEnd);
            String member = value.substring(ownerEnd + 1);
            int method = member.indexOf('(');
            if (method >= 0) return new MemberReference(owner, member.substring(0, method), member.substring(method), false);
            int field = member.indexOf(':');
            if (field >= 0) return new MemberReference(owner, member.substring(0, field), member.substring(field + 1), true);
            return new MemberReference(owner, member, "", false);
        }

        boolean matches(MethodInsnNode call) {
            return !field && owner.equals(call.owner) && name.equals(call.name) && descriptor.equals(call.desc);
        }

        boolean matches(FieldInsnNode access) {
            return field && owner.equals(access.owner) && name.equals(access.name) && descriptor.equals(access.desc);
        }

        String newType() {
            if (!owner.isBlank()) return owner;
            if (descriptor.startsWith("(") && descriptor.contains(")L") && descriptor.endsWith(";")) {
                return descriptor.substring(descriptor.indexOf(")L") + 2, descriptor.length() - 1);
            }
            return "";
        }
    }

    private static final class Counters {
        int configs;
        int mixins;
        int targetClasses;
        int methodSelectors;
        int injectionPoints;
    }
}
