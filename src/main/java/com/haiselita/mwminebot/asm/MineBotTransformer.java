package com.haiselita.mwminebot.asm;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Branchless bytecode hooks (no stackmap frames needed):
 * 1) Minecraft.sendClickBlockToController(Z)V: leftClick = MWMineBot.resolveLeftClick(leftClick)
 * 2) GuiScreen.doesGuiPauseGame()Z: return MWMineBot.shouldPauseScreen(this)
 */
public class MineBotTransformer implements IClassTransformer {

    private static final String HOOK = "com/haiselita/mwminebot/MWMineBot";

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null) return null;
        if ("net.minecraft.client.Minecraft".equals(transformedName)) {
            return patchMinecraft(bytes);
        }
        if ("net.minecraft.client.gui.GuiScreen".equals(transformedName)) {
            return patchGuiScreen(bytes);
        }
        return bytes;
    }

    private static byte[] patchMinecraft(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode m : cn.methods) {
            if (!"(Z)V".equals(m.desc)) continue;
            if (!"func_147115_a".equals(m.name) && !"sendClickBlockToController".equals(m.name)) continue;
            InsnList ins = new InsnList();
            ins.add(new VarInsnNode(Opcodes.ILOAD, 1));
            ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "resolveLeftClick", "(Z)Z", false));
            ins.add(new VarInsnNode(Opcodes.ISTORE, 1));
            m.instructions.insert(ins);
            patched = true;
        }
        if (!patched) return bytes;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static byte[] patchGuiScreen(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode m : cn.methods) {
            if (!"()Z".equals(m.desc)) continue;
            if (!"func_73868_f".equals(m.name) && !"doesGuiPauseGame".equals(m.name)) continue;
            for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                if (n.getOpcode() == Opcodes.IRETURN) {
                    InsnList ins = new InsnList();
                    ins.add(new InsnNode(Opcodes.POP));
                    ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "shouldPauseScreen", "(Ljava/lang/Object;)Z", false));
                    m.instructions.insertBefore(n, ins);
                }
            }
            patched = true;
        }
        if (!patched) return bytes;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }
}
