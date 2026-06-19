package io.momo.playerauction.economy;

import com.mojang.authlib.GameProfile;
import eu.pb4.common.economy.api.CommonEconomy;
import eu.pb4.common.economy.api.EconomyAccount;
import eu.pb4.common.economy.api.EconomyCurrency;
import eu.pb4.common.economy.api.EconomyProvider;
import eu.pb4.common.economy.api.EconomyTransaction;
import io.momo.playerauction.PlayerAuction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class EconomyUtils {

    // 缓存已找到的货币，避免重复日志
    private static final Map<String, String> foundCurrencyCache = new HashMap<>();

    /**
     * 获取指定ID的货币，支持多种格式
     */
    public static EconomyCurrency getCurrency(MinecraftServer server, String currencyId) {
        if (currencyId == null || currencyId.isEmpty()) return null;

        // 方法1：直接解析Identifier查找
        try {
            Identifier id = Identifier.of(currencyId);
            EconomyCurrency found = CommonEconomy.getCurrency(server, id);
            if (found != null) {
                logCurrencyFoundOnce(currencyId, found.id().toString());
                return found;
            }
        } catch (Exception ignored) {}

        // 方法2：遍历所有提供者和货币，匹配ID/路径/名称
        String lowerSearch = currencyId.toLowerCase();
        String searchPath = lowerSearch.contains(":") ? lowerSearch.split(":")[1] : lowerSearch;

        for (EconomyProvider provider : CommonEconomy.providers()) {
            for (EconomyCurrency currency : provider.getCurrencies(server)) {
                String fullId = currency.id().toString().toLowerCase();
                String path = currency.id().getPath().toLowerCase();
                String name = currency.name().getString().toLowerCase();

                if (fullId.equals(lowerSearch) || path.equals(lowerSearch)
                        || name.equals(lowerSearch)
                        || path.equals(searchPath) || name.equals(searchPath)) {
                    logCurrencyFoundOnce(currencyId, fullId);
                    return currency;
                }
            }
        }

        // 方法3：分隔符兼容（下划线↔连字符）
        if (currencyId.contains("-") || currencyId.contains("_")) {
            String altId = currencyId.replace('-', '_');
            if (!altId.equals(currencyId)) {
                try {
                    Identifier altIdentifier = Identifier.of(altId);
                    EconomyCurrency found = CommonEconomy.getCurrency(server, altIdentifier);
                    if (found != null) {
                        logCurrencyFoundOnce(currencyId, found.id().toString());
                        return found;
                    }
                } catch (Exception ignored) {}
            }
            altId = currencyId.replace('_', '-');
            if (!altId.equals(currencyId)) {
                try {
                    Identifier altIdentifier = Identifier.of(altId);
                    EconomyCurrency found = CommonEconomy.getCurrency(server, altIdentifier);
                    if (found != null) {
                        logCurrencyFoundOnce(currencyId, found.id().toString());
                        return found;
                    }
                } catch (Exception ignored) {}
            }
        }

        // 未找到，回退到第一个可用货币
        Collection<EconomyCurrency> allCurrencies = CommonEconomy.getCurrencies(server);
        if (!allCurrencies.isEmpty()) {
            EconomyCurrency fallback = allCurrencies.iterator().next();
            PlayerAuction.LOGGER.warn("Currency '{}' not found, falling back to first available: {}",
                    currencyId, fallback.id());
            return fallback;
        }

        return null;
    }

    /**
     * 每个currencyId只打印一次日志
     */
    private static void logCurrencyFoundOnce(String searchId, String foundId) {
        if (!foundCurrencyCache.containsKey(searchId)) {
            foundCurrencyCache.put(searchId, foundId);
            PlayerAuction.LOGGER.info("Resolved currency '{}' -> '{}'", searchId, foundId);
        }
    }

    /**
     * 打印所有可用货币（用于 /auction currencies 命令）
     */
    public static String getAllCurrenciesText(MinecraftServer server) {
        StringBuilder sb = new StringBuilder();
        for (EconomyProvider provider : CommonEconomy.providers()) {
            sb.append("§7提供者: §f").append(provider.id()).append("\n");
            for (EconomyCurrency currency : provider.getCurrencies(server)) {
                sb.append("  §7- §a").append(currency.id())
                  .append(" §7(名称: §f").append(currency.name().getString()).append("§7)\n");
            }
        }
        return sb.toString();
    }

    /**
     * 获取默认货币
     */
    public static EconomyCurrency getDefaultCurrency(MinecraftServer server) {
        Collection<EconomyCurrency> currencies = CommonEconomy.getCurrencies(server);
        if (currencies.isEmpty()) return null;
        return currencies.iterator().next();
    }

    /**
     * 获取玩家账户——直接通过货币的Provider获取默认账户
     */
    public static EconomyAccount getAccount(ServerPlayerEntity player, EconomyCurrency currency) {
        if (currency == null || player == null) return null;

        // 方式1：通过Provider的getDefaultAccount
        try {
            EconomyAccount account = currency.provider().getDefaultAccount(player, currency);
            if (account != null) return account;
        } catch (Exception ignored) {}

        // 方式2：通过CommonEconomy API
        try {
            EconomyAccount account = CommonEconomy.getAccount(player, currency.id());
            if (account != null) return account;
        } catch (Exception ignored) {}

        // 方式3：获取所有账户，找到有余额的
        try {
            for (EconomyAccount acc : currency.provider().getAccounts(player, currency)) {
                if (acc.balance() > 0) return acc;
            }
            var accounts = currency.provider().getAccounts(player, currency);
            if (!accounts.isEmpty()) return accounts.iterator().next();
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * 获取离线玩家账户——直接使用UUID
     */
    public static EconomyAccount getAccount(MinecraftServer server, UUID playerUuid, EconomyCurrency currency) {
        // 方式1：玩家在线
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
        if (player != null) {
            return getAccount(player, currency);
        }

        // 方式2：使用UUID字符串作为账户ID直接查找
        GameProfile profile = new GameProfile(playerUuid, playerUuid.toString().substring(0, 8));
        try {
            EconomyAccount account = currency.provider().getDefaultAccount(server, profile, currency);
            if (account != null) return account;
        } catch (Exception ignored) {}

        // 方式3：用完整UUID字符串尝试获取
        try {
            EconomyAccount account = CommonEconomy.getAccount(server, profile, currency.id());
            if (account != null) return account;
        } catch (Exception ignored) {}

        // 方式4：直接用UUID.toString()作为accountId
        try {
            EconomyAccount account = currency.provider().getAccount(server, profile, playerUuid.toString());
            if (account != null) return account;
        } catch (Exception ignored) {}

        // 方式5：用短UUID作为accountId
        try {
            EconomyAccount account = currency.provider().getAccount(server, profile, playerUuid.toString().substring(0, 8));
            if (account != null) return account;
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * 检查玩家余额是否足够
     */
    public static boolean hasBalance(ServerPlayerEntity player, EconomyCurrency currency, long amount) {
        EconomyAccount account = getAccount(player, currency);
        if (account == null) {
            PlayerAuction.LOGGER.warn("hasBalance: account is null for player {}, currency {}",
                    player.getName().getString(), currency.id());
            return false;
        }
        boolean canDecrease = account.canDecreaseBalance(amount).isSuccessful();
        if (!canDecrease) {
            PlayerAuction.LOGGER.info("Balance insufficient: player={}, currency={}, balance={}, need={}",
                    player.getName().getString(), currency.id(), account.balance(), amount);
        }
        return canDecrease;
    }

    /**
     * 从玩家账户扣款
     */
    public static boolean deduct(ServerPlayerEntity player, EconomyCurrency currency, long amount) {
        EconomyAccount account = getAccount(player, currency);
        if (account == null) return false;
        EconomyTransaction tx = account.decreaseBalance(amount);
        return tx.isSuccessful();
    }

    /**
     * 从玩家账户扣款（离线）
     */
    public static boolean deduct(MinecraftServer server, UUID playerUuid, EconomyCurrency currency, long amount) {
        EconomyAccount account = getAccount(server, playerUuid, currency);
        if (account == null) return false;
        EconomyTransaction tx = account.decreaseBalance(amount);
        return tx.isSuccessful();
    }

    /**
     * 向玩家账户存钱（离线）
     */
    public static boolean deposit(MinecraftServer server, UUID playerUuid, EconomyCurrency currency, long amount) {
        EconomyAccount account = getAccount(server, playerUuid, currency);
        if (account == null) return false;
        account.increaseBalance(amount);
        return true;
    }

    /**
     * 向玩家账户存钱（在线）
     */
    public static boolean deposit(ServerPlayerEntity player, EconomyCurrency currency, long amount) {
        EconomyAccount account = getAccount(player, currency);
        if (account == null) return false;
        account.increaseBalance(amount);
        return true;
    }

    /**
     * 获取余额
     */
    public static long getBalance(ServerPlayerEntity player, EconomyCurrency currency) {
        EconomyAccount account = getAccount(player, currency);
        if (account == null) return 0;
        return account.balance();
    }

    /**
     * 格式化金额显示
     */
    public static Text formatMoney(EconomyCurrency currency, long amount) {
        if (currency == null) return Text.literal(String.valueOf(amount));
        return currency.formatValueText(amount, true);
    }
}
