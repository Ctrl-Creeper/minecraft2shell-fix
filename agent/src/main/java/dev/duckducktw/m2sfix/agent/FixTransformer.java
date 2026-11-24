package dev.duckducktw.m2sfix.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Splices two small calls into {@code org.lwjgl.util.freetype.FreeType}.
 *
 * <pre>
 * FT_Load_Glyph(face, index, flags):
 *     flags = AgentBridge.adjustFlags(face, flags);     // entry
 *
 * FT_New_Memory_Face(library, file, size, aface):
 *     return AgentBridge.afterNewFace(file, size, aface, result);   // before every return
 * </pre>
 *
 * <p>Raw ASM is used on purpose: both hooks are three instructions of straight
 * line code, and this keeps the agent free of any instrumentation framework
 * that could disagree with a particular JVM/Minecraft combination. The injected
 * code contains no jumps, so existing stack map frames stay valid and only
 * {@code maxStack}/{@code maxLocals} have to be recomputed.</p>
 */
final class FixTransformer implements ClassFileTransformer {

    static final String TARGET_INTERNAL_NAME = "org/lwjgl/util/freetype/FreeType";
    static final String TARGET_BINARY_NAME = "org.lwjgl.util.freetype.FreeType";

    private static final String BRIDGE_INTERNAL_NAME = "dev/duckducktw/m2sfix/agent/AgentBridge";

    private static final String NEW_FACE_NAME = "FT_New_Memory_Face";
    private static final String NEW_FACE_DESC =
            "(JLjava/nio/ByteBuffer;JLorg/lwjgl/PointerBuffer;)I";
    private static final String LOAD_GLYPH_NAME = "FT_Load_Glyph";
    private static final String LOAD_GLYPH_DESC = "(Lorg/lwjgl/util/freetype/FT_Face;II)I";

    /** A free slot above the parameters of {@code FT_New_Memory_Face} (locals 0..5 are used). */
    private static final int NEW_FACE_TEMP_LOCAL = 6;

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        if (!TARGET_INTERNAL_NAME.equals(className)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new PatchingVisitor(writer), 0);
            return writer.toByteArray();
        } catch (Throwable ignored) {
            // Never break the class: fall back to the original bytes.
            return null;
        }
    }

    private static final class PatchingVisitor extends ClassVisitor {

        PatchingVisitor(ClassVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (NEW_FACE_NAME.equals(name) && NEW_FACE_DESC.equals(descriptor)) {
                return new NewFaceVisitor(delegate);
            }
            if (LOAD_GLYPH_NAME.equals(name) && LOAD_GLYPH_DESC.equals(descriptor)) {
                return new LoadGlyphVisitor(delegate);
            }
            return delegate;
        }
    }

    /** Inserts {@code flags = AgentBridge.adjustFlags(face, flags)} at method entry. */
    private static final class LoadGlyphVisitor extends MethodVisitor {

        private boolean injected;

        LoadGlyphVisitor(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            if (injected) {
                return;
            }
            injected = true;
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.ILOAD, 2);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    BRIDGE_INTERNAL_NAME,
                    "adjustFlags",
                    "(Ljava/lang/Object;I)I",
                    false);
            super.visitVarInsn(Opcodes.ISTORE, 2);
        }
    }

    /** Wraps every {@code IRETURN} with {@code AgentBridge.afterNewFace(...)}. */
    private static final class NewFaceVisitor extends MethodVisitor {

        NewFaceVisitor(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.IRETURN) {
                super.visitVarInsn(Opcodes.ISTORE, NEW_FACE_TEMP_LOCAL);
                super.visitVarInsn(Opcodes.ALOAD, 2); // file
                super.visitVarInsn(Opcodes.LLOAD, 3); // file_size
                super.visitVarInsn(Opcodes.ALOAD, 5); // aface
                super.visitVarInsn(Opcodes.ILOAD, NEW_FACE_TEMP_LOCAL);
                super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        BRIDGE_INTERNAL_NAME,
                        "afterNewFace",
                        "(Ljava/nio/ByteBuffer;JLjava/lang/Object;I)I",
                        false);
            }
            super.visitInsn(opcode);
        }
    }
}
