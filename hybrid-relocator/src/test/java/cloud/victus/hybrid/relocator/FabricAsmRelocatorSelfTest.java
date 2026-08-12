// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.relocator;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class FabricAsmRelocatorSelfTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("victus-asm-relocator-test");
        Path target = root.resolve("target.jar");
        Path asm = root.resolve("asm.jar");
        writeJar(target, Map.of(
                "org/bukkit/craftbukkit/util/AsmUser.class", asmUser(),
                "net/minecraft/Untouched.class", emptyClass("net/minecraft/Untouched")));
        writeJar(asm, Map.of(
                "org/objectweb/asm/ClassVisitor.class", emptyClass("org/objectweb/asm/ClassVisitor"),
                "org/objectweb/asm/Helper.class", helperClass()));
        Path commons = root.resolve("asm-commons.jar");
        writeJar(commons, Map.of("org/objectweb/asm/commons/Remapper.class", emptyClass("org/objectweb/asm/commons/Remapper")));
        Path cp = root.resolve("classpath.txt");
        Files.writeString(cp, asm + System.lineSeparator() + commons + System.lineSeparator());
        Path first = root.resolve("first.jar");
        Path second = root.resolve("second.jar");
        FabricAsmRelocator.Result a = FabricAsmRelocator.relocate(target, cp, first, root.resolve("first.json"));
        FabricAsmRelocator.Result b = FabricAsmRelocator.relocate(target, cp, second, root.resolve("second.json"));
        check(a.outputSha256().equals(b.outputSha256()), "deterministic output");
        Map<String, byte[]> output = FabricAsmRelocator.readJar(first);
        check(output.containsKey("cloud/victus/shaded/asm/ClassVisitor.class"), "relocated ASM class");
        check(output.containsKey("cloud/victus/shaded/asm/commons/Remapper.class"), "relocated ASM commons class");
        check(output.containsKey("org/bukkit/craftbukkit/util/AsmUser.class"), "Paper user preserved");
        check(output.containsKey("net/minecraft/Untouched.class"), "unselected game class preserved");
        check(a.transformed().size() == 1, "one selected Paper class");
        System.out.println("FabricAsmRelocatorSelfTest: deterministic relocation passed");
    }

    private static byte[] asmUser() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "org/bukkit/craftbukkit/util/AsmUser", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "visitor", "Lorg/objectweb/asm/ClassVisitor;", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] helperClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "org/objectweb/asm/Helper", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "visitor", "Lorg/objectweb/asm/ClassVisitor;", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0); init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); init.visitInsn(Opcodes.RETURN); init.visitMaxs(1, 1); init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void writeJar(Path path, Map<String, byte[]> entries) throws Exception {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> item : new LinkedHashMap<>(entries).entrySet()) {
                JarEntry entry = new JarEntry(item.getKey()); entry.setTime(0L); jar.putNextEntry(entry); jar.write(item.getValue()); jar.closeEntry();
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
