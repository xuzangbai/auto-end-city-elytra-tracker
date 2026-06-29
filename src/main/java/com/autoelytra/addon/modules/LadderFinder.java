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
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.dimension.DimensionType;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class LadderFinder extends Module {
    private static final Path TIZI_DIR = Paths.get("tizi");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> minLength = sgGeneral.add(new IntSetting.Builder()
        .name("min-length")
        .description("最小连续梯子长度")
        .defaultValue(14).min(3).sliderMax(32)
        .build()
    );

    private final Setting<Boolean> saveToFile = sgGeneral.add(new BoolSetting.Builder()
        .name("save-to-file")
        .description("保存坐标到 tizi/<世界>/zuobiao.json")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> clearCache = sgGeneral.add(new BoolSetting.Builder()
        .name("clear-cache")
        .description("清除 zuobiao.json 缓存")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> reportShort = sgGeneral.add(new BoolSetting.Builder()
        .name("report-short")
        .description("检测低于 min-length 的梯子链，保存到单独文件并提示")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("color")
        .description("梯子高亮颜色")
        .defaultValue(new SettingColor(255, 180, 0, 100))
        .build()
    );

    private final Setting<SettingColor> shortColor = sgRender.add(new ColorSetting.Builder()
        .name("short-color")
        .description("低于最小长度的梯子链颜色")
        .defaultValue(new SettingColor(255, 0, 0, 100))
        .visible(reportShort::get)
        .build()
    );

    // ── State ────────────────────────────────────────────────────────────

    private final Long2ObjectMap<Set<BlockPos>> chunkLadders = new Long2ObjectOpenHashMap<>();
    private final Set<BlockPos> allLadders = new LinkedHashSet<>();
    private List<List<BlockPos>> chains = new ArrayList<>();
    private List<List<BlockPos>> shortChains = new ArrayList<>();
    private int lastReportedCount = -1;
    private DimensionType lastDimension;
    private boolean needsChainUpdate;
    private int ticksSinceLastReport;

    public LadderFinder() {
        super(AddonTemplate.CATEGORY, "ladder-finder",
            "扫描视野内连续梯子，方块ESP渲染 + 保存坐标");
    }

    @Override
    public void onActivate() {
        try { Files.createDirectories(TIZI_DIR); } catch (IOException ignored) {}
        synchronized (chunkLadders) {
            chunkLadders.clear();
            allLadders.clear();
            chains.clear();
            shortChains.clear();
        }
        ticksSinceLastReport = 0;
        needsChainUpdate = false;
        lastReportedCount = -1;

        for (Chunk chunk : Utils.chunks()) {
            searchChunk(chunk);
        }
        lastDimension = mc.world.getDimension();
        recomputeChains();
    }

    @Override
    public void onDeactivate() {
        synchronized (chunkLadders) {
            chunkLadders.clear();
            allLadders.clear();
            chains.clear();
            shortChains.clear();
        }
    }

    // ── Chunk scanning ───────────────────────────────────────────────────

    private void searchChunk(Chunk chunk) {
        long chunkKey = chunk.getPos().toLong();
        Set<BlockPos> found = new HashSet<>();
        BlockPos.Mutable mut = new BlockPos.Mutable();

        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();
        int endX = chunk.getPos().getEndX();
        int endZ = chunk.getPos().getEndZ();

        for (int x = startX; x <= endX; x++) {
            for (int z = startZ; z <= endZ; z++) {
                int surfaceY = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE)
                    .get(x - startX, z - startZ);
                for (int y = mc.world.getBottomY(); y <= surfaceY; y++) {
                    mut.set(x, y, z);
                    if (chunk.getBlockState(mut).getBlock() == Blocks.LADDER) {
                        found.add(mut.toImmutable());
                    }
                }
            }
        }

        if (!found.isEmpty()) {
            synchronized (chunkLadders) {
                Set<BlockPos> old = chunkLadders.get(chunkKey);
                if (old != null) allLadders.removeAll(old);
                chunkLadders.put(chunkKey, found);
                allLadders.addAll(found);
                needsChainUpdate = true;
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
        Block oldB = event.oldState.getBlock();
        Block newB = event.newState.getBlock();
        boolean wasLadder = oldB == Blocks.LADDER;
        boolean isLadder = newB == Blocks.LADDER;
        if (wasLadder == isLadder) return;

        BlockPos pos = event.pos.toImmutable();
        long ck = ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);

        synchronized (chunkLadders) {
            if (isLadder) {
                allLadders.add(pos);
                chunkLadders.computeIfAbsent(ck, k -> new HashSet<>()).add(pos);
            } else {
                allLadders.remove(pos);
                Set<BlockPos> set = chunkLadders.get(ck);
                if (set != null) {
                    set.remove(pos);
                    if (set.isEmpty()) chunkLadders.remove(ck);
                }
            }
            needsChainUpdate = true;
        }
    }

    @EventHandler
    private void onPostTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // Clear cache button
        if (clearCache.get()) {
            clearCache.set(false);
            clearJsonCache();
        }

        // Dimension change
        DimensionType dim = mc.world.getDimension();
        if (lastDimension != dim) {
            onActivate();
            lastDimension = dim;
            return;
        }

        // Purge unloaded chunks
        boolean purged = purgeUnloadedChunks();
        if (purged) needsChainUpdate = true;

        // Periodic chain recomputation
        ticksSinceLastReport++;
        if (ticksSinceLastReport >= 20) {
            ticksSinceLastReport = 0;
            if (needsChainUpdate) {
                needsChainUpdate = false;
                recomputeChains();
            }
        }
    }

    private boolean purgeUnloadedChunks() {
        int playerCx = mc.player.getBlockX() >> 4;
        int playerCz = mc.player.getBlockZ() >> 4;
        int viewDist = Utils.getRenderDistance() + 1;
        boolean changed = false;

        synchronized (chunkLadders) {
            var it = chunkLadders.long2ObjectEntrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                int cx = ChunkPos.getPackedX(entry.getLongKey());
                int cz = ChunkPos.getPackedZ(entry.getLongKey());
                if (Math.abs(cx - playerCx) > viewDist
                    || Math.abs(cz - playerCz) > viewDist) {
                    allLadders.removeAll(entry.getValue());
                    it.remove();
                    changed = true;
                }
            }
        }
        return changed;
    }

    // ── Connected components ─────────────────────────────────────────────

    private void recomputeChains() {
        List<List<BlockPos>> newChains = new ArrayList<>();
        List<List<BlockPos>> newShortChains = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        int minLen = minLength.get();

        for (BlockPos start : allLadders) {
            if (visited.contains(start)) continue;

            List<BlockPos> comp = new ArrayList<>();
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(start);

            while (!queue.isEmpty()) {
                BlockPos p = queue.poll();
                comp.add(p);
                for (BlockPos n : new BlockPos[]{
                    p.up(), p.down(), p.north(), p.south(), p.east(), p.west()
                }) {
                    if (allLadders.contains(n) && !visited.contains(n)) {
                        visited.add(n);
                        queue.add(n);
                    }
                }
            }

            if (comp.size() >= minLen) {
                newChains.add(comp);
            } else if (reportShort.get() && comp.size() >= 2) {
                newShortChains.add(comp);
            }
        }

        chains = newChains;
        shortChains = reportShort.get() ? newShortChains : new ArrayList<>();

        if (saveToFile.get() && !chains.isEmpty()) {
            int newCount = saveToJson();
            if (newCount > 0) {
                info("找到 " + newCount + " 个新连续梯子柱 (≥" + minLen + "格)");
            } else if (chains.size() != lastReportedCount) {
                lastReportedCount = chains.size();
                info("已找到梯子链，但已写入文件 无视");
            }
        } else if (chains.size() != lastReportedCount) {
            lastReportedCount = chains.size();
            if (!chains.isEmpty()) {
                info("找到 " + chains.size() + " 个连续梯子柱 (≥" + minLen + "格)");
            }
        }

        // Short chains
        if (reportShort.get()) {
            if (!newShortChains.isEmpty()) {
                int newCount = saveShortJson(newShortChains);
                if (newCount > 0) {
                    info("找到 " + newCount + " 个新低于" + minLen + "格梯子链，已保存到 tizi/<世界>/short_zuobiao.json 文件");
                } else {
                    info("已找到梯子链，但已写入文件 无视");
                }
            }
        }
    }

    // ── Render ───────────────────────────────────────────────────────────

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        for (List<BlockPos> comp : chains) {
            for (BlockPos pos : comp) {
                Box box = new Box(pos);
                event.renderer.box(box, color.get(), color.get(), ShapeMode.Both, 0);
            }
        }
        for (List<BlockPos> comp : shortChains) {
            for (BlockPos pos : comp) {
                Box box = new Box(pos);
                event.renderer.box(box, shortColor.get(), shortColor.get(), ShapeMode.Both, 0);
            }
        }
    }

    // ── File ─────────────────────────────────────────────────────────────

    /** @return number of NEW entries added (not already in file). */
    private int saveToJson() {
        String world = detectTiziWorld();
        if (world == null) return 0;

        Set<String> entries = new LinkedHashSet<>();
        Path file = TIZI_DIR.resolve(world).resolve("zuobiao.json");
        try {
            Files.createDirectories(file.getParent());
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
            int before = entries.size();
            for (List<BlockPos> comp : chains) {
                BlockPos top = comp.get(0);
                for (BlockPos p : comp) {
                    if (p.getY() > top.getY()) top = p;
                }
                entries.add("{\"x\":" + top.getX() + ",\"y\":" + top.getY() + ",\"z\":" + top.getZ() + "}");
            }
            int added = entries.size() - before;
            if (added > 0) {
                List<String> sorted = new ArrayList<>(entries);
                Files.writeString(file, "[\n  " + String.join(",\n  ", sorted) + "\n]");
            }
            return added;
        } catch (IOException e) {
            error("保存失败: " + e.getMessage());
            return 0;
        }
    }

    /** @return number of NEW entries added (not already in file). */
    private int saveShortJson(List<List<BlockPos>> shortChains) {
        String world = detectTiziWorld();
        if (world == null) return 0;

        Set<String> entries = new LinkedHashSet<>();
        Path file = TIZI_DIR.resolve(world).resolve("short_zuobiao.json");
        try {
            Files.createDirectories(file.getParent());
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
            int before = entries.size();
            for (List<BlockPos> comp : shortChains) {
                BlockPos top = comp.get(0);
                for (BlockPos p : comp) {
                    if (p.getY() > top.getY()) top = p;
                }
                entries.add("{\"x\":" + top.getX() + ",\"y\":" + top.getY() + ",\"z\":" + top.getZ() + "}");
            }
            int added = entries.size() - before;
            if (added > 0) {
                List<String> sorted = new ArrayList<>(entries);
                Files.writeString(file, "[\n  " + String.join(",\n  ", sorted) + "\n]");
            }
            return added;
        } catch (IOException e) {
            error("保存失败: " + e.getMessage());
            return 0;
        }
    }

    private void clearJsonCache() {
        String world = detectTiziWorld();
        if (world == null) return;
        try {
            Files.deleteIfExists(TIZI_DIR.resolve(world).resolve("zuobiao.json"));
            Files.deleteIfExists(TIZI_DIR.resolve(world).resolve("short_zuobiao.json"));
            info("已清除 " + TIZI_DIR.resolve(world).resolve("zuobiao.json"));
        } catch (IOException e) {
            error("清除失败: " + e.getMessage());
        }
    }

    private String detectTiziWorld() {
        try { if (mc.getCurrentServerEntry() != null) return "Multiplayer_" + mc.getCurrentServerEntry().address.replace(":", "_"); } catch (Exception ignored) {}
        try { if (mc.getServer() != null) { var sp = mc.getServer().getSaveProperties(); if (sp != null) return sp.getLevelName(); } } catch (Exception ignored) {}
        return null;
    }

    private void info(String msg) {
        System.out.println("[LadderFinder] " + msg);
        if (mc != null && mc.player != null)
            mc.player.sendMessage(net.minecraft.text.Text.literal("§a[LadderFinder] " + msg), false);
    }

    private void error(String msg) {
        System.out.println("[LadderFinder] " + msg);
        if (mc != null && mc.player != null)
            mc.player.sendMessage(net.minecraft.text.Text.literal("§c[LadderFinder] " + msg), false);
    }
}
