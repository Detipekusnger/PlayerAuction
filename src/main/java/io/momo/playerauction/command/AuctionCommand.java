package io.momo.playerauction.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import io.momo.playerauction.auction.AuctionItem;
import io.momo.playerauction.auction.AuctionManager;
import io.momo.playerauction.config.AuctionConfig;
import io.momo.playerauction.economy.EconomyUtils;
import io.momo.playerauction.gui.AdminRemoveGui;
import io.momo.playerauction.gui.AuctionMarketGui;
import eu.pb4.common.economy.api.EconomyCurrency;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.Predicate;

import static net.minecraft.server.command.CommandManager.*;

public class AuctionCommand {

    /**
     * 权限检查：需要op等级4 (OWNERS)
     */
    private static final Predicate<ServerCommandSource> OP_4 = src ->
            src.getPermissions().hasPermission(new Permission.Level(PermissionLevel.OWNERS));

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("auction")
                .then(literal("open")
                        .executes(AuctionCommand::openMarket))
                .then(literal("sell")
                        .then(argument("quantity", IntegerArgumentType.integer(1))
                                .then(argument("price", IntegerArgumentType.integer(1))
                                        .executes(AuctionCommand::sellItem))))
                .then(literal("buy")
                        .then(argument("quantity", IntegerArgumentType.integer(1))
                                .then(argument("price", IntegerArgumentType.integer(1))
                                        .executes(AuctionCommand::buyItem))))
                .then(literal("me")
                        .executes(AuctionCommand::myListings))
                .then(literal("cancel")
                        .executes(AuctionCommand::cancelListing))
                .then(literal("remove-mine")
                        .executes(AuctionCommand::removeMine))
                .then(literal("search")
                        .then(argument("query", StringArgumentType.greedyString())
                                .executes(AuctionCommand::searchMarket)))
                // ===== 管理员命令 =====
                .then(literal("currency")
                        .requires(OP_4)
                        .then(argument("id", StringArgumentType.string())
                                .executes(AuctionCommand::setCurrency)))
                .then(literal("expired")
                        .requires(OP_4)
                        .then(argument("seconds", IntegerArgumentType.integer(60))
                                .executes(AuctionCommand::setExpired)))
                .then(literal("ban")
                        .requires(OP_4)
                        .then(argument("player", EntityArgumentType.player())
                                .executes(AuctionCommand::banPlayer)))
                .then(literal("unban")
                        .requires(OP_4)
                        .then(argument("player", EntityArgumentType.player())
                                .executes(AuctionCommand::unbanPlayer)))
                .then(literal("remove")
                        .requires(OP_4)
                        .executes(AuctionCommand::adminRemove))
                .then(literal("tax")
                        .requires(OP_4)
                        .then(argument("rate", DoubleArgumentType.doubleArg(0.0, 1.0))
                                .executes(AuctionCommand::setTax)))
                .then(literal("limit")
                        .requires(OP_4)
                        .then(argument("count", IntegerArgumentType.integer(1, 100))
                                .executes(AuctionCommand::setLimit)))
                .then(literal("ban-item")
                        .requires(OP_4)
                        .then(argument("item", StringArgumentType.string())
                                .executes(AuctionCommand::banItem)))
                .then(literal("unban-item")
                        .requires(OP_4)
                        .then(argument("item", StringArgumentType.string())
                                .executes(AuctionCommand::unbanItem)))
                .then(literal("currencies")
                        .requires(OP_4)
                        .executes(AuctionCommand::listCurrencies))
                .executes(ctx -> {
                    sendHelp(ctx.getSource());
                    return Command.SINGLE_SUCCESS;
                })
        );
    }

    // ===== 玩家命令 =====

    private static int openMarket(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        AuctionManager.getInstance().deliverPendingItems(player);
        new AuctionMarketGui(player);
        return Command.SINGLE_SUCCESS;
    }

    private static int searchMarket(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        String query = StringArgumentType.getString(ctx, "query");
        AuctionManager.getInstance().deliverPendingItems(player);
        new AuctionMarketGui(player, query);
        return Command.SINGLE_SUCCESS;
    }

    private static int sellItem(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        int quantity = IntegerArgumentType.getInteger(ctx, "quantity");
        int price = IntegerArgumentType.getInteger(ctx, "price");
        String result = AuctionManager.getInstance().listItemForSale(player, quantity, price);
        player.sendMessage(Text.literal(result), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int buyItem(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        int quantity = IntegerArgumentType.getInteger(ctx, "quantity");
        int price = IntegerArgumentType.getInteger(ctx, "price");
        String result = AuctionManager.getInstance().listItemForBuy(player, quantity, price);
        player.sendMessage(Text.literal(result), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int myListings(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        List<AuctionItem> listings = AuctionManager.getInstance().getPlayerListings(player.getUuid());
        if (listings.isEmpty()) {
            player.sendMessage(Text.literal("§c你没有任何上架！"), false);
            return Command.SINGLE_SUCCESS;
        }

        player.sendMessage(Text.literal("§6===== 我的商店 (" + listings.size() + ") ====="), false);
        for (int i = 0; i < listings.size(); i++) {
            AuctionItem item = listings.get(i);
            String typeStr = item.getType() == AuctionItem.AuctionType.SELL ? "§c出售" : "§a收购";
            player.sendMessage(Text.literal(String.format(
                    "§7[%d] %s §f%s §7x%d §7单价 §6$%d §7| %s §7| §7ID: %s",
                    i + 1,
                    typeStr,
                    item.getItemStack().getName().getString(),
                    item.getQuantity(),
                    item.getPrice(),
                    item.isExpired() ? "§c已过期" : "§a进行中",
                    item.getId().toString().substring(0, 8)
            )), false);
        }
        player.sendMessage(Text.literal("§7使用 §e/auction cancel §7取消最近上架"), false);
        player.sendMessage(Text.literal("§7使用 §e/auction open §7打开市场"), false);

        return Command.SINGLE_SUCCESS;
    }

    private static int cancelListing(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        String result = AuctionManager.getInstance().cancelListing(player);
        player.sendMessage(Text.literal(result), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 打开我的商店GUI，点击下架货物
     */
    private static int removeMine(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        new io.momo.playerauction.gui.PlayerRemoveGui(player);
        return Command.SINGLE_SUCCESS;
    }

    // ===== 管理员命令 =====

    private static int setCurrency(CommandContext<ServerCommandSource> ctx) {
        String currencyId = StringArgumentType.getString(ctx, "id");
        EconomyCurrency currency = EconomyUtils.getCurrency(ctx.getSource().getServer(), currencyId);
        if (currency == null) {
            ctx.getSource().sendError(Text.literal("§c未找到货币: " + currencyId));
            ctx.getSource().sendFeedback(() -> Text.literal("§7使用 §e/auction currencies §7查看所有可用货币"), false);
            return 0;
        }
        AuctionConfig config = AuctionConfig.getInstance();
        config.setCurrencyId(currencyId);
        config.save();
        ctx.getSource().sendFeedback(() ->
                Text.literal("§a已设置默认货币为: " + currency.name().getString() + " (" + currencyId + ")"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int setExpired(CommandContext<ServerCommandSource> ctx) {
        int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
        AuctionConfig config = AuctionConfig.getInstance();
        config.setExpiryTimeMs(seconds * 1000L);
        config.save();

        String display;
        if (seconds >= 86400) display = (seconds / 86400) + "天";
        else if (seconds >= 3600) display = (seconds / 3600) + "小时";
        else display = seconds + "秒";

        ctx.getSource().sendFeedback(() ->
                Text.literal("§a已设置拍卖到期时间为: " + display + " (" + seconds + "秒)"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int banPlayer(CommandContext<ServerCommandSource> ctx) {
        try {
            ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
            AuctionConfig config = AuctionConfig.getInstance();
            config.getBannedPlayers().add(target.getUuid().toString());
            config.save();
            target.sendMessage(Text.literal("§c你已被禁止使用拍卖系统！"), false);
            ctx.getSource().sendFeedback(() ->
                    Text.literal("§a已禁止玩家 " + target.getName().getString() + " 使用拍卖系统"), true);
        } catch (Exception e) {
            ctx.getSource().sendError(Text.literal("§c指令用法: /auction ban <玩家>"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int unbanPlayer(CommandContext<ServerCommandSource> ctx) {
        try {
            ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
            AuctionConfig config = AuctionConfig.getInstance();
            config.getBannedPlayers().remove(target.getUuid().toString());
            config.save();
            target.sendMessage(Text.literal("§a你已被解禁，可以再次使用拍卖系统！"), false);
            ctx.getSource().sendFeedback(() ->
                    Text.literal("§a已解除玩家 " + target.getName().getString() + " 的拍卖禁令"), true);
        } catch (Exception e) {
            ctx.getSource().sendError(Text.literal("§c指令用法: /auction unban <玩家>"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int adminRemove(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity admin = ctx.getSource().getPlayer();
        if (admin == null) return 0;
        new AdminRemoveGui(admin);
        return Command.SINGLE_SUCCESS;
    }

    private static int setTax(CommandContext<ServerCommandSource> ctx) {
        double rate = DoubleArgumentType.getDouble(ctx, "rate");
        AuctionConfig config = AuctionConfig.getInstance();
        config.setTaxRate(rate);
        config.save();
        ctx.getSource().sendFeedback(() ->
                Text.literal("§a已设置税率为: " + (rate * 100) + "%"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int setLimit(CommandContext<ServerCommandSource> ctx) {
        int count = IntegerArgumentType.getInteger(ctx, "count");
        AuctionConfig config = AuctionConfig.getInstance();
        config.setMaxListingsPerPlayer(count);
        config.save();
        ctx.getSource().sendFeedback(() ->
                Text.literal("§a已设置玩家最大上架数量为: " + count), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int banItem(CommandContext<ServerCommandSource> ctx) {
        String itemName = StringArgumentType.getString(ctx, "item");
        AuctionConfig config = AuctionConfig.getInstance();
        config.getBannedItems().add(itemName);
        config.save();
        ctx.getSource().sendFeedback(() ->
                Text.literal("§a已禁止物品上架: " + itemName), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int unbanItem(CommandContext<ServerCommandSource> ctx) {
        String itemName = StringArgumentType.getString(ctx, "item");
        AuctionConfig config = AuctionConfig.getInstance();
        config.getBannedItems().remove(itemName);
        config.save();
        ctx.getSource().sendFeedback(() ->
                Text.literal("§a已解除物品禁令: " + itemName), true);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 列出所有可用货币
     */
    private static int listCurrencies(CommandContext<ServerCommandSource> ctx) {
        var text = io.momo.playerauction.economy.EconomyUtils.getAllCurrenciesText(ctx.getSource().getServer());
        for (String line : text.split("\n")) {
            ctx.getSource().sendFeedback(() -> Text.literal(line), false);
        }
        ctx.getSource().sendFeedback(() -> Text.literal("§7使用 §e/auction currency <ID> §7设置货币"), false);
        return Command.SINGLE_SUCCESS;
    }

    // ===== 帮助 =====

    private static void sendHelp(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("§6===== 拍卖市场指令帮助 ====="), false);
        source.sendFeedback(() -> Text.literal(""), false);
        source.sendFeedback(() -> Text.literal("§e玩家指令:"), false);
        source.sendFeedback(() -> Text.literal("  §a/auction open §7- 打开拍卖市场"), false);
        source.sendFeedback(() -> Text.literal("  §a/auction sell <数量> <价格> §7- 出售手持物品"), false);
        source.sendFeedback(() -> Text.literal("  §a/auction buy <数量> <价格> §7- 发布收购单"), false);
        source.sendFeedback(() -> Text.literal("  §a/auction me §7- 查看我的上架"), false);
        source.sendFeedback(() -> Text.literal("  §a/auction cancel §7- 取消最近的上架"), false);
        source.sendFeedback(() -> Text.literal("  §a/auction remove-mine §7- 打开我的商店GUI下架"), false);
        source.sendFeedback(() -> Text.literal("  §a/auction search <关键词> §7- 搜索并打开市场"), false);

        boolean isOp = source.getPermissions()
                .hasPermission(new Permission.Level(PermissionLevel.OWNERS));
        if (isOp) {
            source.sendFeedback(() -> Text.literal(""), false);
            source.sendFeedback(() -> Text.literal("§c管理员指令:"), false);
            source.sendFeedback(() -> Text.literal("  §c/auction currency <EcoProvider:CurrencyId> §7- 设置货币"), false);
            source.sendFeedback(() -> Text.literal("  §c/auction currencies §7- 列出所有可用货币"), false);
            source.sendFeedback(() -> Text.literal("  §c/auction expired <秒数> §7- 设置到期时间"), false);
            source.sendFeedback(() -> Text.literal("  §c/auction ban <玩家> §7- 禁止玩家上架"), false);
            source.sendFeedback(() -> Text.literal("  §c/auction unban <玩家> §7- 解禁玩家"), false);
            source.sendFeedback(() -> Text.literal("  §c/auction remove §7- 强制下架菜单"), false);
            source.sendFeedback(() -> Text.literal("  §c/auction tax <0.0~1.0> §7- 设置税率"), false);
            source.sendFeedback(() -> Text.literal("  §c/auction limit <数字> §7- 设置上架上限"), false);
            source.sendFeedback(() -> Text.literal("  §c/auction ban-item <物品ID> §7- 禁止物品上架"), false);
            source.sendFeedback(() -> Text.literal("  §c/auction unban-item <物品ID> §7- 解除物品禁令"), false);
        }
    }
}
