package com.autoelytra.addon.modules;

import com.autoelytra.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.text.Text;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class MinHeightLog extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Integer> minY = sg.add(new IntSetting.Builder().name("min-y").description("低于此Y坐标退出游戏").defaultValue(40).min(-64).max(320).build());
    private final Setting<String> message = sg.add(new StringSetting.Builder().name("message").description("退出时显示的信息").defaultValue("高度低于设定值，自动断开").build());

    private boolean fired;

    public MinHeightLog() { super(AddonTemplate.CATEGORY, "min-height-log", "低于设定高度自动退出游戏"); }

    @Override public void onActivate() { fired = false; }
    @Override public void onDeactivate() { fired = false; }

    @EventHandler void tick(TickEvent.Pre e) {
        if (mc.player == null || mc.world == null || fired) return;
        if (mc.player.getY() < minY.get()) {
            fired = true;
            info(message.get());
            if (mc.getNetworkHandler() != null) {
                mc.getNetworkHandler().getConnection().disconnect(Text.literal(message.get()));
            }
            toggle();
        }
    }
}
