package io.momo.playerauction.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class AuctionConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AuctionConfig INSTANCE;
    private static Path CONFIG_PATH;

    // ===== 可配置项 =====
    private String currencyId = "savs-common-economy:dollar";      // 默认货币
    private long expiryTimeMs = 86400000L;                 // 默认24小时 (毫秒)
    private double taxRate = 0.0;                          // 税乘数 (0.0 = 无税)
    private int maxListingsPerPlayer = 10;                 // 每个玩家最多上架数
    private Set<String> bannedPlayers = new HashSet<>();   // 被禁玩家UUID
    private Set<String> bannedItems = new HashSet<>();     // 被禁物品ID

    public AuctionConfig() {}

    public static AuctionConfig getInstance() {
        return INSTANCE;
    }

    public static void load(MinecraftServer server) {
        CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("playerauction").resolve("config.json");
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            if (Files.exists(CONFIG_PATH)) {
                try (FileReader reader = new FileReader(CONFIG_PATH.toFile())) {
                    INSTANCE = GSON.fromJson(reader, AuctionConfig.class);
                }
            } else {
                INSTANCE = new AuctionConfig();
            }
        } catch (IOException e) {
            INSTANCE = new AuctionConfig();
            e.printStackTrace();
        }
        if (INSTANCE == null) {
            INSTANCE = new AuctionConfig();
        }
        INSTANCE.save();
    }

    public void save() {
        if (CONFIG_PATH == null) return;
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (FileWriter writer = new FileWriter(CONFIG_PATH.toFile())) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ===== Getter / Setter =====

    public String getCurrencyId() { return currencyId; }
    public void setCurrencyId(String currencyId) { this.currencyId = currencyId; }

    public long getExpiryTimeMs() { return expiryTimeMs; }
    public void setExpiryTimeMs(long expiryTimeMs) { this.expiryTimeMs = expiryTimeMs; }

    public double getTaxRate() { return taxRate; }
    public void setTaxRate(double taxRate) { this.taxRate = taxRate; }

    public int getMaxListingsPerPlayer() { return maxListingsPerPlayer; }
    public void setMaxListingsPerPlayer(int maxListingsPerPlayer) { this.maxListingsPerPlayer = maxListingsPerPlayer; }

    public Set<String> getBannedPlayers() { return bannedPlayers; }

    public boolean isPlayerBanned(String uuid) { return bannedPlayers.contains(uuid); }
    public boolean isPlayerBannedByUuid(java.util.UUID uuid) { return bannedPlayers.contains(uuid.toString()); }

    public boolean isItemBanned(String itemId) { return bannedItems.contains(itemId); }
    public Set<String> getBannedItems() { return bannedItems; }
}
