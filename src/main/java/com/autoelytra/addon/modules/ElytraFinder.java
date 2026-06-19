package com.autoelytra.addon.modules;

import com.autoelytra.addon.AddonTemplate;
import com.autoelytra.addon.utils.PointUtils;
import com.seedfinding.mcbiome.source.BiomeSource;
import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.state.Dimension;
import com.seedfinding.mccore.util.pos.BPos;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.structure.EndCity;
import com.seedfinding.mcfeature.structure.generator.structure.EndCityGenerator;
import com.seedfinding.mcterrain.TerrainGenerator;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 用世界种子预测末地城/末地船(鞘翅)位置，渲染并添加到 Xaero 路径点。
 * 基于 <a href="https://github.com/SeedFinding/fnseedc">fnseedc</a> 库。
 */
public class ElytraFinder extends Module {

    private final SettingGroup sgInput = settings.createGroup("Input");
    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgWaypoint = settings.createGroup("Waypoint");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // -- Input --
    private final Setting<String> worldSeedStr = sgInput.add(new StringSetting.Builder()
        .name("world-seed")
        .description("世界种子。0 = 单人游戏自动检测")
        .defaultValue("0")
        .build()
    );

    private final Setting<String> mcVersion = sgInput.add(new StringSetting.Builder()
        .name("mc-version")
        .description("MC版本 (e.g. 1.21, 1.20.1)")
        .defaultValue("1.21")
        .build()
    );

    private final Setting<Integer> centerX = sgInput.add(new IntSetting.Builder()
        .name("center-x")
        .description("搜索中心 X")
        .defaultValue(0).min(-30000000).max(30000000)
        .build()
    );

    private final Setting<Integer> centerZ = sgInput.add(new IntSetting.Builder()
        .name("center-z")
        .description("搜索中心 Z")
        .defaultValue(0).min(-30000000).max(30000000)
        .build()
    );

    private final Setting<Integer> searchRadius = sgInput.add(new IntSetting.Builder()
        .name("search-radius")
        .description("搜索半径 (方块)")
        .defaultValue(20000).min(1000).sliderMax(100000)
        .build()
    );

    private final Setting<Boolean> snapToPlayer = sgInput.add(new BoolSetting.Builder()
        .name("snap-to-player")
        .description("中心设为当前玩家坐标")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> doSearch = sgInput.add(new BoolSetting.Builder()
        .name("search-now")
        .description("点击执行搜索")
        .defaultValue(false)
        .build()
    );

    // -- Filter --
    private final Setting<Boolean> requireShip = sgFilter.add(new BoolSetting.Builder()
        .name("require-ship")
        .description("仅报告含有末地船(鞘翅)的末地城")
        .defaultValue(true)
        .build()
    );

    // -- Waypoint --
    private final Setting<Boolean> addWaypoints = sgWaypoint.add(new BoolSetting.Builder()
        .name("add-waypoints")
        .description("添加到 Xaero 路径点")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> clearWaypoints = sgWaypoint.add(new BoolSetting.Builder()
        .name("clear-waypoints")
        .description("清除全部 ElytraFinder 路径点")
        .defaultValue(false)
        .build()
    );

    // -- Render --
    private final Setting<SettingColor> cityColor = sgRender.add(new ColorSetting.Builder()
        .name("city-color")
        .description("末地城颜色")
        .defaultValue(new SettingColor(255, 180, 0, 80))
        .build()
    );

    private final Setting<SettingColor> shipColor = sgRender.add(new ColorSetting.Builder()
        .name("ship-color")
        .description("末地船/鞘翅颜色")
        .defaultValue(new SettingColor(255, 0, 255, 180))
        .build()
    );

    private final Setting<Integer> renderBoxSize = sgRender.add(new IntSetting.Builder()
        .name("render-box-size")
        .description("渲染方块大小")
        .defaultValue(3).min(1).sliderMax(10)
        .build()
    );

    // ── State ───────────────────────────────────────────────────────

    private final List<FoundCity> foundCities = new ArrayList<>();
    private int searchTotal, searchProgress;
    private String lastError;

    public ElytraFinder() {
        super(AddonTemplate.CATEGORY, "elytra-finder",
            "种子 → 末地城/鞘翅坐标 → Xaero路径点 + 3D渲染。基于 fnseedc。");
    }

    @Override
    public void onActivate() {
        if (getWorldSeedLong() == 0L) {
            long d = detectWorldSeed();
            if (d != 0L) {
                worldSeedStr.set(String.valueOf(d));
                info("自动检测种子: " + d);
            }
        }
    }

    // ── Tick ────────────────────────────────────────────────────────

    @EventHandler
    private void onPostTick(TickEvent.Post event) {
        if (mc.player == null) return;

        if (snapToPlayer.get()) {
            snapToPlayer.set(false);
            centerX.set(mc.player.getBlockX());
            centerZ.set(mc.player.getBlockZ());
            info("中心 → 玩家: " + centerX.get() + ", " + centerZ.get());
        }

        if (clearWaypoints.get()) {
            clearWaypoints.set(false);
            int n = PointUtils.removeWaypoints("Elytra_")
                  + PointUtils.removeWaypoints("EndCity_")
                  + PointUtils.removeWaypoints("EndShip_");
            info("已清除 " + n + " 个路径点");
        }

        if (doSearch.get()) {
            doSearch.set(false);
            performSeedSearch();
        }
    }

    // ── 种子搜索 ────────────────────────────────────────────────────

    private void performSeedSearch() {
        long seed = getWorldSeedLong();
        if (seed == 0L) { error("请先设置世界种子"); return; }

        MCVersion ver = resolveVersion();
        if (ver == null) { error("无法解析MC版本: " + mcVersion.get()); return; }

        int cx = centerX.get(), cz = centerZ.get(), radius = searchRadius.get();
        info(String.format("种子搜索: seed=%d ver=%s center=(%d,%d) r=%d", seed, ver, cx, cz, radius));

        foundCities.clear();
        searchProgress = 0;
        lastError = null;

        try {
            EndCity endCity = new EndCity(ver);
            ChunkRand rand = new ChunkRand();
            BiomeSource biomeSrc = BiomeSource.of(Dimension.END, ver, seed);
            TerrainGenerator terrain = TerrainGenerator.of(Dimension.END, biomeSrc);
            EndCityGenerator gen = new EndCityGenerator(ver);

            int spacing = endCity.getSpacing();
            int crX = cx / 16 / spacing, crZ = cz / 16 / spacing;
            int rr = radius / 16 / spacing + 1;
            searchTotal = (2 * rr + 1) * (2 * rr + 1);

            for (int rx = crX - rr; rx <= crX + rr; rx++) {
                for (int rz = crZ - rr; rz <= crZ + rr; rz++) {
                    searchProgress++;
                    CPos cp = endCity.getInRegion(seed, rx, rz, rand);
                    if (cp == null) continue;
                    if (!endCity.canSpawn(cp, biomeSrc)) continue;
                    if (!endCity.canGenerate(cp, terrain)) continue;

                    int bx = cp.getX() * 16 + 8, bz = cp.getZ() * 16 + 8;
                    double dist = Math.sqrt((double)(bx - cx) * (bx - cx) + (bz - cz) * (bz - cz));
                    if (dist > radius) continue;

                    BPos elytraPos = null;
                    boolean hasShip = false;
                    try {
                        gen.generate(terrain, cp.getX(), cp.getZ(), rand);
                        hasShip = gen.hasShip();
                        if (hasShip) {
                            for (var e : gen.getChestsPos()) {
                                if (e.getFirst() == EndCityGenerator.LootType.SHIP_ELYTRA) {
                                    elytraPos = e.getSecond();
                                    break;
                                }
                            }
                        }
                        gen.reset();
                    } catch (Exception ex) { gen.reset(); }

                    if (requireShip.get() && !hasShip) continue;

                    int y = terrain.getHeightInGround(bx, bz);
                    if (hasShip && elytraPos != null) y = elytraPos.getY();

                    foundCities.add(new FoundCity(
                        new BlockPos(bx, y, bz),
                        elytraPos != null ? new BlockPos(elytraPos.getX(), elytraPos.getY(), elytraPos.getZ()) : null,
                        hasShip, (int) dist));
                }
            }

            foundCities.sort(Comparator.comparingInt(a -> a.distance));
            reportResults();
        } catch (Exception e) {
            lastError = e.getMessage();
            error("搜索出错: " + lastError);
        }
    }

    // ── 报告 + 路径点 ──────────────────────────────────────────────

    private void reportResults() {
        long ships = foundCities.stream().filter(c -> c.hasShip).count();
        info(String.format("完成! %d 末地城 | %d 末地船(鞘翅) | 种子=%d",
            foundCities.size(), ships, getWorldSeedLong()));

        int show = Math.min(20, foundCities.size());
        for (int i = 0; i < show; i++) {
            FoundCity c = foundCities.get(i);
            BlockPos p = c.elytraPos != null ? c.elytraPos : c.position;
            String tag = c.hasShip ? "§a[鞘翅]" : "§e[末地城]";
            info(String.format("  #%d: %s §f%d,%d,%d §7距离:%dm",
                i + 1, tag, p.getX(), p.getY(), p.getZ(), c.distance));
        }

        if (addWaypoints.get()) addAllWaypoints();
    }

    private void addAllWaypoints() {
        int added = 0;
        for (FoundCity c : foundCities) {
            if (c.hasShip && c.elytraPos != null) {
                BlockPos p = c.elytraPos;
                if (PointUtils.addToWaypoints(p.getX(), p.getY(), p.getZ(),
                    "Elytra_" + p.getX() + "_" + p.getZ())) added++;
            } else {
                BlockPos p = c.position;
                if (PointUtils.addToWaypoints(p.getX(), p.getY(), p.getZ(),
                    "EndCity_" + p.getX() + "_" + p.getZ())) added++;
            }
        }
        if (added > 0) info("已添加 " + added + " 个路径点到Xaero");
    }

    // ── 3D渲染 ─────────────────────────────────────────────────────

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (foundCities.isEmpty()) return;

        SettingColor cc = cityColor.get(), sc = shipColor.get();
        double hw = renderBoxSize.get() / 2.0;
        double maxSq = 500.0 * 500.0;
        double px = mc.player.getX(), py = mc.player.getY() + 1.0, pz = mc.player.getZ();

        for (FoundCity c : foundCities) {
            BlockPos pos = c.hasShip && c.elytraPos != null ? c.elytraPos : c.position;
            double dx = pos.getX() + 0.5 - px, dy = pos.getY() + 0.5 - py, dz = pos.getZ() + 0.5 - pz;
            if (dx * dx + dy * dy + dz * dz > maxSq) continue;

            SettingColor col = c.hasShip ? sc : cc;
            event.renderer.box(
                pos.getX() + 0.5 - hw, pos.getY() + 0.5 - hw, pos.getZ() + 0.5 - hw,
                pos.getX() + 0.5 + hw, pos.getY() + 0.5 + hw, pos.getZ() + 0.5 + hw,
                col, col, ShapeMode.Both, 0);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private long getWorldSeedLong() {
        try { return Long.parseLong(worldSeedStr.get().trim()); }
        catch (NumberFormatException e) { return 0L; }
    }

    private long detectWorldSeed() {
        try {
            if (mc.getServer() != null) {
                var sp = mc.getServer().getSaveProperties();
                if (sp != null) return sp.getGeneratorOptions().getSeed();
            }
        } catch (Exception ignored) {}
        return 0L;
    }

    @SuppressWarnings("unchecked")
    private MCVersion resolveVersion() {
        String v = mcVersion.get().trim();
        try { return MCVersion.fromString(v); } catch (Exception ignored) {}
        // fallback: common aliases → standard name
        Map<String, String> m = new LinkedHashMap<>();
        m.put("1.21.4", "1.21.4"); m.put("1.21.3", "1.21.3"); m.put("1.21.1", "1.21");
        m.put("1.21", "1.21"); m.put("1.20.6", "1.20.5"); m.put("1.20.4", "1.20.3");
        m.put("1.20.2", "1.20"); m.put("1.20.1", "1.20"); m.put("1.20", "1.20");
        m.put("1.19.4", "1.19"); m.put("1.19.3", "1.19"); m.put("1.19.2", "1.19");
        m.put("1.19.1", "1.19"); m.put("1.19", "1.19");
        m.put("1.18.2", "1.18"); m.put("1.18.1", "1.18"); m.put("1.18", "1.18");
        m.put("1.17.1", "1.17"); m.put("1.17", "1.17");
        m.put("1.16.5", "1.16_5"); m.put("1.16", "1.16");
        if (m.containsKey(v)) {
            try { return MCVersion.fromString(m.get(v)); } catch (Exception ignored) {}
        }
        return null;
    }

    private void info(String msg) {
        System.out.println("[ElytraFinder] " + msg);
        if (mc != null && mc.player != null)
            mc.player.sendMessage(Text.literal("§d[ElytraFinder] " + msg), false);
    }

    private void error(String msg) {
        System.err.println("[ElytraFinder] " + msg);
        if (mc != null && mc.player != null)
            mc.player.sendMessage(Text.literal("§c[ElytraFinder] " + msg), false);
    }

    @Override
    public String getInfoString() {
        if (lastError != null) return "err: " + lastError;
        if (searchTotal > 0 && searchProgress < searchTotal)
            return searchProgress + "/" + searchTotal;
        if (foundCities.isEmpty()) return getWorldSeedLong() != 0 ? "点击搜索" : "设置种子";
        long ships = foundCities.stream().filter(c -> c.hasShip).count();
        return foundCities.size() + "城/" + ships + "船";
    }

    // ── Inner class ─────────────────────────────────────────────────

    private static class FoundCity {
        final BlockPos position, elytraPos;
        final boolean hasShip;
        final int distance;

        FoundCity(BlockPos p, BlockPos ep, boolean hs, int d) {
            position = p; elytraPos = ep; hasShip = hs; distance = d;
        }
    }
}
