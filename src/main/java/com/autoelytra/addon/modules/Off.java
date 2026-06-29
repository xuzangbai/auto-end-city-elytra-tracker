package com.autoelytra.addon.modules;

import com.autoelytra.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.orbit.EventHandler;

public class Off extends Module {

    public Off() {
        super(AddonTemplate.CATEGORY, "off", "一键关闭所有飞行/锁定/补给功能");
    }

    @Override
    public void onActivate() {
        Modules modules = Modules.get();

        // 本插件模块
        toggleIfActive(modules, SpiralFlight.class);
        toggleIfActive(modules, StoreResupply.class);
        toggleIfActive(modules, StowElytra.class);
        toggleIfActive(modules, WaypointLock.class);
        toggleIfActive(modules, LockStart.class);
        toggleIfActive(modules, LockEnd.class);
        toggleIfActive(modules, ElytraInfiniteFlight.class);

        // 彗星本体鞘翅飞行
        toggleIfActive(modules, ElytraFly.class);

        // 关闭自身
        this.toggle();
    }

    private void toggleIfActive(Modules modules, Class<? extends Module> clazz) {
        Module m = modules.get(clazz);
        if (m != null && m.isActive()) {
            m.toggle();
        }
    }
}
