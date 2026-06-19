package com.autoelytra.addon.modules;

import com.autoelytra.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class LockEnd extends Module {
    private static final Path TIZI_DIR = Paths.get("tizi");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private enum Mode { WaypointLock, SpiralFlight }
    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("到达后开启的功能")
        .defaultValue(Mode.WaypointLock)
        .build()
    );

    private final Setting<Integer> aimAbove = sgGeneral.add(new IntSetting.Builder()
        .name("aim-above")
        .description("在目标坐标上方多少格创建锁定点")
        .defaultValue(40).min(10).sliderMax(200)
        .build()
    );

    private final Setting<Integer> arriveDistance = sgGeneral.add(new IntSetting.Builder()
        .name("arrive-distance")
        .description("距锁定点此范围内写入黑名单并关闭")
        .defaultValue(2).min(1).max(20)
        .build()
    );

    private final Setting<Boolean> autoFirework = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-firework")
        .description("玩家高度低于锁定点时自动释放烟花")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> fireworkInterval = sgGeneral.add(new IntSetting.Builder()
        .name("firework-interval")
        .description("烟花释放间隔(tick)，20=1秒")
        .defaultValue(80).min(10).sliderMax(200)
        .visible(autoFirework::get)
        .build()
    );

    private final Setting<Boolean> quickSwap = sgGeneral.add(new BoolSetting.Builder()
        .name("quick-swap")
        .description("从背包任意位置切换烟花")
        .defaultValue(false)
        .visible(autoFirework::get)
        .build()
    );

    private BlockPos lockTarget;
    private BlockPos ladderPos;
    private int fireworkCooldown;
    private int jumpTimer;
    private boolean arrived;
    private int sequenceTimer;

    public LockEnd() {
        super(AddonTemplate.CATEGORY, "lock-end",
            "读取zuobiao.json最近坐标，在上方创建锁定点，模拟跳跃到达后写入Lockblacklist");
    }

    @Override
    public void onActivate() {
        lockTarget = null;
        ladderPos = null;
        fireworkCooldown = 0;
        jumpTimer = 0;
        arrived = false;
        sequenceTimer = 0;

        BlockPos nearest = findNearest();
        if (nearest == null) {
            info("未找到zuobiao.json或无有效坐标");
            toggle();
            return;
        }

        ladderPos = nearest;
        int above = aimAbove.get();
        lockTarget = new BlockPos(nearest.getX(), nearest.getY() + above, nearest.getZ());

        info("锁定起飞: " + lockTarget.getX() + " " + lockTarget.getY() + " " + lockTarget.getZ()
            + "  距离: " + (int) Math.sqrt(lockTarget.getSquaredDistance(
                mc.player.getX(), mc.player.getY(), mc.player.getZ())) + "m");
    }

    @Override
    public void onDeactivate() {
        mc.options.jumpKey.setPressed(false);
        lockTarget = null;
        ladderPos = null;
        arrived = false;
        sequenceTimer = 0;
    }

    @EventHandler
    private void onPreTick(TickEvent.Pre event) {
        if (mc.player == null || lockTarget == null) return;

        double dist = Math.sqrt(lockTarget.getSquaredDistance(
            mc.player.getX(), mc.player.getY(), mc.player.getZ()));
        if (dist <= arriveDistance.get() && !arrived) {
            arrived = true;
            writeBlacklist();
            info("已到达锁定点，写入Lockblacklist，启动后续流程");
            // Open mode-specific module + ElytraInfiniteFlight
            if (mode.get() == Mode.SpiralFlight) {
                SpiralFlight sf = Modules.get().get(SpiralFlight.class);
                if (sf != null && !sf.isActive()) sf.toggle();
            } else {
                WaypointLock wl = Modules.get().get(WaypointLock.class);
                if (wl != null && !wl.isActive()) wl.toggle();
            }
            ElytraInfiniteFlight eif = Modules.get().get(ElytraInfiniteFlight.class);
            if (eif != null && !eif.isActive()) eif.toggle();
            sequenceTimer = 0;
            return;
        }

        // Post-arrival sequence: toggle WaypointLock for 2s, then LockStart
        if (arrived) {
            sequenceTimer++;
            if (sequenceTimer >= 40) { // 2 seconds
                // Open LockStart, keep WaypointLock + ElytraInfiniteFlight running
                LockStart ls = Modules.get().get(LockStart.class);
                if (ls != null && !ls.isActive()) ls.toggle();
                info("流程完成，关闭 LockEnd");
                toggle();
                return;
            }
            return;
        }

        // Simulate spacebar every 4 ticks (0.2s) until gliding
        if (!mc.player.isGliding() && jumpTimer <= 0) {
            mc.options.jumpKey.setPressed(true);
            jumpTimer = 2;
        }
        if (jumpTimer > 0) {
            if (jumpTimer == 1) mc.options.jumpKey.setPressed(false);
            jumpTimer--;
        }

        if (autoFirework.get() && mc.player.isGliding() && mc.player.getY() < lockTarget.getY()) {
            if (fireworkCooldown > 0) fireworkCooldown--;
            else releaseFirework();
        }

        double dx = lockTarget.getX() + 0.5 - mc.player.getX();
        double dy = lockTarget.getY() + 0.5 - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double dz = lockTarget.getZ() + 0.5 - mc.player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
    }

    @EventHandler
    private void onPostTick(TickEvent.Post event) {
        if (mc.player == null || lockTarget == null) return;

        double dx = lockTarget.getX() + 0.5 - mc.player.getX();
        double dy = lockTarget.getY() + 0.5 - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double dz = lockTarget.getZ() + 0.5 - mc.player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
    }

    private void releaseFirework() {
        if (quickSwap.get()) {
            FindItemResult result = InvUtils.find(Items.FIREWORK_ROCKET);
            if (!result.found()) return;
            int prevSlot = mc.player.getInventory().getSelectedSlot();
            if (!result.isHotbar()) InvUtils.quickSwap().fromId(prevSlot).to(result.slot());
            else mc.player.getInventory().setSelectedSlot(result.slot());
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            if (!result.isHotbar()) InvUtils.quickSwap().fromId(prevSlot).to(result.slot());
            else mc.player.getInventory().setSelectedSlot(prevSlot);
        } else {
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getStack(i).getItem() == Items.FIREWORK_ROCKET) {
                    int prevSlot = mc.player.getInventory().getSelectedSlot();
                    mc.player.getInventory().setSelectedSlot(i);
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    mc.player.getInventory().setSelectedSlot(prevSlot);
                    break;
                }
            }
        }
        fireworkCooldown = fireworkInterval.get();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (lockTarget == null || mc.player == null) return;
        Box box = new Box(lockTarget);
        event.renderer.box(box, new SettingColor(0, 255, 0, 120),
            new SettingColor(0, 255, 0, 120), ShapeMode.Both, 0);
    }

    private BlockPos findNearest() {
        String world = detectWorld();
        if (world == null) return null;

        Path file = TIZI_DIR.resolve(world).resolve("zuobiao.json");
        if (!Files.isRegularFile(file)) return null;

        // Load blacklist
        Set<Long> blacklist = new HashSet<>();
        Path blFile = TIZI_DIR.resolve(world).resolve("Lockblacklist.json");
        if (Files.isRegularFile(blFile)) {
            try {
                String raw = Files.readString(blFile).trim();
                if (raw.startsWith("[") && raw.endsWith("]")) {
                    String inner = raw.substring(1, raw.length() - 1).trim();
                    if (!inner.isEmpty()) {
                        for (String part : inner.split("},")) {
                            String t = part.trim();
                            if (!t.endsWith("}")) t += "}";
                            try {
                                int xi = t.indexOf("\"x\":"), zi = t.indexOf("\"z\":");
                                if (xi < 0 || zi < 0) continue;
                                int x = Integer.parseInt(t.substring(xi + 4, t.indexOf(",", xi)));
                                int z = Integer.parseInt(t.substring(zi + 4, t.indexOf("}", zi)));
                                blacklist.add((long) x << 32 | (z & 0xFFFFFFFFL));
                            } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (IOException ignored) {}
        }

        List<BlockPos> positions = new ArrayList<>();
        try {
            String raw = Files.readString(file).trim();
            if (raw.startsWith("[") && raw.endsWith("]")) {
                String inner = raw.substring(1, raw.length() - 1).trim();
                if (!inner.isEmpty()) {
                    for (String part : inner.split("},")) {
                        String t = part.trim();
                        if (!t.endsWith("}")) t += "}";
                        try {
                            int xi = t.indexOf("\"x\":"), yi = t.indexOf("\"y\":"), zi = t.indexOf("\"z\":");
                            if (xi < 0 || yi < 0 || zi < 0) continue;
                            int x = Integer.parseInt(t.substring(xi + 4, t.indexOf(",", xi)));
                            int y = Integer.parseInt(t.substring(yi + 4, t.indexOf(",", yi)));
                            int z = Integer.parseInt(t.substring(zi + 4, t.indexOf("}", zi)));
                            long key = (long) x << 32 | (z & 0xFFFFFFFFL);
                            if (!blacklist.contains(key)) {
                                positions.add(new BlockPos(x, y, z));
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (IOException e) { return null; }

        if (positions.isEmpty()) {
            info("所有坐标已被锁定过（黑名单内）");
            return null;
        }

        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (BlockPos p : positions) {
            double d = p.getSquaredDistance(px, py, pz);
            if (d < best) { best = d; nearest = p; }
        }
        return nearest;
    }

    private void writeBlacklist() {
        if (ladderPos == null) return;
        String world = detectWorld();
        if (world == null) return;

        Path file = TIZI_DIR.resolve(world).resolve("Lockblacklist.json");
        try {
            Files.createDirectories(file.getParent());
            Set<String> entries = new LinkedHashSet<>();
            if (Files.isRegularFile(file)) {
                String raw = Files.readString(file).trim();
                if (raw.startsWith("[") && raw.endsWith("]")) {
                    String inner = raw.substring(1, raw.length() - 1).trim();
                    if (!inner.isEmpty()) {
                        for (String part : inner.split("},")) {
                            String t = part.trim();
                            if (!t.endsWith("}")) t += "}";
                            if (!t.isBlank()) entries.add(t);
                        }
                    }
                }
            }
            entries.add("{\"x\":" + ladderPos.getX() + ",\"z\":" + ladderPos.getZ() + "}");
            List<String> sorted = new ArrayList<>(entries);
            Files.writeString(file, "[\n  " + String.join(",\n  ", sorted) + "\n]");
        } catch (IOException e) {
            error("写入Lockblacklist失败: " + e.getMessage());
        }
    }

    private String detectWorld() {
        if (mc == null) return null;
        try {
            if (mc.getCurrentServerEntry() != null)
                return "Multiplayer_" + mc.getCurrentServerEntry().address.replace(":", "_");
        } catch (Exception ignored) {}
        try {
            if (mc.getServer() != null) {
                var sp = mc.getServer().getSaveProperties();
                if (sp != null) return sp.getLevelName();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void info(String msg) {
        System.out.println("[LockEnd] " + msg);
        if (mc != null && mc.player != null)
            mc.player.sendMessage(net.minecraft.text.Text.literal("§b[LockEnd] " + msg), false);
    }

    private void error(String msg) {
        System.out.println("[LockEnd] " + msg);
        if (mc != null && mc.player != null)
            mc.player.sendMessage(net.minecraft.text.Text.literal("§c[LockEnd] " + msg), false);
    }
}
