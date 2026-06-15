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

public class LockStart extends Module {
    private static final Path TIZI_DIR = Paths.get("tizi");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> aimAbove = sgGeneral.add(new IntSetting.Builder()
        .name("aim-above")
        .description("在目标坐标上方多少格创建锁定点")
        .defaultValue(30).min(5).max(100)
        .build()
    );

    private final Setting<Integer> descendRadius = sgGeneral.add(new IntSetting.Builder()
        .name("descend-radius")
        .description("距锁定点XZ此范围内Y轴跟随下降")
        .defaultValue(8).min(2).max(30)
        .build()
    );

    private final Setting<Integer> descendHeight = sgGeneral.add(new IntSetting.Builder()
        .name("descend-height")
        .description("距锁定点Y轴此范围内进入下降模式")
        .defaultValue(10).min(2).max(50)
        .build()
    );

    private final Setting<Integer> arriveDistance = sgGeneral.add(new IntSetting.Builder()
        .name("arrive-distance")
        .description("距梯子链坐标XZ此范围内关闭模块")
        .defaultValue(1).min(1).max(20)
        .build()
    );

    private final Setting<Integer> arriveHeight = sgGeneral.add(new IntSetting.Builder()
        .name("arrive-height")
        .description("距梯子链坐标Y轴此范围内关闭模块")
        .defaultValue(3).min(1).max(20)
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
        .description("从背包任意位置切换烟花（不只是快捷栏）")
        .defaultValue(false)
        .visible(autoFirework::get)
        .build()
    );

    private int prevSlot = -1;

    private BlockPos lockTarget;
    private BlockPos fixedTarget;
    private BlockPos ladderPos;
    private boolean descending;
    private int fireworkCooldown;
    private int scanTimer;

    public LockStart() {
        super(AddonTemplate.CATEGORY, "lock-start",
            "读取zuobiao.json最近坐标，在上方创建锁定点，引导降落");
    }

    @Override
    public void onActivate() {
        lockTarget = null;
        fixedTarget = null;
        ladderPos = null;
        descending = false;
        fireworkCooldown = 0;
        scanTimer = 0;

        tryLock();
    }

    @Override
    public void onDeactivate() {
        lockTarget = null;
        fixedTarget = null;
        ladderPos = null;
        descending = false;
    }

    /** Try to find and lock a non-blacklisted coordinate. Returns true if locked. */
    private boolean tryLock() {
        BlockPos nearest = findNearest();
        if (nearest == null) return false;

        ladderPos = nearest;
        int above = aimAbove.get();
        lockTarget = new BlockPos(nearest.getX(), nearest.getY() + above, nearest.getZ());
        fixedTarget = lockTarget;
        descending = false;

        double dist = Math.sqrt(lockTarget.getSquaredDistance(
            mc.player.getX(), mc.player.getY(), mc.player.getZ()));
        info("锁定目标: " + lockTarget.getX() + " " + lockTarget.getY() + " " + lockTarget.getZ()
            + "  距离: " + (int) dist + "m");

        // Close WaypointLock + ElytraInfiniteFlight + ElytraFly now that we have a new lock target
        WaypointLock wl = Modules.get().get(WaypointLock.class);
        if (wl != null && wl.isActive()) wl.toggle();
        ElytraInfiniteFlight eif = Modules.get().get(ElytraInfiniteFlight.class);
        if (eif != null && eif.isActive()) eif.toggle();
        ElytraFly ef = Modules.get().get(ElytraFly.class);
        if (ef != null && ef.isActive()) ef.toggle();

        return true;
    }

    @EventHandler
    private void onPreTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // If no lock yet, periodically re-scan for new non-blacklisted coords
        if (lockTarget == null) {
            scanTimer++;
            if (scanTimer >= 40) { // every 2 seconds
                scanTimer = 0;
                tryLock();
            }
            return;
        }

        // Check arrival at original ladder coordinate
        if (ladderPos != null) {
            double dx = ladderPos.getX() - mc.player.getX();
            double dz = ladderPos.getZ() - mc.player.getZ();
            double dy = Math.abs(ladderPos.getY() - mc.player.getY());
            if (dx * dx + dz * dz <= arriveDistance.get() * arriveDistance.get()
                && dy <= arriveHeight.get()) {
                info("已到达梯子链坐标，关闭锁定，打开 ItemFrameSearch");
                toggle();
                ItemFrameSearch ifs = Modules.get().get(ItemFrameSearch.class);
                if (ifs != null && !ifs.isActive()) ifs.toggle();
                return;
            }
        }

        // Distance to lock point
        double dx = lockTarget.getX() + 0.5 - mc.player.getX();
        double dy = lockTarget.getY() + 0.5 - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double dz = lockTarget.getZ() + 0.5 - mc.player.getZ();
        double horizDist = Math.sqrt(dx * dx + dz * dz);

        if (!descending && horizDist <= descendRadius.get()
            && Math.abs(fixedTarget.getY() - mc.player.getY()) <= descendHeight.get()) {
            descending = true;
        }

        if (descending) {
            lockTarget = new BlockPos(lockTarget.getX(), (int) mc.player.getY(), lockTarget.getZ());
            float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
            mc.player.setYaw(yaw);
            mc.player.setPitch(0);
        } else {
            double fdx = fixedTarget.getX() + 0.5 - mc.player.getX();
            double fdy = fixedTarget.getY() + 0.5 - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
            double fdz = fixedTarget.getZ() + 0.5 - mc.player.getZ();
            double fhoriz = Math.sqrt(fdx * fdx + fdz * fdz);

            float yaw = (float) Math.toDegrees(Math.atan2(fdz, fdx)) - 90.0f;
            float pitch = (float) -Math.toDegrees(Math.atan2(fdy, fhoriz));
            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);
        }

        // Auto firework
        if (autoFirework.get() && mc.player.isGliding() && mc.player.getY() < lockTarget.getY()) {
            if (fireworkCooldown > 0) fireworkCooldown--;
            else releaseFirework();
        }
    }

    @EventHandler
    private void onPostTick(TickEvent.Post event) {
        if (mc.player == null || lockTarget == null) return;

        double dx = lockTarget.getX() + 0.5 - mc.player.getX();
        double dz = lockTarget.getZ() + 0.5 - mc.player.getZ();

        if (descending) {
            float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
            mc.player.setYaw(yaw);
            mc.player.setPitch(0);
        } else {
            double fdx = fixedTarget.getX() + 0.5 - mc.player.getX();
            double fdy = fixedTarget.getY() + 0.5 - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
            double fdz = fixedTarget.getZ() + 0.5 - mc.player.getZ();
            double fhoriz = Math.sqrt(fdx * fdx + fdz * fdz);

            float yaw = (float) Math.toDegrees(Math.atan2(fdz, fdx)) - 90.0f;
            float pitch = (float) -Math.toDegrees(Math.atan2(fdy, fhoriz));
            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (lockTarget == null || mc.player == null) return;
        Box box = new Box(descending ? lockTarget : fixedTarget);
        event.renderer.box(box, new SettingColor(255, 0, 0, 120),
            new SettingColor(255, 0, 0, 120), ShapeMode.Both, 0);
    }

    // ── Firework ─────────────────────────────────────────────────────────

    private void releaseFirework() {
        if (quickSwap.get()) {
            FindItemResult result = InvUtils.find(Items.FIREWORK_ROCKET);
            if (!result.found()) return;
            prevSlot = mc.player.getInventory().getSelectedSlot();
            if (!result.isHotbar()) InvUtils.quickSwap().fromId(prevSlot).to(result.slot());
            else mc.player.getInventory().setSelectedSlot(result.slot());
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            if (!result.isHotbar()) InvUtils.quickSwap().fromId(prevSlot).to(result.slot());
            else mc.player.getInventory().setSelectedSlot(prevSlot);
        } else {
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getStack(i).getItem() == Items.FIREWORK_ROCKET) {
                    prevSlot = mc.player.getInventory().getSelectedSlot();
                    mc.player.getInventory().setSelectedSlot(i);
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    mc.player.getInventory().setSelectedSlot(prevSlot);
                    break;
                }
            }
        }
        fireworkCooldown = fireworkInterval.get();
    }

    // ── Nearest coordinate (with blacklist) ──────────────────────────────

    private BlockPos findNearest() {
        String world = detectWorld();
        if (world == null) return null;

        Path file = TIZI_DIR.resolve(world).resolve("zuobiao.json");
        if (!Files.isRegularFile(file)) return null;

        Set<Long> blacklist = loadBlacklist(world);

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

        if (positions.isEmpty()) return null;

        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (BlockPos p : positions) {
            double d = p.getSquaredDistance(px, py, pz);
            if (d < best) { best = d; nearest = p; }
        }
        return nearest;
    }

    private Set<Long> loadBlacklist(String world) {
        Set<Long> set = new HashSet<>();
        Path file = TIZI_DIR.resolve(world).resolve("Lockblacklist.json");
        if (!Files.isRegularFile(file)) return set;
        try {
            String raw = Files.readString(file).trim();
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
                            set.add((long) x << 32 | (z & 0xFFFFFFFFL));
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (IOException ignored) {}
        return set;
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
        System.out.println("[LockStart] " + msg);
        if (mc != null && mc.player != null)
            mc.player.sendMessage(net.minecraft.text.Text.literal("§d[LockStart] " + msg), false);
    }
}
