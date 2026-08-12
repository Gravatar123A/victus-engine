// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.relocator;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

/** Builds a deterministic Fabric-only server target with Paper's ASM users isolated. */
public final class FabricAsmRelocator {
    static final String FROM = "org/objectweb/asm";
    static final String TO = "cloud/victus/shaded/asm";
    static final String PAPER_PREFIX = "org/bukkit/craftbukkit/";

    private FabricAsmRelocator() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            throw new IllegalArgumentException("usage: <target.jar> <runtime-classpath.txt> <output.jar> <provenance.json>");
        }
        relocate(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
    }

    public static Result relocate(Path target, Path classpathFile, Path output, Path provenance) throws Exception {
        Map<String, byte[]> targetEntries = readJar(target);
        List<Path> runtime = Files.readAllLines(classpathFile).stream()
                .map(String::trim).filter(s -> !s.isEmpty()).map(Path::of).filter(Files::isRegularFile).toList();
        List<Path> asmJars = runtime.stream().filter(FabricAsmRelocator::containsAsm).sorted().toList();
        if (asmJars.isEmpty()) throw new IllegalStateException("FABRIC_ASM_RELOCATION_INPUT_MISSING: no ASM runtime jars");

        Map<String, byte[]> out = new LinkedHashMap<>();
        List<String> transformed = new ArrayList<>();
        targetEntries.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String name = entry.getKey();
            byte[] data = entry.getValue();
            if (name.endsWith(".class") && name.startsWith(PAPER_PREFIX) && contains(data, FROM)) {
                data = remap(data);
                transformed.add(name);
            }
            if (!signature(name)) out.put(name, data);
        });
        if (transformed.isEmpty()) throw new IllegalStateException("FABRIC_ASM_RELOCATION_NO_TARGETS");

        Set<String> embedded = new LinkedHashSet<>();
        for (Path asmJar : asmJars) {
            for (Map.Entry<String, byte[]> entry : readJar(asmJar).entrySet()) {
                String name = entry.getKey();
                if (!name.endsWith(".class") || !name.startsWith(FROM + "/")) continue;
                byte[] data = remap(entry.getValue());
                String relocated = TO + name.substring(FROM.length());
                out.putIfAbsent(relocated, data);
                embedded.add(relocated);
            }
        }
        writeJar(output, out);
        validate(targetEntries, out, transformed, embedded);
        String inputHash = sha256(target);
        String outputHash = sha256(output);
        String json = json(inputHash, outputHash, asmJars, transformed, embedded);
        Files.createDirectories(provenance.toAbsolutePath().getParent());
        Files.writeString(provenance, json, StandardCharsets.UTF_8);
        return new Result(inputHash, outputHash, List.copyOf(transformed), List.copyOf(embedded));
    }

    private static byte[] remap(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(0);
        Remapper mapper = new Remapper() {
            @Override public String map(String internalName) {
                return internalName.equals(FROM) || internalName.startsWith(FROM + "/")
                        ? TO + internalName.substring(FROM.length()) : internalName;
            }
        };
        reader.accept(new ClassRemapper(writer, mapper), 0);
        return writer.toByteArray();
    }

    private static void validate(Map<String, byte[]> original, Map<String, byte[]> output,
                                 List<String> transformed, Set<String> embedded) {
        for (String name : transformed) {
            byte[] data = output.get(name);
            if (contains(data, FROM)) throw new IllegalStateException("unrelocated ASM reference in " + name);
            if (!contains(data, TO)) throw new IllegalStateException("relocated namespace missing in " + name);
        }
        Set<String> changed = new LinkedHashSet<>(transformed);
        for (Map.Entry<String, byte[]> entry : original.entrySet()) {
            String name = entry.getKey();
            if (signature(name) || changed.contains(name)) continue;
            byte[] actual = output.get(name);
            if (actual == null || !MessageDigest.isEqual(entry.getValue(), actual)) {
                throw new IllegalStateException("non-selected target entry changed: " + name);
            }
        }
        if (embedded.stream().noneMatch(name -> name.equals(TO + "/ClassVisitor.class"))) {
            throw new IllegalStateException("relocated ClassVisitor missing");
        }
        for (String name : embedded) {
            byte[] data = output.get(name);
            if (contains(data, FROM)) throw new IllegalStateException("relocated ASM self-reference leaked in " + name);
        }
    }

    private static boolean containsAsm(Path path) {
        try { return readJar(path).containsKey(FROM + "/ClassVisitor.class"); }
        catch (Exception ignored) { return false; }
    }

    static Map<String, byte[]> readJar(Path path) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (InputStream stream = Files.newInputStream(path); JarInputStream jar = new JarInputStream(stream)) {
            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                if (!entry.isDirectory()) entries.put(entry.getName(), jar.readAllBytes());
            }
        }
        return entries;
    }

    private static void writeJar(Path output, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(output))) {
            for (Map.Entry<String, byte[]> value : entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                JarEntry entry = new JarEntry(value.getKey());
                entry.setTime(0L);
                jar.putNextEntry(entry);
                jar.write(value.getValue());
                jar.closeEntry();
            }
        }
    }

    private static boolean signature(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("META-INF/") && upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA");
    }

    private static boolean contains(byte[] bytes, String value) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(value);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String json(String input, String output, List<Path> asm, List<String> transformed, Set<String> embedded) throws Exception {
        StringBuilder value = new StringBuilder("{\n  \"format\":1,\n  \"inputSha256\":\"").append(input)
                .append("\",\n  \"outputSha256\":\"").append(output).append("\",\n  \"asmInputs\":[");
        for (int i = 0; i < asm.size(); i++) {
            if (i > 0) value.append(',');
            value.append("{\"path\":\"").append(escape(asm.get(i).toAbsolutePath().toString()))
                    .append("\",\"sha256\":\"").append(sha256(asm.get(i))).append("\"}");
        }
        value.append("],\n  \"transformedClasses\":").append(strings(transformed))
                .append(",\n  \"embeddedClasses\":").append(strings(embedded.stream().sorted().toList())).append("\n}\n");
        return value.toString();
    }

    private static String strings(List<String> values) {
        return values.stream().map(v -> "\"" + escape(v) + "\"").collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }

    public record Result(String inputSha256, String outputSha256, List<String> transformed, List<String> embedded) {}
}
