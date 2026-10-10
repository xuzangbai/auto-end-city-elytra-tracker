package com.autoelytra.addon.utils;

import com.autoelytra.addon.mixin.ClientWorldAccessor;
import net.minecraft.client.network.PendingUpdateManager;
import net.minecraft.client.network.SequencedPacketCreator;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 数据包工具。
 * 方块相关操作（放置/挖掘）必须带 PendingUpdateManager 的自增序号——
 * 这是原版客户端 ClientPlayerInteractionManager.sendSequencedPacket 的做法，
 * 序号写死 0 会被服务端当作无效预测。
 */
public final class PacketUtils {

    private PacketUtils() {}

    public static void sendSequencedPacket(SequencedPacketCreator packetCreator) {
        if (mc.getNetworkHandler() == null || mc.world == null) return;
        try (PendingUpdateManager pum =
                ((ClientWorldAccessor) mc.world).invokeGetPendingUpdateManager().incrementSequence()) {
            int i = pum.getSequence();
            mc.getNetworkHandler().sendPacket(packetCreator.predict(i));
        }
    }
}
