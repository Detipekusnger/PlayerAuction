package io.momo.playerauction.auction;

import io.momo.playerauction.PlayerAuction;

/**
 * 拍卖过期检查 - 通过 ServerTick 驱动
 */
public class AuctionExpired {

    private int tickCounter = 0;

    private static final AuctionExpired INSTANCE = new AuctionExpired();

    public static AuctionExpired getInstance() {
        return INSTANCE;
    }

    /**
     * 每个 tick 调用一次（每20tick=1秒检查一次）
     */
    public void onTick() {
        tickCounter++;
        if (tickCounter >= 20) {  // 每秒检查一次
            tickCounter = 0;
            int expired = AuctionManager.getInstance().checkExpired();
            if (expired > 0) {
                PlayerAuction.LOGGER.info("Processed {} expired auctions", expired);
            }
        }
    }
}
