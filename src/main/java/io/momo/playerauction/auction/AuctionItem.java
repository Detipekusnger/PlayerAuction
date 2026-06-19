package io.momo.playerauction.auction;

import io.momo.playerauction.PlayerAuction;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AuctionItem {
    private final UUID id;                 // 唯一标识
    private final UUID uploaderUuid;
    private final String uploaderName;
    private final ItemStack itemStack;
    private final AuctionType type;
    private final int price;
    private final int quantity;
    private final long listTime;           // 上架时间戳（毫秒）
    private final long endTime;

    public AuctionItem(UUID id, UUID uploaderUuid, String uploaderName, ItemStack itemStack,
                       AuctionType type, int price, int quantity, long listTime, long endTime) {
        this.id = id;
        this.uploaderUuid = uploaderUuid;
        this.uploaderName = uploaderName;
        this.itemStack = itemStack.copy();
        this.type = type;
        this.price = price;
        this.quantity = quantity;
        this.listTime = listTime;
        this.endTime = endTime;
    }

    public UUID getId() { return id; }
    public UUID getUploaderUuid() { return uploaderUuid; }
    public String getUploaderName() { return uploaderName; }
    public ItemStack getItemStack() { return itemStack.copy(); }
    public AuctionType getType() { return type; }
    public int getPrice() { return price; }
    public int getQuantity() { return quantity; }
    public long getListTime() { return listTime; }
    public long getEndTime() { return endTime; }

    public boolean isExpired() {
        return System.currentTimeMillis() > endTime;
    }

    /**
     * 为拍卖品生成Lore显示
     */
    public List<Text> generateLore() {
        List<Text> lore = new ArrayList<>();
        lore.add(Text.literal(""));

        // 上架者
        lore.add(Text.literal("§7上架者: §f" + uploaderName));

        // 类型 (买/卖)
        String typeStr = type == AuctionType.SELL ? "§c出售" : "§a收购";
        lore.add(Text.literal("§7类型: " + typeStr));

        // 数量
        lore.add(Text.literal("§7数量: §f" + quantity));

        // 价格
        lore.add(Text.literal("§7单价: §6$" + price));
        lore.add(Text.literal("§7总价: §6$" + ((long) price * quantity)));

        // 到期时间
        lore.add(Text.literal(""));
        lore.add(Text.literal("§7到期: " + formatTimeRemaining()));
        lore.add(Text.literal("§7上架时间: §8" + PlayerAuction.formatTimestamp(listTime)));

        lore.add(Text.literal(""));
        lore.add(Text.literal("§e点击以" + (type == AuctionType.SELL ? "购买" : "出售") + "此物品"));

        return lore;
    }

    /**
     * 为个人商店页面生成Lore (包含取消操作提示)
     */
    public List<Text> generateMyLore() {
        List<Text> lore = generateLore();
        lore.add(Text.literal(""));
        lore.add(Text.literal("§c右键点击以取消上架"));
        return lore;
    }

    /**
     * 格式化剩余时间
     */
    private String formatTimeRemaining() {
        long remaining = endTime - System.currentTimeMillis();
        if (remaining <= 0) return "§c已过期";

        long seconds = remaining / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return "§e" + days + "天 " + (hours % 24) + "小时";
        } else if (hours > 0) {
            return "§e" + hours + "小时 " + (minutes % 60) + "分钟";
        } else if (minutes > 0) {
            return "§e" + minutes + "分钟";
        } else {
            return "§c不到1分钟";
        }
    }

    public enum AuctionType { BUY, SELL }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AuctionItem that = (AuctionItem) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
