package io.momo.playerauction.gui;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import io.momo.playerauction.auction.AuctionItem;
import io.momo.playerauction.auction.AuctionManager;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

public class AuctionMarketGui extends SimpleGui {

    private static final int ITEMS_PER_PAGE = 36;
    private static final int CONTENT_START = 9;

    private final ServerPlayerEntity player;
    private String searchQuery = "";
    private int currentPage = 0;
    private List<AuctionItem> filteredItems;
    private int totalPages = 0;

    public AuctionMarketGui(ServerPlayerEntity player) {
        super(ScreenHandlerType.GENERIC_9X6, player, false);
        this.player = player;
        this.setTitle(Text.literal("§8拍卖市场"));
        rebuildList();
        draw();
        this.open();
    }

    public AuctionMarketGui(ServerPlayerEntity player, String searchQuery) {
        super(ScreenHandlerType.GENERIC_9X6, player, false);
        this.player = player;
        this.searchQuery = searchQuery != null ? searchQuery : "";
        this.setTitle(Text.literal("§8拍卖市场" + (this.searchQuery.isEmpty() ? "" : " - 搜索: " + this.searchQuery)));
        rebuildList();
        draw();
        this.open();
    }

    private void rebuildList() {
        AuctionManager manager = AuctionManager.getInstance();
        if (searchQuery == null || searchQuery.trim().isEmpty()) {
            filteredItems = manager.getActiveAuctions();
        } else {
            filteredItems = manager.searchAuctions(searchQuery.trim());
        }
        totalPages = Math.max(1, (int) Math.ceil((double) filteredItems.size() / ITEMS_PER_PAGE));
        if (currentPage >= totalPages) {
            currentPage = totalPages - 1;
        }
        if (currentPage < 0) currentPage = 0;
    }

    private void draw() {
        for (int i = 0; i < 54; i++) {
            this.clearSlot(i);
        }

        // ===== 顶栏 =====
        GuiElementBuilder searchBtn = new GuiElementBuilder(Items.COMPASS)
                .setName(Text.literal("§b🔍 搜索"))
                .setLore(List.of(
                        Text.literal("§7点击打开搜索输入框"),
                        Text.literal("§8当前: " + (searchQuery.isEmpty() ? "无" : searchQuery))
                ))
                .setCallback((index, type, action) -> openSearchGui());
        this.setSlot(0, searchBtn);

        // 翻页信息
        GuiElementBuilder pageInfo = new GuiElementBuilder(Items.BOOK)
                .setName(Text.literal("§7第 §e" + (currentPage + 1) + " §7/ §e" + totalPages + " §7页"))
                .setLore(List.of(
                        Text.literal("§7共 §f" + filteredItems.size() + " §7个拍卖品"),
                        Text.literal("§7每页显示 §f" + ITEMS_PER_PAGE + " §7个"),
                        Text.literal(""),
                        Text.literal("§e左键§7: 买全部  §e右键§7: 买1个")
                ));
        this.setSlot(4, pageInfo);

        GuiElementBuilder prevBtn = new GuiElementBuilder(Items.ARROW)
                .setName(Text.literal("§e◀ 上一页"));
        if (currentPage > 0) {
            prevBtn.setCallback((index, type, action) -> { currentPage--; draw(); });
        } else {
            prevBtn.setName(Text.literal("§7◀ 上一页"));
        }
        this.setSlot(3, prevBtn);

        GuiElementBuilder nextBtn = new GuiElementBuilder(Items.ARROW)
                .setName(Text.literal("§e下一页 ▶"));
        if (currentPage < totalPages - 1) {
            nextBtn.setCallback((index, type, action) -> { currentPage++; draw(); });
        } else {
            nextBtn.setName(Text.literal("§7下一页 ▶"));
        }
        this.setSlot(5, nextBtn);

        GuiElementBuilder refreshBtn = new GuiElementBuilder(Items.ENDER_PEARL)
                .setName(Text.literal("§a🔄 刷新"))
                .setCallback((index, type, action) -> { rebuildList(); draw(); });
        this.setSlot(7, refreshBtn);

        GuiElementBuilder closeBtn = new GuiElementBuilder(Items.BARRIER)
                .setName(Text.literal("§c✕ 关闭"))
                .setCallback((index, type, action) -> this.close());
        this.setSlot(8, closeBtn);

        // 分隔栏
        ItemStack grayPane = Items.GRAY_STAINED_GLASS_PANE.getDefaultStack();
        for (int i = 0; i < 9; i++) {
            if (getSlot(i) == null) {
                GuiElementBuilder pane = GuiElementBuilder.from(grayPane);
                pane.setName(Text.literal(""));
                this.setSlot(i, pane);
            }
        }

        // ===== 物品展示 =====
        int startIndex = currentPage * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, filteredItems.size());

        int slotIndex = CONTENT_START;
        for (int i = startIndex; i < endIndex; i++) {
            AuctionItem auctionItem = filteredItems.get(i);
            ItemStack displayStack = auctionItem.getItemStack();
            int displayCount = Math.min(auctionItem.getQuantity(), displayStack.getMaxCount());

            // 生成Lore
            List<Text> lore = auctionItem.generateLore();
            lore.add(Text.literal(""));
            lore.add(Text.literal("§e左键: 买全部  §e右键: 买1个"));

            GuiElementBuilder builder = new GuiElementBuilder(displayStack.getItem())
                    .setCount(displayCount)
                    .setName(displayStack.getName().copy().formatted(Formatting.WHITE))
                    .setLore(lore)
                    .setCallback((index, type, action) -> handleItemClick(auctionItem, type, action));

            this.setSlot(slotIndex, builder);
            slotIndex++;
        }

        // 填充空位
        ItemStack blackPane = Items.BLACK_STAINED_GLASS_PANE.getDefaultStack();
        for (int i = slotIndex; i < CONTENT_START + ITEMS_PER_PAGE; i++) {
            GuiElementBuilder pane = GuiElementBuilder.from(blackPane);
            pane.setName(Text.literal(""));
            this.setSlot(i, pane);
        }

        for (int i = 45; i < 54; i++) {
            GuiElementBuilder pane = GuiElementBuilder.from(blackPane);
            pane.setName(Text.literal(""));
            this.setSlot(i, pane);
        }
    }

    /**
     * 处理物品点击：左键=买全部，右键=买1个
     */
    private void handleItemClick(AuctionItem item, ClickType type, SlotActionType action) {
        if (item.getUploaderUuid().equals(player.getUuid())) {
            player.sendMessage(Text.literal("§c你不能购买/出售自己的上架！"), false);
            return;
        }

        AuctionManager manager = AuctionManager.getInstance();
        int quantity = type.isLeft ? item.getQuantity() : 1;

        if (item.getType() == AuctionItem.AuctionType.SELL) {
            String result = manager.buyItem(player, item, quantity);
            player.sendMessage(Text.literal(result), false);
        } else {
            String result = manager.sellToBuyOrder(player, item, quantity);
            player.sendMessage(Text.literal(result), false);
        }

        rebuildList();
        draw();
    }

    private void openSearchGui() {
        AnvilInputGui inputGui = new AnvilInputGui(player, false) {
            @Override
            public void onInput(String input) {
                searchQuery = input.trim();
                currentPage = 0;
                setTitle(Text.literal("§8拍卖市场 - 搜索: " + searchQuery));
                rebuildList();
                draw();
            }

            @Override
            public void onClose() {
                rebuildList();
                draw();
                AuctionMarketGui.this.open();
            }
        };
        inputGui.setTitle(Text.literal("§8输入搜索关键词..."));
        if (!searchQuery.isEmpty()) {
            inputGui.setDefaultInputValue(searchQuery);
        }

        this.close();
        inputGui.open();
    }
}
