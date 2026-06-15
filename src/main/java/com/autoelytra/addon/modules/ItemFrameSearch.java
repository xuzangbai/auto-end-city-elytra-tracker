package com.autoelytra.addon.modules;

import com.autoelytra.addon.AddonTemplate;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.dimension.DimensionType;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ItemFrameSearch extends Module {
    private static final Path TIZI_DIR = Paths.get("tizi");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> showRender = sgRender.add(new BoolSetting.Builder()
        .name("show-render")
        .description("渲染最近的展示框和炼药台")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("渲染模式")
        .defaultValue(ShapeMode.Both)
        .visible(showRender::get)
        .build()
    );

    private final Setting<SettingColor> frameColor = sgRender.add(new ColorSetting.Builder()
        .name("frame-color")
        .description("展示框渲染颜色")
        .defaultValue(new SettingColor(0, 255, 255, 100))
        .visible(showRender::get)
        .build()
    );

    private final Setting<Boolean> saveToFile = sgGeneral.add(new BoolSetting.Builder()
        .name("save-to-file")
        .description("保存到文件")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> findBrewingStand = sgGeneral.add(new BoolSetting.Builder()
        .name("find-brewing-stand")
        .description("同时查找炼药台")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> clearCache = sgGeneral.add(new BoolSetting.Builder()
        .name("clear-cache")
        .description("清除缓存文件")
        .defaultValue(false)
        .build()
    );

    // ── State ────────────────────────────────────────────────────────────

    // Item frames (entity-based, efficient)
    private BlockPos nearestFrame;

    // Brewing stands (chunk-based, like LadderFinder)
    private final Long2ObjectMap<Set<BlockPos>> chunkBrewingStands = new Long2ObjectOpenHashMap<>();
    private final Set<BlockPos> allBrewingStands = new LinkedHashSet<>();
    private BlockPos nearestBrewingStand;
    private DimensionType lastDimension;
    private int recomputeCooldown;

    // Navigation state (lock-start flow)
    private boolean navMode;
    private BlockPos navTarget;
    private double navFrameX, navFrameY, navFrameZ;
    private ItemFrameEntity navFrameEntity;
    private int navTicks;
    private int frameCheckDelay;
    private boolean navDone;
    private int storeDelay;

    public ItemFrameSearch() {
        super(AddonTemplate.CATEGORY, "item-frame-search",
            "查找最近展示框和炼药台，保存坐标到文件");
    }

    @Override
    public void onActivate() {
        try { Files.createDirectories(TIZI_DIR); } catch (IOException ignored) {}
        nearestFrame = null;
        nearestBrewingStand = null;
        synchronized (chunkBrewingStands) {
            chunkBrewingStands.clear();
            allBrewingStands.clear();
        }
        recomputeCooldown = 0;
        navMode = false;
        navTarget = null;
        navTicks = 0;

        // Delay frame check on world join (chunks not loaded yet)
        frameCheckDelay = mc.player.age < 80 ? 40 : 0;

        // Start chunk scanning for brewing stands immediately
        if (findBrewingStand.get()) {
            for (Chunk chunk : Utils.chunks()) {
                searchChunk(chunk);
            }
            lastDimension = mc.world.getDimension();
            recomputeNearest();
        }

        // Check nearest frame for navigation (delayed if just joined)
        if (frameCheckDelay == 0) {
            checkFrameAndNavigate();
        }
    }

    private void checkFrameAndNavigate() {
        navDone = true;
        ItemFrameEntity frame = findNearestFrameEntity();
        if (frame != null) {
            nearestFrame = frame.getBlockPos();
            if (saveToFile.get()) saveToJson(nearestFrame, "ItemFrame.json");
            if (frame.getHeldItemStack().isEmpty()) {
                info("展示框内无物品，直接启动 LockEnd");
                toggle();
                LockEnd le = Modules.get().get(LockEnd.class);
                if (le != null && !le.isActive()) le.toggle();
            } else {
                navMode = true;
                navFrameEntity = frame;
                navFrameX = frame.getX();
                navFrameY = frame.getY();
                navFrameZ = frame.getZ();
                navTarget = frame.getBlockPos().down();
                navTicks = 0;
                if (mc.player.networkHandler != null) {
                    mc.player.networkHandler.sendChatMessage(
                        "#goto " + navTarget.getX() + " " + navTarget.getY() + " " + navTarget.getZ());
                }
                info("展示框有物品，寻路至: " + navTarget.getX() + " " + navTarget.getY() + " " + navTarget.getZ());
            }
        } else {
            info("未找到展示框，直接启动 LockEnd");
            toggle();
            LockEnd le = Modules.get().get(LockEnd.class);
            if (le != null && !le.isActive()) le.toggle();
        }
    }

    @Override
    public void onDeactivate() {
        nearestFrame = null;
        nearestBrewingStand = null;
        synchronized (chunkBrewingStands) {
            chunkBrewingStands.clear();
            allBrewingStands.clear();
        }
    }

    private ItemFrameEntity findNearestFrameEntity() {
        if (mc.player == null || mc.world == null) return null;
        int viewDist = mc.options.getViewDistance().getValue();
        double range = viewDist * 16.0;
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        Box sb = new Box(px - range, py - range, pz - range, px + range, py + range, pz + range);

        ItemFrameEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (ItemFrameEntity f : mc.world.getEntitiesByClass(
                ItemFrameEntity.class, sb, e -> true)) {
            double d = f.getBlockPos().getSquaredDistance(px, py, pz);
            if (d < best) { best = d; nearest = f; }
        }
        return nearest;
    }

    // ── Chunk scanning for brewing stands ───────────────────────────────

    private void searchChunk(Chunk chunk) {
        if (!findBrewingStand.get()) return;
        if (!(chunk instanceof WorldChunk wc)) return;
        long ck = chunk.getPos().toLong();
        Set<BlockPos> found = new HashSet<>();

        ChunkSection[] sections = wc.getSectionArray();
        int cx = chunk.getPos().x * 16;
        int cz = chunk.getPos().z * 16;
        for (int si = 0; si < sections.length; si++) {
            ChunkSection section = sections[si];
            if (section.isEmpty()) continue;
            int sy0 = mc.world.getBottomY() + si * 16;
            for (int sx = 0; sx < 16; sx++) {
                for (int sz = 0; sz < 16; sz++) {
                    for (int sy = 0; sy < 16; sy++) {
                        if (section.getBlockState(sx, sy, sz).isOf(Blocks.BREWING_STAND)) {
                            found.add(new BlockPos(cx + sx, sy0 + sy, cz + sz));
                        }
                    }
                }
            }
        }

        if (!found.isEmpty()) {
            synchronized (chunkBrewingStands) {
                Set<BlockPos> old = chunkBrewingStands.get(ck);
                if (old != null) allBrewingStands.removeAll(old);
                chunkBrewingStands.put(ck, found);
                allBrewingStands.addAll(found);
                
            }
        }
    }

    // ── Events ───────────────────────────────────────────────────────────

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        searchChunk(event.chunk());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (!findBrewingStand.get()) return;
        Block oldB = event.oldState.getBlock();
        Block newB = event.newState.getBlock();
        boolean was = oldB == Blocks.BREWING_STAND;
        boolean is = newB == Blocks.BREWING_STAND;
        if (was == is) return;

        BlockPos pos = event.pos.toImmutable();
        long ck = ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
        synchronized (chunkBrewingStands) {
            if (is) {
                allBrewingStands.add(pos);
                chunkBrewingStands.computeIfAbsent(ck, k -> new HashSet<>()).add(pos);
            } else {
                allBrewingStands.remove(pos);
                Set<BlockPos> set = chunkBrewingStands.get(ck);
                if (set != null) {
                    set.remove(pos);
                    if (set.isEmpty()) chunkBrewingStands.remove(ck);
                }
            }
            
        }
    }

    @EventHandler
    private void onPostTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (storeDelay > 0) {
            storeDelay--;
            if (storeDelay == 0) {
                toggle();
                StoreResupply sr = Modules.get().get(StoreResupply.class);
                if (sr != null && !sr.isActive()) sr.toggle();
            }
            return;
        }

        if (clearCache.get()) {
            clearCache.set(false);
            clearJsonCache();
        }

        // Delayed frame check (world join — chunks not loaded yet)
        if (frameCheckDelay > 0) {
            frameCheckDelay--;
            if (frameCheckDelay == 0 && !navDone) {
                checkFrameAndNavigate();
            }
            return;
        }

        // Navigation mode
        if (navMode) {
            boolean arrived = navTarget != null
                && mc.player.getBlockX() == navTarget.getX()
                && mc.player.getBlockZ() == navTarget.getZ();
            if (arrived) {
                navTicks++;
                // Lock view + attack
                {
                    double dx = navFrameX - mc.player.getX();
                    double dy = navFrameY - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
                    double dz = navFrameZ - mc.player.getZ();
                    double h = Math.sqrt(dx * dx + dz * dz);
                    mc.player.setYaw((float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f);
                    mc.player.setPitch((float) -Math.toDegrees(Math.atan2(dy, h)));
                }
                if (navTicks == 4 && navFrameEntity != null && !navFrameEntity.isRemoved()) {
                    mc.interactionManager.attackEntity(mc.player, navFrameEntity);
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
                if (navTicks >= 6) {
                    info("已攻击展示框，0.8秒后打开 StoreResupply");
                    navMode = false;
                    storeDelay = 16;
                }
            } else {
                navTicks = 0;
            }
            return;
        }

        // Normal scanning mode
        DimensionType dim = mc.world.getDimension();
        if (lastDimension != dim) {
            onActivate();
            lastDimension = dim;
            return;
        }

        purgeUnloadedChunks();

        recomputeCooldown++;
        if (recomputeCooldown >= 20) {
            recomputeCooldown = 0;
            recomputeNearest();
        }

        scanItemFrame();
    }

    private boolean purgeUnloadedChunks() {
        int pcx = mc.player.getBlockX() >> 4;
        int pcz = mc.player.getBlockZ() >> 4;
        int vd = Utils.getRenderDistance() + 1;
        boolean changed = false;
        synchronized (chunkBrewingStands) {
            var it = chunkBrewingStands.long2ObjectEntrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                int cx = ChunkPos.getPackedX(e.getLongKey());
                int cz = ChunkPos.getPackedZ(e.getLongKey());
                if (Math.abs(cx - pcx) > vd || Math.abs(cz - pcz) > vd) {
                    allBrewingStands.removeAll(e.getValue());
                    it.remove();
                    changed = true;
                }
            }
        }
        return changed;
    }

    private void recomputeNearest() {
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();

        // Nearest brewing stand
        if (findBrewingStand.get()) {
            BlockPos bs = null;
            double best = Double.MAX_VALUE;
            synchronized (chunkBrewingStands) {
                for (BlockPos bp : allBrewingStands) {
                    double d = bp.getSquaredDistance(px, py, pz);
                    if (d < best) { best = d; bs = bp; }
                }
            }
            if (bs != null && !bs.equals(nearestBrewingStand)) {
                nearestBrewingStand = bs;
                if (saveToFile.get()) saveToJson(bs, "brewing_stand.json");
            }
        }
    }

    private void scanItemFrame() {
        int viewDist = mc.options.getViewDistance().getValue();
        double range = viewDist * 16.0;
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();

        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        Box sb = new Box(px - range, py - range, pz - range, px + range, py + range, pz + range);

        for (ItemFrameEntity f : mc.world.getEntitiesByClass(
                ItemFrameEntity.class, sb, e -> true)) {
            BlockPos pos = f.getBlockPos();
            double d = pos.getSquaredDistance(px, py, pz);
            if (d < best) { best = d; nearest = pos; }
        }

        if (nearest != null && !nearest.equals(nearestFrame)) {
            nearestFrame = nearest;
            info("最近展示框: " + nearest.getX() + " " + nearest.getY() + " " + nearest.getZ()
                + "  距离: " + (int) Math.sqrt(best) + "m");
            if (saveToFile.get()) saveToJson(nearest, "ItemFrame.json");
        }
    }

    // ── Render ───────────────────────────────────────────────────────────

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!showRender.get() || mc.player == null) return;
        if (nearestFrame != null) {
            Box box = new Box(nearestFrame);
            SettingColor c = frameColor.get();
            event.renderer.box(box, c, c, shapeMode.get(), 0);
        }
        if (findBrewingStand.get() && nearestBrewingStand != null) {
            Box box = new Box(nearestBrewingStand);
            event.renderer.box(box, new SettingColor(255, 0, 255, 100),
                new SettingColor(255, 0, 255, 100), shapeMode.get(), 0);
        }
    }

    // ── File ─────────────────────────────────────────────────────────────

    private void saveToJson(BlockPos pos, String filename) {
        String world = detectWorld();
        if (world == null) return;
        Path file = TIZI_DIR.resolve(world).resolve(filename);
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
            String entry = "{\"x\":" + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ() + "}";
            if (entries.add(entry)) {
                List<String> sorted = new ArrayList<>(entries);
                Files.writeString(file, "[\n  " + String.join(",\n  ", sorted) + "\n]");
            }
        } catch (IOException e) {
            error("保存失败: " + e.getMessage());
        }
    }

    private void clearJsonCache() {
        String world = detectWorld();
        if (world == null) return;
        try {
            Files.deleteIfExists(TIZI_DIR.resolve(world).resolve("ItemFrame.json"));
            Files.deleteIfExists(TIZI_DIR.resolve(world).resolve("brewing_stand.json"));
            info("已清除缓存");
        } catch (IOException e) {
            error("清除失败: " + e.getMessage());
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
        System.out.println("[ItemFrameSearch] " + msg);
        if (mc != null && mc.player != null)
            mc.player.sendMessage(net.minecraft.text.Text.literal("§e[ItemFrameSearch] " + msg), false);
    }

    private void error(String msg) {
        System.out.println("[ItemFrameSearch] " + msg);
        if (mc != null && mc.player != null)
            mc.player.sendMessage(net.minecraft.text.Text.literal("§c[ItemFrameSearch] " + msg), false);
    }
}
