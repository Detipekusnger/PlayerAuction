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

public class AdminRemoveGui extends SimpleGui {

    private static final int ITEMS_PER_PAGE = 36;
    private static final int CONTENT_START = 9;

    private final ServerPlayerEntity admin;
    private int currentPage = 0;
    private List<AuctionItem> allItems;

    public AdminRemoveGui(ServerPlayerEntity admin) {
        super(ScreenHandlerType.GENERIC_9X6, admin, false);
        this.admin = admin;
        this.setTitle(Text.literal("§c管理员强制下架"));
        rebuildList();
        draw();
        this.open();
    }

    private void rebuildList() {
        allItems = AuctionManager.getInstance().getActiveAuctions();
    }

    private void draw() {
        for (int i = 0; i < 54; i++) {
            this.clearSlot(i);
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) allItems.size() / ITEMS_PER_PAGE));
        if (currentPage >= totalPages) currentPage = totalPages - 1;
        if (currentPage < 0) currentPage = 0;

        int startIndex = currentPage * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, allItems.size());

        // 顶栏
        GuiElementBuilder pageInfo = new GuiElementBuilder(Items.BOOK)
                .setName(Text.literal("§7第 §c" + (currentPage + 1) + " §7/ §c" + totalPages + " §7页"))
                .setLore(List.of(Text.literal("§7共 §c" + allItems.size() + " §7个拍卖品")));
        this.setSlot(4, pageInfo);

        GuiElementBuilder prevBtn = new GuiElementBuilder(Items.ARROW)
                .setName(Text.literal("§e◀ 上一页"));
        if (currentPage > 0) {
            prevBtn.setCallback((index, type, action) -> { currentPage--; draw(); });
        }
        this.setSlot(3, prevBtn);

        GuiElementBuilder nextBtn = new GuiElementBuilder(Items.ARROW)
                .setName(Text.literal("§e下一页 ▶"));
        if (currentPage < totalPages - 1) {
            nextBtn.setCallback((index, type, action) -> { currentPage++; draw(); });
        }
        this.setSlot(5, nextBtn);

        GuiElementBuilder closeBtn = new GuiElementBuilder(Items.BARRIER)
                .setName(Text.literal("§c✕ 关闭"))
                .setLore(List.of(Text.literal("§7点击关闭")))
                .setCallback((index, type, action) -> this.close());
        this.setSlot(8, closeBtn);

        // 分隔
        ItemStack redPane = Items.RED_STAINED_GLASS_PANE.getDefaultStack();
        for (int i = 0; i < 9; i++) {
            if (getSlot(i) == null) {
                GuiElementBuilder pane = GuiElementBuilder.from(redPane);
                pane.setName(Text.literal(""));
                this.setSlot(i, pane);
            }
        }

        // 物品列表
        int slotIndex = CONTENT_START;
        for (int i = startIndex; i < endIndex; i++) {
            AuctionItem item = allItems.get(i);
            ItemStack displayStack = item.getItemStack();
            int displayCount = Math.min(item.getQuantity(), displayStack.getMaxCount());

            GuiElementBuilder builder = new GuiElementBuilder(displayStack.getItem())
                    .setCount(displayCount)
                    .setName(displayStack.getName().copy().formatted(Formatting.WHITE))
                    .setLore(item.generateLore())
                    .addLoreLine(Text.literal(""))
                    .addLoreLine(Text.literal("§c⚠ 点击强制下架此物品"))
                    .setCallback((index, type, action) -> {
                        String result = AuctionManager.getInstance().adminRemove(admin, item);
                        admin.sendMessage(Text.literal(result), false);
                        rebuildList();
                        draw();
                    });
            this.setSlot(slotIndex, builder);
            slotIndex++;
        }

        // 填充
        ItemStack blackPane = Items.BLACK_STAINED_GLASS_PANE.getDefaultStack();
        for (int i = slotIndex; i < CONTENT_START + ITEMS_PER_PAGE; i++) {
            GuiElementBuilder pane = GuiElementBuilder.from(blackPane);
            pane.setName(Text.literal(""));
            this.setSlot(i, pane);
        }

        for (int i = 45; i < 54; i++) {
            GuiElementBuilder pane = GuiElementBuilder.from(redPane);
            pane.setName(Text.literal(""));
            this.setSlot(i, pane);
        }
    }
}
