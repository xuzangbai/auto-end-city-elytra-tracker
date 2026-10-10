package com.autoelytra.addon.utils;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 调试输出：把模块执行细节写成 txt 到游戏目录（run/）。
 * 由各模块的 "debug" 开关控制，关闭时不产生任何输出与文件。
 */
public final class DebugLogger {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final List<String> QUEUE = new ArrayList<>();
    private static int queueModule = 0;

    private DebugLogger() {}

    public static Path file(String module) {
        return mc.runDirectory.toPath().resolve(module + "-debug.txt");
    }

    public static void line(String module, String msg) {
        String text = "[" + LocalDateTime.now().format(TIME) + "] " + msg;
        System.out.println("[" + module + "] " + msg);
        try {
            Path f = file(module);
            Files.write(f, (text + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 调试输出失败不影响模块运行
        }
    }

    public static void header(String module, String text) {
        line(module, "");
        line(module, "==================== " + text + " ====================");
    }

    /** 记录一次物品切换 / 当前选中槽快照。 */
    public static void logSlots(String module, String where) {
        if (mc.player == null) return;
        var inv = mc.player.getInventory();
        int slot = inv.getSelectedSlot();
        StringBuilder hb = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            var st = inv.getStack(i);
            hb.append(i).append('=').append(st.isEmpty() ? "-" : st.getItem() + "x" + st.getCount());
            if (i < 8) hb.append(", ");
        }
        line(module, "SLOT[" + where + "] selected=" + slot
            + " mainHand=" + (mc.player.getMainHandStack().isEmpty() ? "EMPTY" : mc.player.getMainHandStack().getItem().toString())
            + " stackInSlot=" + (inv.getStack(slot).isEmpty() ? "EMPTY" : inv.getStack(slot).getItem().toString())
            + " | hotbar: " + hb);
    }

    /** 记录一次方块交互发包的完整参数。 */
    public static void logInteract(String module, String where, BlockPos clicked, Vec3d hitVec,
                                   Direction face) {
        line(module, "SEND[" + where + "] clickedBlock=" + fmt(clicked) + " face=" + face
            + " hitVec=(" + fmt(hitVec.x) + ", " + fmt(hitVec.y) + ", " + fmt(hitVec.z) + ")"
            + " | playerEye=(" + fmt(mc.player.getX()) + ", " + fmt(mc.player.getEyeY()) + ", " + fmt(mc.player.getZ()) + ")"
            + " distToBlockCenter=" + fmt(Math.sqrt(mc.player.getEyePos().squaredDistanceTo(clicked.toCenterPos())))
            + " yaw=" + fmt(mc.player.getYaw()) + " pitch=" + fmt(mc.player.getPitch()));
    }

    /** 记录某个方块位置当前的世界状态。 */
    public static void logBlock(String module, String label, BlockPos pos) {
        if (pos == null || mc.world == null) { line(module, "BLOCK[" + label + "] pos=null"); return; }
        var st = mc.world.getBlockState(pos);
        line(module, "BLOCK[" + label + "] " + fmt(pos)
            + " block=" + st.getBlock()
            + " air=" + st.isAir() + " replaceable=" + st.isReplaceable());
    }

    public static void dumpHotbarCount(String module, String label, net.minecraft.item.Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            var st = mc.player.getInventory().getStack(i);
            if (st.getItem() == item) n += st.getCount();
        }
        line(module, "COUNT[" + label + "] " + item + " = " + n);
    }

    private static String fmt(BlockPos p) {
        return "(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }
}
