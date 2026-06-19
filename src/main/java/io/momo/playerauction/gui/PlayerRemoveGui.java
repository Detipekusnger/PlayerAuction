package io.momo.playerauction.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import io.momo.playerauction.auction.AuctionItem;
import io.momo.playerauction.auction.AuctionManager;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

public class PlayerRemoveGui extends SimpleGui {

    private static final int ITEMS_PER_PAGE = 36;
    private static final int CONTENT_START = 9;

    private final ServerPlayerEntity player;
    private int currentPage = 0;
    private List<AuctionItem> myItems;

    public PlayerRemoveGui(ServerPlayerEntity player) {
        super(ScreenHandlerType.GENERIC_9X6, player, false);
        this.player = player;
        this.setTitle(Text.literal("§6我的商店 — 点击下架"));
        rebuildList();
        draw();
        this.open();
    }

    private void rebuildList() {
        myItems = AuctionManager.getInstance().getPlayerListings(player.getUuid());
    }

    private void draw() {
        for (int i = 0; i < 54; i++) {
            this.clearSlot(i);
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) myItems.size() / ITEMS_PER_PAGE));
        if (currentPage >= totalPages) currentPage = totalPages - 1;
        if (currentPage < 0) currentPage = 0;

        int startIndex = currentPage * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, myItems.size());

        // ===== 顶栏 =====
        // 分页信息
        GuiElementBuilder pageInfo = new GuiElementBuilder(Items.BOOK)
                .setName(Text.literal("§7第 §e" + (currentPage + 1) + " §7/ §e" + totalPages + " §7页"))
                .setLore(List.of(
                        Text.literal("§7共 §f" + myItems.size() + " §7个上架物品"),
                        Text.literal("§7点击物品即可下架")
                ));
        this.setSlot(4, pageInfo);

        // 上一页
        GuiElementBuilder prevBtn = new GuiElementBuilder(Items.ARROW)
                .setName(Text.literal("§e◀ 上一页"));
        if (currentPage > 0) {
            prevBtn.setCallback((index, type, action) -> { currentPage--; draw(); });
        } else {
            prevBtn.setName(Text.literal("§7◀ 上一页"));
        }
        this.setSlot(3, prevBtn);

        // 下一页
        GuiElementBuilder nextBtn = new GuiElementBuilder(Items.ARROW)
                .setName(Text.literal("§e下一页 ▶"));
        if (currentPage < totalPages - 1) {
            nextBtn.setCallback((index, type, action) -> { currentPage++; draw(); });
        } else {
            nextBtn.setName(Text.literal("§7下一页 ▶"));
        }
        this.setSlot(5, nextBtn);

        // 关闭
        GuiElementBuilder closeBtn = new GuiElementBuilder(Items.BARRIER)
                .setName(Text.literal("§c✕ 关闭"))
                .setCallback((index, type, action) -> this.close());
        this.setSlot(8, closeBtn);

        // 分隔栏
        ItemStack greenPane = Items.GREEN_STAINED_GLASS_PANE.getDefaultStack();
        for (int i = 0; i < 9; i++) {
            if (getSlot(i) == null) {
                GuiElementBuilder pane = GuiElementBuilder.from(greenPane);
                pane.setName(Text.literal(""));
                this.setSlot(i, pane);
            }
        }

        // ===== 我的物品列表 =====
        int slotIndex = CONTENT_START;
        for (int i = startIndex; i < endIndex; i++) {
            AuctionItem item = myItems.get(i);
            ItemStack displayStack = item.getItemStack();
            int displayCount = Math.min(item.getQuantity(), displayStack.getMaxCount());

            String typeStr = item.getType() == AuctionItem.AuctionType.SELL ? "§c出售" : "§a收购";

            GuiElementBuilder builder = new GuiElementBuilder(displayStack.getItem())
                    .setCount(displayCount)
                    .setName(displayStack.getName().copy().formatted(Formatting.WHITE))
                    .setLore(item.generateMyLore())
                    .setCallback((index, type, action) -> {
                        // 右键或左键点击都下架
                        String result = AuctionManager.getInstance().cancelListing(player, item);
                        player.sendMessage(Text.literal(result), false);
                        rebuildList();
                        draw();
                    });
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

        // 底栏
        for (int i = 45; i < 54; i++) {
            GuiElementBuilder pane = GuiElementBuilder.from(blackPane);
            pane.setName(Text.literal(""));
            this.setSlot(i, pane);
        }
    }
}
