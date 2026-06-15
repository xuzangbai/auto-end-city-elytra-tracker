package com.autoelytra.addon;

import com.autoelytra.addon.commands.CommandExample;
import com.autoelytra.addon.hud.HudExample;
import com.autoelytra.addon.modules.ElytraInfiniteFlight;
import com.autoelytra.addon.modules.ItemFrameSearch;
import com.autoelytra.addon.modules.LadderFinder;
import com.autoelytra.addon.modules.LockEnd;
import com.autoelytra.addon.modules.LockStart;
import com.autoelytra.addon.modules.ModuleExample;
import com.autoelytra.addon.modules.Run;
import com.autoelytra.addon.modules.StowElytra;
import com.autoelytra.addon.modules.StoreResupply;
import com.autoelytra.addon.modules.WaypointLock;
import com.autoelytra.addon.modules.WaypointReader;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class AddonTemplate extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("AutoElytra");
    public static final HudGroup HUD_GROUP = new HudGroup("AutoElytra");

    @Override
    public void onInitialize() {
        LOG.info("Initializing AutoElytraV1");

        // Modules
        Modules.get().add(new ModuleExample());
        Modules.get().add(new WaypointReader());
        Modules.get().add(new WaypointLock());
        Modules.get().add(new LadderFinder());
        Modules.get().add(new ElytraInfiniteFlight());
        Modules.get().add(new LockStart());
        Modules.get().add(new LockEnd());
        Modules.get().add(new ItemFrameSearch());
        Modules.get().add(new StoreResupply());
        Modules.get().add(new StowElytra());
        Modules.get().add(new Run());

        // Commands
        Commands.add(new CommandExample());

        // HUD
        Hud.get().register(HudExample.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.autoelytra.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("MeteorDevelopment", "meteor-addon-template");
    }
}
