package io.momo.playerauction.auction;

import io.momo.playerauction.PlayerAuction;
import io.momo.playerauction.config.AuctionConfig;
import io.momo.playerauction.economy.EconomyUtils;
import io.momo.playerauction.storage.AuctionStorage;
import eu.pb4.common.economy.api.EconomyCurrency;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.nio.file.Path;

public class AuctionManager {

    private static AuctionManager INSTANCE;

    private final List<AuctionItem> activeAuctions = new CopyOnWriteArrayList<>();
    // 离线玩家的待送达物品: playerUUID -> ItemStack列表
    private final java.util.Map<UUID, java.util.List<ItemStack>> pendingDeliveries = new java.util.concurrent.ConcurrentHashMap<>();
    private MinecraftServer server;

    private AuctionManager() {}

    public static AuctionManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new AuctionManager();
        }
        return INSTANCE;
    }

    public void init(MinecraftServer server) {
        this.server = server;
        AuctionConfig.load(server);
        AuctionStorage.init();
        List<AuctionItem> loaded = AuctionStorage.load(server);
        activeAuctions.clear();
        activeAuctions.addAll(loaded);
        // 加载离线待送达物品
        setPendingDeliveries(AuctionStorage.loadPendingDeliveries(server));
        PlayerAuction.LOGGER.info("Loaded {} active auctions, {} pending deliveries",
                activeAuctions.size(), pendingDeliveries.size());
    }

    public MinecraftServer getServer() { return server; }

    // ==================== 核心操作 ====================

    /**
     * 上架物品 (SELL)
     */
    public String listItemForSale(ServerPlayerEntity player, int quantity, int price) {
        AuctionConfig config = AuctionConfig.getInstance();

        if (config.isPlayerBannedByUuid(player.getUuid())) {
            return "§c你已被禁止上架货物！";
        }

        if (getPlayerActiveListingsCount(player.getUuid()) >= config.getMaxListingsPerPlayer()) {
            return "§c你的上架数量已达到上限 (" + config.getMaxListingsPerPlayer() + ")！";
        }

        ItemStack heldItem = player.getMainHandStack();
        if (heldItem.isEmpty()) {
            return "§c请手持要出售的物品！";
        }

        String itemId = heldItem.getItem().toString();
        if (config.isItemBanned(itemId)) {
            return "§c该物品已被禁止上架！";
        }

        // 检查玩家是否有足够的物品数量
        int heldCount = countItemsInInventory(player, heldItem);
        if (heldCount < quantity) {
            return "§c你没有足够的物品！持有: " + heldCount + ", 需要: " + quantity;
        }

        // 从玩家背包中扣除物品之前，先复制一份用于储存
        ItemStack itemToStore = heldItem.copyWithCount(quantity);
        removeItemsFromInventory(player, heldItem, quantity);

        // 创建上架记录
        AuctionItem auctionItem = new AuctionItem(
                UUID.randomUUID(),
                player.getUuid(),
                player.getName().getString(),
                itemToStore,
                AuctionItem.AuctionType.SELL,
                price,
                quantity,
                System.currentTimeMillis(),
                System.currentTimeMillis() + config.getExpiryTimeMs()
        );

        activeAuctions.add(auctionItem);
        save();

        broadcast(
                auctionPrefix().append(Text.literal(player.getName().getString()))
                        .append(Text.literal(" §e上架了 §f"))
                        .append(itemToStore.getName())
                        .append(Text.literal(" §ex" + quantity + " §e单价 §6$" + price))
        );

        return "§a成功上架 " + itemToStore.getName().getString() + " x" + quantity + "！";
    }

    /**
     * 上架收购单 (BUY)
     */
    public String listItemForBuy(ServerPlayerEntity player, int quantity, int price) {
        AuctionConfig config = AuctionConfig.getInstance();

        if (config.isPlayerBannedByUuid(player.getUuid())) {
            return "§c你已被禁止上架货物！";
        }

        if (getPlayerActiveListingsCount(player.getUuid()) >= config.getMaxListingsPerPlayer()) {
            return "§c你的上架数量已达到上限 (" + config.getMaxListingsPerPlayer() + ")！";
        }

        EconomyCurrency currency = getCurrency(config);
        if (currency == null) {
            return "§c经济系统未配置！请联系管理员。";
        }

        long totalCost = (long) price * quantity;

        if (!EconomyUtils.hasBalance(player, currency, totalCost)) {
            long balance = EconomyUtils.getBalance(player, currency);
            return "§c余额不足！需要: " + EconomyUtils.formatMoney(currency, totalCost).getString() +
                    ", 持有: " + EconomyUtils.formatMoney(currency, balance).getString();
        }

        if (!EconomyUtils.deduct(player, currency, totalCost)) {
            return "§c扣款失败！";
        }

        // 创建占位物品
        ItemStack placeholder = player.getMainHandStack().copyWithCount(1);
        if (placeholder.isEmpty()) {
            placeholder = Items.PAPER.getDefaultStack();
            placeholder.set(DataComponentTypes.CUSTOM_NAME, Text.literal("收购单"));
        }

        AuctionItem auctionItem = new AuctionItem(
                UUID.randomUUID(),
                player.getUuid(),
                player.getName().getString(),
                placeholder,
                AuctionItem.AuctionType.BUY,
                price,
                quantity,
                System.currentTimeMillis(),
                System.currentTimeMillis() + config.getExpiryTimeMs()
        );

        activeAuctions.add(auctionItem);
        save();

        broadcast(
                auctionPrefix().append(Text.literal(player.getName().getString()))
                        .append(Text.literal(" §e发布了收购单: §f"))
                        .append(placeholder.getName())
                        .append(Text.literal(" §ex" + quantity + " §e单价 §6$" + price))
        );

        return "§a成功发布收购单！总花费: " + EconomyUtils.formatMoney(currency, totalCost).getString();
    }

    /**
     * 购买物品 (从SELL单购买) - 指定数量
     */
    public String buyItem(ServerPlayerEntity buyer, AuctionItem item, int quantity) {
        if (item.getType() != AuctionItem.AuctionType.SELL) {
            return "§c该物品不是出售类型！";
        }

        AuctionConfig config = AuctionConfig.getInstance();
        EconomyCurrency currency = getCurrency(config);
        if (currency == null) {
            return "§c经济系统未配置！";
        }

        if (quantity <= 0 || quantity > item.getQuantity()) {
            quantity = item.getQuantity();
        }

        long totalCost = (long) item.getPrice() * quantity;

        if (!EconomyUtils.hasBalance(buyer, currency, totalCost)) {
            return "§c余额不足！需要: " + EconomyUtils.formatMoney(currency, totalCost).getString();
        }

        if (!EconomyUtils.deduct(buyer, currency, totalCost)) {
            return "§c扣款失败！";
        }

        long taxAmount = (long) (totalCost * config.getTaxRate());
        long sellerGets = totalCost - taxAmount;

        // 给卖家打款（离线也支持）
        try {
            EconomyUtils.deposit(server, item.getUploaderUuid(), currency, sellerGets);
        } catch (Exception e) {
            PlayerAuction.LOGGER.warn("Failed to deposit to seller {}: {}", item.getUploaderUuid(), e.getMessage());
        }

        // 给买家物品
        ItemStack boughtItem = item.getItemStack().copyWithCount(quantity);
        String itemName = boughtItem.getName().getString(); // 在 offerOrDrop 之前保存！
        if (!boughtItem.isEmpty()) {
            buyer.getInventory().offerOrDrop(boughtItem);
        } else {
            return "§c物品数据异常，请报告管理员！";
        }

        // 从列表中移除（支持部分购买）
        boolean removed = activeAuctions.remove(item);
        if (!removed) {
            PlayerAuction.LOGGER.warn("buyItem: item {} not found in activeAuctions!", item.getId());
        }
        if (quantity < item.getQuantity()) {
            AuctionItem remaining = new AuctionItem(
                    UUID.randomUUID(), item.getUploaderUuid(), item.getUploaderName(),
                    item.getItemStack().copyWithCount(item.getQuantity() - quantity),
                    item.getType(), item.getPrice(), item.getQuantity() - quantity,
                    item.getListTime(), item.getEndTime()
            );
            activeAuctions.add(remaining);
        }
        save();

        ServerPlayerEntity seller = server.getPlayerManager().getPlayer(item.getUploaderUuid());
        if (seller != null) {
            seller.sendMessage(Text.literal("§6[拍卖] §a你的 " + itemName +
                    " x" + quantity + " 已被 §f" + buyer.getName().getString() + " §a购买！" +
                    " 收入: " + EconomyUtils.formatMoney(currency, sellerGets).getString()), false);
        }

        broadcast(
                auctionPrefix().append(Text.literal(buyer.getName().getString()))
                        .append(Text.literal(" §e购买了 §f"))
                        .append(Text.literal(item.getUploaderName()))
                        .append(Text.literal(" §e的 "))
                        .append(item.getItemStack().getName())
                        .append(Text.literal(" x" + quantity + " §e花费 "))
                        .append(EconomyUtils.formatMoney(currency, totalCost))
        );

        return "§a成功购买 " + itemName + " x" + quantity + "！";
    }

    /**
     * 购买物品 - 全量购买（兼容旧接口）
     */
    public String buyItem(ServerPlayerEntity buyer, AuctionItem item) {
        return buyItem(buyer, item, item.getQuantity());
    }

    /**
     * 接受收购单 (向BUY单出售物品) - 指定数量
     */
    public String sellToBuyOrder(ServerPlayerEntity seller, AuctionItem item, int quantity) {
        if (item.getType() != AuctionItem.AuctionType.BUY) {
            return "§c该物品不是收购类型！";
        }

        AuctionConfig config = AuctionConfig.getInstance();
        EconomyCurrency currency = getCurrency(config);
        if (currency == null) {
            return "§c经济系统未配置！";
        }

        if (quantity <= 0 || quantity > item.getQuantity()) {
            quantity = item.getQuantity();
        }

        ItemStack targetItem = item.getItemStack();
        int heldCount = countItemsInInventory(seller, targetItem);
        if (heldCount < quantity) {
            return "§c你没有足够的 " + targetItem.getName().getString() + "！持有: " + heldCount + ", 需要: " + quantity;
        }

        removeItemsFromInventory(seller, targetItem, quantity);

        // 给买家物品
        ServerPlayerEntity buyer = server.getPlayerManager().getPlayer(item.getUploaderUuid());
        ItemStack givenStack = targetItem.copyWithCount(quantity);
        if (buyer != null) {
            buyer.getInventory().offerOrDrop(givenStack);
        } else {
            PlayerAuction.LOGGER.info("Player {} offline, writing to ender chest...", item.getUploaderName());
            giveItemToPlayer(item.getUploaderUuid(), givenStack);
        }

        long totalValue = (long) item.getPrice() * quantity;
        long taxAmount = (long) (totalValue * config.getTaxRate());
        long sellerGets = totalValue - taxAmount;

        try {
            EconomyUtils.deposit(seller, currency, sellerGets);
        } catch (Exception e) {
            PlayerAuction.LOGGER.warn("Failed to deposit to seller {}: {}", seller.getUuid(), e.getMessage());
        }

        // 从列表中移除（支持部分收购）
        boolean removed = activeAuctions.remove(item);
        if (!removed) {
            PlayerAuction.LOGGER.warn("sellToBuyOrder: item {} not found in activeAuctions!", item.getId());
        }
        if (quantity < item.getQuantity()) {
            AuctionItem remaining = new AuctionItem(
                    UUID.randomUUID(), item.getUploaderUuid(), item.getUploaderName(),
                    item.getItemStack().copyWithCount(item.getQuantity() - quantity),
                    item.getType(), item.getPrice(), item.getQuantity() - quantity,
                    item.getListTime(), item.getEndTime()
            );
            activeAuctions.add(remaining);
        }
        save();

        if (buyer != null) {
            buyer.sendMessage(Text.literal("§6[拍卖] §f" + seller.getName().getString() +
                    " §e向你的收购单出售了 §f" + targetItem.getName().getString() + " x" + quantity), false);
        }

        broadcast(
                auctionPrefix().append(Text.literal(seller.getName().getString()))
                        .append(Text.literal(" §e向 §f"))
                        .append(Text.literal(item.getUploaderName()))
                        .append(Text.literal(" §e的收购单出售了 "))
                        .append(item.getItemStack().getName())
                        .append(Text.literal(" x" + quantity))
        );

        return "§a成功出售 " + targetItem.getName().getString() + " x" + quantity +
                "！收入: " + EconomyUtils.formatMoney(currency, sellerGets).getString();
    }

    /**
     * 接受收购单 - 全量出售（兼容旧接口）
     */
    public String sellToBuyOrder(ServerPlayerEntity seller, AuctionItem item) {
        return sellToBuyOrder(seller, item, item.getQuantity());
    }

    /**
     * 取消上架 (玩家取消自己的最近上架)
     */
    public String cancelListing(ServerPlayerEntity player) {
        List<AuctionItem> playerListings = getPlayerListings(player.getUuid());
        if (playerListings.isEmpty()) {
            return "§c你没有任何上架的货物！";
        }
        AuctionItem latest = playerListings.get(0); // 最新的在前
        return cancelListingInternal(player, latest);
    }

    /**
     * 取消指定上架
     */
    public String cancelListing(ServerPlayerEntity player, AuctionItem item) {
        if (!item.getUploaderUuid().equals(player.getUuid())) {
            return "§c这不是你的上架！";
        }
        return cancelListingInternal(player, item);
    }

    private String cancelListingInternal(ServerPlayerEntity player, AuctionItem item) {
        AuctionConfig config = AuctionConfig.getInstance();
        EconomyCurrency currency = getCurrency(config);

        activeAuctions.remove(item);
        save();

        if (item.getType() == AuctionItem.AuctionType.SELL) {
            player.getInventory().offerOrDrop(item.getItemStack());
            broadcast(
                    auctionPrefix().append(Text.literal(player.getName().getString()))
                            .append(Text.literal(" §e下架了: "))
                            .append(item.getItemStack().getName())
                            .append(Text.literal(" x" + item.getQuantity()))
            );
            return "§a已下架 " + item.getItemStack().getName().getString() + " x" + item.getQuantity() + "！";
        } else {
            long totalRefund = (long) item.getPrice() * item.getQuantity();
            if (currency != null) {
                EconomyUtils.deposit(server, player.getUuid(), currency, totalRefund);
            }
            broadcast(
                    auctionPrefix().append(Text.literal(player.getName().getString()))
                            .append(Text.literal(" §e取消了收购单: "))
                            .append(item.getItemStack().getName())
                            .append(Text.literal(" x" + item.getQuantity()))
            );
            return "§a已取消收购单，退款 " + (currency != null ? EconomyUtils.formatMoney(currency, totalRefund).getString() : "$" + totalRefund) + "！";
        }
    }

    /**
     * 管理员强制下架
     */
    public String adminRemove(ServerPlayerEntity admin, AuctionItem item) {
        AuctionConfig config = AuctionConfig.getInstance();
        EconomyCurrency currency = getCurrency(config);

        activeAuctions.remove(item);
        save();

        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(item.getUploaderUuid());

        if (item.getType() == AuctionItem.AuctionType.SELL) {
            if (owner != null) {
                owner.getInventory().offerOrDrop(item.getItemStack());
                owner.sendMessage(Text.literal("§c[拍卖] 你的 " + item.getItemStack().getName().getString() +
                        " x" + item.getQuantity() + " 已被管理员强制下架！"), false);
            }
        } else {
            long totalRefund = (long) item.getPrice() * item.getQuantity();
            if (currency != null) {
                EconomyUtils.deposit(server, item.getUploaderUuid(), currency, totalRefund);
            }
            if (owner != null) {
                owner.sendMessage(Text.literal("§c[拍卖] 你的收购单已被管理员强制取消，退款 " +
                        EconomyUtils.formatMoney(currency, totalRefund).getString()), false);
            }
        }

        return "§a已强制下架 " + item.getItemStack().getName().getString() + " x" + item.getQuantity() + "！";
    }

    // ==================== 过期处理 ====================

    /**
     * 检查并处理所有过期拍卖
     */
    public int checkExpired() {
        AuctionConfig config = AuctionConfig.getInstance();
        EconomyCurrency currency = getCurrency(config);
        long now = System.currentTimeMillis();

        List<AuctionItem> expired = new ArrayList<>();
        for (AuctionItem item : activeAuctions) {
            if (now > item.getEndTime()) {
                expired.add(item);
            }
        }

        for (AuctionItem item : expired) {
            activeAuctions.remove(item);
            returnToOwner(item, currency);
        }

        if (!expired.isEmpty()) {
            save();
        }

        return expired.size();
    }

    /**
     * 将过期的拍卖品返回给主人（在线→末影箱内存，离线→末影箱NBT）
     */
    private void returnToOwner(AuctionItem item, EconomyCurrency currency) {
        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(item.getUploaderUuid());

        if (item.getType() == AuctionItem.AuctionType.SELL) {
            ItemStack stack = item.getItemStack();
            if (owner != null) {
                ItemStack remainder = owner.getEnderChestInventory().addStack(stack);
                if (remainder.isEmpty()) {
                    owner.sendMessage(Text.literal("§c[拍卖] 你的 " + item.getItemStack().getName().getString() +
                            " x" + item.getQuantity() + " 已到期，已放入末影箱！"), false);
                } else {
                    owner.getInventory().offerOrDrop(remainder);
                    owner.sendMessage(Text.literal("§c[拍卖] 你的 " + item.getItemStack().getName().getString() +
                            " x" + item.getQuantity() + " 已到期，末影箱空间不足，已放入背包！"), false);
                }
            } else {
                // 玩家离线：写入末影箱NBT文件
                PlayerAuction.LOGGER.info("Returning expired items to offline player's ender chest: {} x{}",
                        stack.getName().getString(), stack.getCount());
                writeExpiredToOfflineEnderChest(item.getUploaderUuid(), stack);
            }
        } else {
            long totalRefund = (long) item.getPrice() * item.getQuantity();
            if (currency != null) {
                EconomyUtils.deposit(server, item.getUploaderUuid(), currency, totalRefund);
            }
            if (owner != null) {
                owner.sendMessage(Text.literal("§c[拍卖] 你的收购单 " + item.getItemStack().getName().getString() +
                        " x" + item.getQuantity() + " 已到期，已退款 " +
                        EconomyUtils.formatMoney(currency, totalRefund).getString() + "！"), false);
            }
        }
    }

    /**
     * 写入单个物品到离线玩家的末影箱NBT
     */
    private void writeExpiredToOfflineEnderChest(UUID playerUuid, ItemStack stack) {
        try {
            Path playerFile = server.getSavePath(WorldSavePath.PLAYERDATA)
                    .resolve(playerUuid.toString() + ".dat");

            if (!java.nio.file.Files.exists(playerFile)) {
                PlayerAuction.LOGGER.warn("Player data file not found: {}", playerFile);
                return;
            }

            NbtCompound playerData = NbtIo.readCompressed(playerFile, NbtSizeTracker.ofUnlimitedBytes());
            NbtList enderItems = playerData.getListOrEmpty("EnderItems");
            boolean listExisted = playerData.contains("EnderItems");
            if (!listExisted) {
                enderItems = new NbtList();
                playerData.put("EnderItems", enderItems);
            }

            // 找空位
            boolean[] slotUsed = new boolean[27];
            for (int i = 0; i < enderItems.size(); i++) {
                NbtElement elem = enderItems.get(i);
                if (elem instanceof NbtCompound comp) {
                    int slot = comp.getByte("Slot", (byte) -1);
                    if (slot >= 0 && slot < 27) slotUsed[slot] = true;
                }
            }

            RegistryOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, server.getRegistryManager());
            int slot = 0;
            while (slot < 27 && slotUsed[slot]) slot++;
            if (slot < 27) {
                NbtElement itemNbt = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
                if (itemNbt instanceof NbtCompound comp) {
                    comp.putByte("Slot", (byte) slot);
                    enderItems.add(comp);
                }
            }

            NbtIo.writeCompressed(playerData, playerFile);
            PlayerAuction.LOGGER.info("Returned {} x{} to ender chest of offline player {}",
                    stack.getName().getString(), stack.getCount(), playerUuid);
        } catch (Exception e) {
            PlayerAuction.LOGGER.error("Failed to return items to offline player {}: {}",
                    playerUuid, e.getMessage());
        }
    }

    // ==================== 查询方法 ====================

    public List<AuctionItem> getActiveAuctions() {
        List<AuctionItem> sorted = new ArrayList<>(activeAuctions);
        sorted.sort((a, b) -> Long.compare(b.getListTime(), a.getListTime()));
        return sorted;
    }

    public List<AuctionItem> searchAuctions(String query) {
        String lowerQuery = query.toLowerCase();
        return getActiveAuctions().stream()
                .filter(item -> {
                    String itemName = item.getItemStack().getName().getString().toLowerCase();
                    String uploaderName = item.getUploaderName().toLowerCase();
                    return itemName.contains(lowerQuery) || uploaderName.contains(lowerQuery);
                })
                .collect(Collectors.toList());
    }

    public List<AuctionItem> getPlayerListings(UUID playerUuid) {
        return activeAuctions.stream()
                .filter(item -> item.getUploaderUuid().equals(playerUuid))
                .sorted((a, b) -> Long.compare(b.getListTime(), a.getListTime()))
                .collect(Collectors.toList());
    }

    public int getPlayerActiveListingsCount(UUID playerUuid) {
        return (int) activeAuctions.stream()
                .filter(item -> item.getUploaderUuid().equals(playerUuid))
                .count();
    }

    public AuctionItem getById(UUID id) {
        return activeAuctions.stream()
                .filter(item -> item.getId().equals(id))
                .findFirst().orElse(null);
    }

    // ==================== 待送达物品 ====================

    /**
     * 给玩家物品：在线→末影箱内存，离线→末影箱NBT
     */
    public void giveItemToPlayer(UUID playerUuid, ItemStack stack) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
        if (player != null) {
            ItemStack remainder = player.getEnderChestInventory().addStack(stack.copy());
            if (!remainder.isEmpty()) {
                player.getInventory().offerOrDrop(remainder);
            }
        } else {
            queuePendingDelivery(playerUuid, stack);
        }
    }

    /**
     * 为离线玩家添加待送达物品
     */
    public void queuePendingDelivery(UUID playerUuid, ItemStack stack) {
        pendingDeliveries.computeIfAbsent(playerUuid, k -> new ArrayList<>()).add(stack.copy());
        savePendingDeliveries();
    }

    /**
     * 玩家上线时，直接放入末影箱内存 + 清理队列
     */
    public void deliverPendingItems(ServerPlayerEntity player) {
        List<ItemStack> deliveries = pendingDeliveries.remove(player.getUuid());
        if (deliveries == null || deliveries.isEmpty()) return;

        PlayerAuction.LOGGER.info("Delivering {} items to ender chest of player {}",
                deliveries.size(), player.getName().getString());

        int count = 0;
        for (ItemStack stack : deliveries) {
            if (!stack.isEmpty()) {
                ItemStack remainder = player.getEnderChestInventory().addStack(stack);
                if (!remainder.isEmpty()) {
                    player.getInventory().offerOrDrop(remainder);
                }
                count++;
            }
        }

        savePendingDeliveries();

        if (count > 0) {
            player.sendMessage(Text.literal("§6[拍卖] §a你有 " + count
                    + " 件物品在离线期间被购买，已放入末影箱！"), false);
        }
    }

    /**
     * 获取所有待送达数据 (用于保存到DB)
     */
    public java.util.Map<UUID, java.util.List<ItemStack>> getPendingDeliveries() {
        return pendingDeliveries;
    }

    /**
     * 设置待送达数据 (从DB加载)
     */
    public void setPendingDeliveries(java.util.Map<UUID, java.util.List<ItemStack>> data) {
        pendingDeliveries.clear();
        if (data != null) {
            pendingDeliveries.putAll(data);
        }
    }

    private void savePendingDeliveries() {
        if (server != null) {
            AuctionStorage.savePendingDeliveries(server, pendingDeliveries);
        }
    }

    // ==================== 辅助方法 ====================

    private void broadcast(Text message) {
        if (server != null) {
            server.getPlayerManager().broadcast(message, false);
        }
    }

    /**
     * 创建带可点击"[拍卖]"前缀的消息
     */
    private MutableText auctionPrefix() {
        return Text.literal("§6[拍卖]")
                .styled(style -> style.withClickEvent(
                        new net.minecraft.text.ClickEvent.RunCommand("/auction open")
                ))
                .append(Text.literal(" §f"));
    }

    private void save() {
        if (server != null) {
            AuctionStorage.save(server, getActiveAuctions());
        }
    }

    private EconomyCurrency getCurrency(AuctionConfig config) {
        if (server == null) return null;
        EconomyCurrency currency = EconomyUtils.getCurrency(server, config.getCurrencyId());
        if (currency == null) {
            currency = EconomyUtils.getDefaultCurrency(server);
        }
        return currency;
    }

    /**
     * 统计玩家背包中某物品的总数量
     */
    private int countItemsInInventory(ServerPlayerEntity player, ItemStack target) {
        int count = 0;
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < inv.size(); slot++) {
            ItemStack stack = inv.getStack(slot);
            if (!stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, target)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * 从玩家背包中移除指定数量的物品
     */
    private void removeItemsFromInventory(ServerPlayerEntity player, ItemStack target, int amount) {
        int remaining = amount;
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < inv.size() && remaining > 0; slot++) {
            ItemStack stack = inv.getStack(slot);
            if (!stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, target)) {
                int toRemove = Math.min(stack.getCount(), remaining);
                stack.decrement(toRemove);
                remaining -= toRemove;
                if (stack.isEmpty()) {
                    inv.setStack(slot, ItemStack.EMPTY);
                }
            }
        }
    }
}
