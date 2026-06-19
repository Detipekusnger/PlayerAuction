package io.momo.playerauction;

import io.momo.playerauction.auction.AuctionExpired;
import io.momo.playerauction.auction.AuctionManager;
import io.momo.playerauction.command.AuctionCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.Date;

public class PlayerAuction implements ModInitializer {
    public static final String MOD_ID = "playerauction";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    @Override
    public void onInitialize() {
        LOGGER.info("PlayerAuction 拍卖市场模组加载中...");

        // 服务器启动时初始化
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            AuctionManager.getInstance().init(server);
            LOGGER.info("PlayerAuction 拍卖市场已就绪！");
        });

        // 注册指令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            AuctionCommand.register(dispatcher);
            LOGGER.info("PlayerAuction 指令已注册");
        });

        // 注册 Tick 事件用于过期检查
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            AuctionExpired.getInstance().onTick();
        });

        // 注册玩家加入事件，交付离线期间的待送达物品
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            AuctionManager.getInstance().deliverPendingItems(handler.getPlayer());
        });

        LOGGER.info("PlayerAuction 拍卖市场模组加载完成！");
    }

    /**
     * 格式化时间戳为可读字符串
     */
    public static String formatTimestamp(long timestamp) {
        return DATE_FORMAT.format(new Date(timestamp));
    }
}
