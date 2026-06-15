package com.autoelytra.addon.modules;

import com.autoelytra.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class Run extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Integer> addY = sg.add(new IntSetting.Builder().name("add-y").description("展示框Y上加几格").defaultValue(6).min(1).max(20).build());

    private enum S { FIND, GOTO, WAIT, DONE }
    private S s; private int t;
    private BlockPos target;

    public Run() { super(AddonTemplate.CATEGORY, "run", "寻路到展示框上方然后开LockEnd"); }

    @Override public void onActivate() { if (mc.player == null) { toggle(); return; } s = S.FIND; t = 0; target = null; }

    @EventHandler void tick(TickEvent.Pre e) { if (mc.player == null) return; t++;
        switch (s) { case FIND -> find(); case GOTO -> go(); case WAIT -> waitArrive(); case DONE -> {} } }

    void find() {
        String w = detW(); if (w == null) { toggle(); return; }
        Path f = Paths.get("tizi", w, "ItemFrame.json");
        if (!Files.isRegularFile(f)) { info("无ItemFrame.json"); toggle(); return; }
        BlockPos nearest = null; double best = Double.MAX_VALUE;
        try {
            String raw = Files.readString(f).trim();
            if (raw.startsWith("[") && raw.endsWith("]")) {
                double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
                String in = raw.substring(1, raw.length() - 1).trim();
                if (!in.isEmpty()) for (String p : in.split("},")) {
                    String t2 = p.trim(); if (!t2.endsWith("}")) t2 += "}";
                    try {
                        int xi = t2.indexOf("\"x\":"), yi = t2.indexOf("\"y\":"), zi = t2.indexOf("\"z\":");
                        if (xi < 0 || yi < 0 || zi < 0) continue;
                        int x = Integer.parseInt(t2.substring(xi + 4, t2.indexOf(",", xi)));
                        int y = Integer.parseInt(t2.substring(yi + 4, t2.indexOf(",", yi)));
                        int z = Integer.parseInt(t2.substring(zi + 4, t2.indexOf("}", zi)));
                        BlockPos bp = new BlockPos(x, y, z);
                        double d = bp.getSquaredDistance(px, py, pz);
                        if (d < best) { best = d; nearest = bp; }
                    } catch (Exception ignored) {}
                }
            }
        } catch (IOException ignored) {}
        if (nearest == null) { info("无坐标"); toggle(); return; }
        target = new BlockPos(nearest.getX(), nearest.getY() + addY.get(), nearest.getZ());
        s = S.GOTO; t = 0;
    }

    void go() {
        if (mc.player.networkHandler != null) {
            mc.player.networkHandler.sendChatMessage(
                "#goto " + target.getX() + " " + target.getY() + " " + target.getZ());
        }
        info("寻路: " + target.getX() + " " + target.getY() + " " + target.getZ());
        s = S.WAIT; t = 0;
    }

    void waitArrive() {
        if (t < 20) return; // wait for Baritone to start
        if (target != null
            && mc.player.getBlockX() == target.getX()
            && mc.player.getBlockZ() == target.getZ()) {
            LockEnd le = Modules.get().get(LockEnd.class);
            if (le != null && !le.isActive()) le.toggle();
            toggle();
        }
    }

    String detW() {
        try { if (mc.getCurrentServerEntry() != null) return "Multiplayer_" + mc.getCurrentServerEntry().address.replace(":", "_"); } catch (Exception ignored) {}
        try { if (mc.getServer() != null) { var sp = mc.getServer().getSaveProperties(); if (sp != null) return sp.getLevelName(); } } catch (Exception ignored) {}
        return null;
    }

    void info(String m) { System.out.println("[Run] " + m); if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("§5[Run] " + m), false); }
}
