// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.fabric;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Executable regression proof that method descriptors and injection bytecode are audited pre-launch. */
public final class FabricApiCompatibilityValidatorSelfTest {
    private FabricApiCompatibilityValidatorSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("fabric-api-validator-self-test");
        Path target = temp.resolve("target.jar");
        Path aggregate = temp.resolve("aggregate.jar");
        Path report = temp.resolve("compatibility.json");
        writeTarget(target);
        writeAggregate(aggregate, writeModule());

        try {
            FabricApiCompatibilityValidator.validateAggregate(aggregate, target, List.of(), report);
            throw new AssertionError("missing injection target unexpectedly passed");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("MISSING_INJECTION_TARGET"),
                    "injection target mismatch is actionable");
            String json = Files.readString(report);
            check(json.contains("\"type\":\"MISSING_INJECTION_TARGET\""),
                    "machine-readable report records injection mismatch");
                check(json.contains("expected()V"), "report records the absent bytecode member descriptor");
        }
        System.out.println("FabricApiCompatibilityValidatorSelfTest: descriptor mismatch detected before Mixin launch");
    }

    private static void writeTarget(Path jar) throws Exception {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/test/AuditTarget", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "boot", "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            write(output, "net/minecraft/test/AuditTarget.class", writer.toByteArray());
        }
    }

    private static byte[] writeModule() throws Exception {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC,
                "net/fabricmc/fabric/mixin/test/AuditMixin", null, "java/lang/Object", null);
        var mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        var values = mixin.visitArray("value");
        values.visit(null, Type.getObjectType("net/minecraft/test/AuditTarget"));
        values.visitEnd();
        mixin.visitEnd();
        MethodVisitor handler = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "hook", "()V", null, null);
        var inject = handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        var methods = inject.visitArray("method");
        methods.visit(null, "boot()V");
        methods.visitEnd();
        var points = inject.visitArray("at");
        var point = points.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        point.visit("value", "INVOKE");
        point.visit("target", "Lnet/minecraft/test/AuditTarget;expected()V");
        point.visitEnd();
        points.visitEnd();
        inject.visitEnd();
        handler.visitCode();
        handler.visitInsn(Opcodes.RETURN);
        handler.visitMaxs(0, 0);
        handler.visitEnd();
        writer.visitEnd();

        Path module = Files.createTempFile("fabric-validator-module", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(module))) {
            write(output, "fabric.mod.json", """
                    {"schemaVersion":1,"id":"fabric-audit-fixture","version":"1",
                     "mixins":["audit.mixins.json"]}
                    """.getBytes(StandardCharsets.UTF_8));
            write(output, "audit.mixins.json", """
                    {"required":true,"package":"net.fabricmc.fabric.mixin.test","mixins":["AuditMixin"]}
                    """.getBytes(StandardCharsets.UTF_8));
            write(output, "net/fabricmc/fabric/mixin/test/AuditMixin.class", writer.toByteArray());
        }
        byte[] bytes = Files.readAllBytes(module);
        Files.delete(module);
        return bytes;
    }

    private static void writeAggregate(Path jar, byte[] module) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            write(output, "META-INF/jars/fabric-audit-fixture-1.jar", module);
        }
    }

    private static void write(JarOutputStream output, String name, byte[] bytes) throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
