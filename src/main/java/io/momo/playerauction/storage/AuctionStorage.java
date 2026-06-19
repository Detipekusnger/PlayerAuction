package io.momo.playerauction.storage;

import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import io.momo.playerauction.auction.AuctionItem;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AuctionStorage {

    private static final String DB_URL_PREFIX = "jdbc:sqlite:";

    private static String getDbPath() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("playerauction").resolve("Auction.db").toString();
    }

    /**
     * 获取数据库连接
     */
    private static Connection getConnection() throws SQLException {
        String path = getDbPath();
        new File(path).getParentFile().mkdirs();
        return DriverManager.getConnection(DB_URL_PREFIX + path);
    }

    /**
     * 初始化数据库表
     */
    public static void init() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS auctions (" +
                "  id TEXT PRIMARY KEY," +
                "  uploader_uuid TEXT NOT NULL," +
                "  uploader_name TEXT NOT NULL," +
                "  type TEXT NOT NULL," +
                "  price INTEGER NOT NULL," +
                "  quantity INTEGER NOT NULL," +
                "  list_time INTEGER NOT NULL," +
                "  end_time INTEGER NOT NULL," +
                "  item_nbt TEXT NOT NULL" +
                ")"
            );
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 保存所有拍卖品到数据库
     */
    public static void save(MinecraftServer server, List<AuctionItem> items) {
        RegistryOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, server.getRegistryManager());

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            // 清空旧数据
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM auctions");
            }

            // 批量插入
            String sql = "INSERT INTO auctions (id, uploader_uuid, uploader_name, type, price, quantity, list_time, end_time, item_nbt) " +
                         "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (AuctionItem item : items) {
                    ps.setString(1, item.getId().toString());
                    ps.setString(2, item.getUploaderUuid().toString());
                    ps.setString(3, item.getUploaderName());
                    ps.setString(4, item.getType().name());
                    ps.setInt(5, item.getPrice());
                    ps.setInt(6, item.getQuantity());
                    ps.setLong(7, item.getListTime());
                    ps.setLong(8, item.getEndTime());

                    // 用 RegistryOps 编码物品 NBT
                    DataResult<NbtElement> result = ItemStack.CODEC.encodeStart(ops, item.getItemStack());
                    String nbtStr = result.result().map(NbtElement::toString).orElse("{}");
                    ps.setString(9, nbtStr);

                    ps.addBatch();
                }
                ps.executeBatch();
            }

            conn.commit();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 从数据库加载所有拍卖品
     */
    public static List<AuctionItem> load(MinecraftServer server) {
        RegistryOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, server.getRegistryManager());
        List<AuctionItem> items = new ArrayList<>();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM auctions ORDER BY list_time DESC")) {

            while (rs.next()) {
                UUID id = UUID.fromString(rs.getString("id"));
                UUID uploaderUuid = UUID.fromString(rs.getString("uploader_uuid"));
                String uploaderName = rs.getString("uploader_name");
                AuctionItem.AuctionType type = AuctionItem.AuctionType.valueOf(rs.getString("type"));
                int price = rs.getInt("price");
                int quantity = rs.getInt("quantity");
                long listTime = rs.getLong("list_time");
                long endTime = rs.getLong("end_time");

                // 反序列化物品
                ItemStack itemStack = ItemStack.EMPTY;
                String nbtStr = rs.getString("item_nbt");
                if (nbtStr != null && !nbtStr.isEmpty()) {
                    try {
                        NbtElement nbt = NbtHelper.fromNbtProviderString(nbtStr);
                        itemStack = ItemStack.CODEC.parse(ops, nbt).getOrThrow();
                    } catch (Exception e) {
                        // 尝试从简单格式重建
                        itemStack = tryFallbackDecode(nbtStr);
                    }
                }

                items.add(new AuctionItem(id, uploaderUuid, uploaderName, itemStack,
                        type, price, quantity, listTime, endTime));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return items;
    }

    /**
     * 保存离线玩家待送达物品
     */
    public static void savePendingDeliveries(MinecraftServer server,
                                              java.util.Map<UUID, java.util.List<ItemStack>> pending) {
        RegistryOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, server.getRegistryManager());

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM pending_deliveries");
            }

            String sql = "INSERT INTO pending_deliveries (player_uuid, item_nbt) VALUES (?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (var entry : pending.entrySet()) {
                    for (ItemStack stack : entry.getValue()) {
                        ps.setString(1, entry.getKey().toString());

                        // 用 RegistryOps 编码
                        DataResult<NbtElement> result = ItemStack.CODEC.encodeStart(ops, stack);
                        String nbtStr = result.result().map(NbtElement::toString).orElse(null);
                        
                        // 如果 CODEC 编码失败，用简单格式
                        if (nbtStr == null || nbtStr.equals("{}")) {
                            JsonObject fallback = new JsonObject();
                            fallback.addProperty("id", stack.getItem().toString());
                            fallback.addProperty("count", stack.getCount());
                            nbtStr = fallback.toString();
                        }
                        
                        ps.setString(2, nbtStr);

                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            }

            conn.commit();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 加载离线玩家待送达物品
     */
    public static java.util.Map<UUID, java.util.List<ItemStack>> loadPendingDeliveries(MinecraftServer server) {
        RegistryOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, server.getRegistryManager());
        java.util.Map<UUID, java.util.List<ItemStack>> result = new java.util.HashMap<>();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // 确保表存在
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS pending_deliveries (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  player_uuid TEXT NOT NULL," +
                "  item_nbt TEXT NOT NULL" +
                ")"
            );

            try (ResultSet rs = stmt.executeQuery("SELECT player_uuid, item_nbt FROM pending_deliveries")) {
                while (rs.next()) {
                    UUID playerUuid = UUID.fromString(rs.getString("player_uuid"));
                    String nbtStr = rs.getString("item_nbt");

                    ItemStack stack = ItemStack.EMPTY;
                    if (nbtStr != null && !nbtStr.isEmpty()) {
                        try {
                            NbtElement nbt = NbtHelper.fromNbtProviderString(nbtStr);
                            stack = ItemStack.CODEC.parse(ops, nbt).getOrThrow();
                        } catch (Exception e) {
                            stack = tryFallbackDecode(nbtStr);
                        }
                    }

                    if (!stack.isEmpty()) {
                        result.computeIfAbsent(playerUuid, k -> new java.util.ArrayList<>()).add(stack);
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return result;
    }

    /**
     * 回退解码：尝试从 NBT 中提取 id 和 count
     */
    private static ItemStack tryFallbackDecode(String nbtStr) {
        try {
            NbtElement nbt = NbtHelper.fromNbtProviderString(nbtStr);
            if (nbt instanceof net.minecraft.nbt.NbtCompound compound) {
                String idStr = compound.getString("id", "");
                if (!idStr.isEmpty()) {
                    int count = compound.getInt("count", 1);
                    var item = Registries.ITEM.get(Identifier.of(idStr));
                    if (item != null && item != net.minecraft.item.Items.AIR) {
                        return new ItemStack(item, count);
                    }
                }
            }
        } catch (Exception ignored) {}
        return ItemStack.EMPTY;
    }
}
