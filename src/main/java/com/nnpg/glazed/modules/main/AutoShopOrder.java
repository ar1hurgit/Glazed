package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoShopOrder extends Module {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    private enum ItemType {
        BLAZE_ROD("Blaze Rod", "blaze rod", Items.BLAZE_ROD),
        TOTEM("Totem of Undying", "totem", Items.TOTEM_OF_UNDYING),
        SHULKER_SHELL("Shulker Shell", "shulker shell", Items.SHULKER_SHELL),
        SHULKER_BOX("Shulker Box", "shulker", Items.SHULKER_BOX);

        private final String displayName;
        private final String orderSearch;
        private final Item item;

        ItemType(String displayName, String orderSearch, Item item) {
            this.displayName = displayName;
            this.orderSearch = orderSearch;
            this.item = item;
        }
    }

    private enum Stage {
        NONE, SHOP, SHOP_CATEGORY, SHOP_ITEM, SHOP_GLASS_PANE, SHOP_BUY_ONE,
        SHOP_CHECK_FULL, SHOP_EXIT, WAIT, ORDERS, ORDERS_SELECT,
        ORDERS_CONFIRM, ORDERS_FINAL_EXIT, CYCLE_PAUSE, TARGET_ORDERS
    }

    private Stage stage = Stage.NONE;
    private long stageStart = 0;
    private static final long WAIT_TIME_MS = 50;
    private int itemMoveIndex = 0;
    private long lastItemMoveTime = 0;
    private int finalExitCount = 0;
    private long finalExitStart = 0;

    // Player targeting
    private String targetPlayer = "";
    private boolean isTargetingActive = false;

    // Settings

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPrices = settings.createGroup("Prices");
    private final SettingGroup sgTargeting = settings.createGroup("Player Targeting");

    // Item type selection
    private final Setting<ItemType> selectedItemType = sgGeneral.add(new EnumSetting.Builder<ItemType>()
        .name("item-type")
        .description("Select which item type to auto-order.")
        .defaultValue(ItemType.BLAZE_ROD)
        .build()
    );

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Show detailed notifications.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> speedMode = sgGeneral.add(new BoolSetting.Builder()
        .name("speed-mode")
        .description("Maximum speed mode - removes most delays (may be unstable).")
        .defaultValue(true)
        .build()
    );

    // Separate price settings for each item type
    private final Setting<String> blazeRodPrice = sgPrices.add(new StringSetting.Builder()
        .name("blaze-rod-price")
        .description("Minimum price for Blaze Rods.")
        .defaultValue("150")
        .visible(() -> selectedItemType.get() == ItemType.BLAZE_ROD)
        .build()
    );

    private final Setting<String> totemPrice = sgPrices.add(new StringSetting.Builder()
        .name("totem-price")
        .description("Minimum price for Totems of Undying.")
        .defaultValue("850")
        .visible(() -> selectedItemType.get() == ItemType.TOTEM)
        .build()
    );

    private final Setting<String> shulkerShellPrice = sgPrices.add(new StringSetting.Builder()
        .name("shulker-shell-price")
        .description("Minimum price for Shulker Shells.")
        .defaultValue("350")
        .visible(() -> selectedItemType.get() == ItemType.SHULKER_SHELL)
        .build()
    );

    private final Setting<String> shulkerBoxPrice = sgPrices.add(new StringSetting.Builder()
        .name("shulker-box-price")
        .description("Minimum price for Shulker Boxes.")
        .defaultValue("500")
        .visible(() -> selectedItemType.get() == ItemType.SHULKER_BOX)
        .build()
    );

    // Player targeting settings
    private final Setting<Boolean> enableTargeting = sgTargeting.add(new BoolSetting.Builder()
        .name("enable-targeting")
        .description("Enable targeting a specific player (ignores minimum price).")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> targetPlayerName = sgTargeting.add(new StringSetting.Builder()
        .name("target-player")
        .description("Specific player name to target for orders.")
        .defaultValue("")
        .visible(() -> enableTargeting.get())
        .build()
    );

    private final Setting<Boolean> targetOnlyMode = sgTargeting.add(new BoolSetting.Builder()
        .name("target-only-mode")
        .description("Only look for orders from the targeted player, ignore all others.")
        .defaultValue(false)
        .visible(() -> enableTargeting.get())
        .build()
    );



    private final Setting<List<String>> blacklistedPlayers = sgTargeting.add(new StringListSetting.Builder()
        .name("blacklisted-players")
        .description("Players whose orders will be ignored.")
        .defaultValue(List.of())
        .build()
    );

    // Admin detection settings
    private final SettingGroup sgAdmin = settings.createGroup("Admin Protection");

    private final Setting<Boolean> stopOnAdmin = sgAdmin.add(new BoolSetting.Builder()
            .name("stop-on-admin")
            .description("Enable admin detection.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> stopModuleOnAdmin = sgAdmin.add(new BoolSetting.Builder()
            .name("disable-module")
            .description("Disable module when admin detected.")
            .defaultValue(true)
            .visible(() -> stopOnAdmin.get())
            .build()
    );

    private final Setting<Boolean> disconnectOnAdmin = sgAdmin.add(new BoolSetting.Builder()
            .name("disconnect")
            .description("Disconnect when admin detected.")
            .defaultValue(false)
            .visible(() -> stopOnAdmin.get())
            .build()
    );

    private final Setting<Boolean> loudSoundOnAdmin = sgAdmin.add(new BoolSetting.Builder()
            .name("loud-sound")
            .description("Play loud alert sound.")
            .defaultValue(true)
            .visible(() -> stopOnAdmin.get())
            .build()
    );

    private final Setting<Integer> adminDetectionRange = sgAdmin.add(new IntSetting.Builder()
            .name("admin-detection-range")
            .description("Detection range.")
            .defaultValue(50)
            .min(10)
            .max(150)
            .sliderMax(150)
            .visible(() -> stopOnAdmin.get())
            .build()
    );

    private final Setting<Boolean> testAdminSound = sgAdmin.add(new BoolSetting.Builder()
            .name("test-loud-sound")
            .description("Test the loud admin sound.")
            .defaultValue(false)
            .visible(() -> stopOnAdmin.get())
            .onChanged(value -> {
                if (value) playLoudAdminSound();
            })
            .build()
    );

    public AutoShopOrder() {
        super(GlazedAddon.CATEGORY, "auto-shop-order", "Unified auto-order module for multiple item types.");
    }

    @Override
    public void onActivate() {
        String minPrice = getMinPriceForCurrentType();
        double parsedPrice = parsePrice(minPrice);
        if (parsedPrice == -1.0 && !enableTargeting.get()) {
            if (notifications.get()) ChatUtils.error("Invalid minimum price format!");
            toggle();
            return;
        }

        updateTargetPlayer();
        stage = Stage.SHOP;
        stageStart = System.currentTimeMillis();
        itemMoveIndex = 0;
        lastItemMoveTime = 0;
        finalExitCount = 0;

        if (notifications.get()) {
            String modeInfo = isTargetingActive ? String.format(" | Targeting: %s", targetPlayer) : "";
            info("🚀 AutoShopOrder activated! Item: %s | Min Price: %s%s",
                selectedItemType.get().displayName, minPrice, modeInfo);
        }
    }

    @Override
    public void onDeactivate() {
        stage = Stage.NONE;
    }

    private String getMinPriceForCurrentType() {
        return switch (selectedItemType.get()) {
            case BLAZE_ROD -> blazeRodPrice.get();
            case TOTEM -> totemPrice.get();
            case SHULKER_SHELL -> shulkerShellPrice.get();
            case SHULKER_BOX -> shulkerBoxPrice.get();
        };
    }

    private void updateTargetPlayer() {
        targetPlayer = "";
        isTargetingActive = false;
        if (enableTargeting.get() && !targetPlayerName.get().trim().isEmpty()) {
            targetPlayer = targetPlayerName.get().trim();
            isTargetingActive = true;
            if (notifications.get()) info("🎯 Targeting enabled for player: %s", targetPlayer);
        }
    }
    private void playLoudAdminSound() {
        mc.execute(() -> {
            for (int i = 0; i < 5; i++) {
                mc.player.playSound(SoundEvents.BLOCK_ANVIL_DESTROY, 10000f, 1);
            }
        });
    }
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        
        // Check for nearby admins and stop if detected
        if (stopOnAdmin.get() && isAdminNearby()) {
            if (notifications.get()) ChatUtils.warning("⚠️ Admin detected nearby!");

            if (loudSoundOnAdmin.get()) {
                playLoudAdminSound();
            }

            if (disconnectOnAdmin.get()) {
                mc.getNetworkHandler().getConnection().disconnect(Text.literal("Admin detected"));
                return;
            }

            if (stopModuleOnAdmin.get()) {
                if (mc.currentScreen != null) mc.player.closeHandledScreen();
                toggle();
            }

            return;
        }
        
        long now = System.currentTimeMillis();

        switch (stage) {
            case TARGET_ORDERS -> {
                ChatUtils.sendPlayerMsg("/orders " + targetPlayer);
                stage = Stage.ORDERS;
                stageStart = now;
                if (notifications.get()) info("🔍 Checking orders for: %s", targetPlayer);
            }
            case SHOP -> {
                ChatUtils.sendPlayerMsg("/shop");
                stage = Stage.SHOP_CATEGORY;
                stageStart = now;
            }
            case SHOP_CATEGORY -> handleShopCategory(now);
            case SHOP_ITEM -> handleShopItem(now);
            case SHOP_GLASS_PANE -> handleShopGlassPane(now);
            case SHOP_BUY_ONE -> handleShopBuyOne(now);
            case SHOP_CHECK_FULL -> {
                mc.player.closeHandledScreen();
                stage = Stage.SHOP_EXIT;
                stageStart = now;
            }
            case SHOP_EXIT -> handleShopExit(now);
            case WAIT -> handleWait(now);
            case ORDERS -> handleOrders(now);
            case ORDERS_SELECT -> handleOrdersSelect(now);
            case ORDERS_CONFIRM -> handleOrdersConfirm(now);
            case ORDERS_FINAL_EXIT -> handleOrdersFinalExit(now);
            case CYCLE_PAUSE -> handleCyclePause(now);
            case NONE -> {}
        }
    }

    private void handleShopCategory(long now) {
        if (mc.currentScreen instanceof GenericContainerScreen screen) {
            ScreenHandler handler = screen.getScreenHandler();
            for (Slot slot : handler.slots) {
                ItemStack stack = slot.getStack();
                if (!stack.isEmpty() && isCategoryItem(stack)) {
                    mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
                    stage = Stage.SHOP_ITEM;
                    stageStart = now;
                    return;
                }
            }
            if (now - stageStart > (speedMode.get() ? 1000 : 3000)) {
                mc.player.closeHandledScreen();
                stage = Stage.SHOP;
                stageStart = now;
            }
        }
    }

    private void handleShopItem(long now) {
        if (mc.currentScreen instanceof GenericContainerScreen screen) {
            ScreenHandler handler = screen.getScreenHandler();
            for (Slot slot : handler.slots) {
                ItemStack stack = slot.getStack();
                if (!stack.isEmpty() && isTargetItem(stack) && slot.inventory != mc.player.getInventory()) {
                    mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
                    stage = Stage.SHOP_GLASS_PANE;
                    stageStart = now;
                    return;
                }
            }
            if (now - stageStart > (speedMode.get() ? 300 : 1000)) {
                mc.player.closeHandledScreen();
                stage = Stage.SHOP;
                stageStart = now;
            }
        }
    }

    private void handleShopGlassPane(long now) {
        if (mc.currentScreen instanceof GenericContainerScreen screen) {
            ScreenHandler handler = screen.getScreenHandler();
            for (Slot slot : handler.slots) {
                ItemStack stack = slot.getStack();
                if (!stack.isEmpty() && isGlassPane(stack) && stack.getCount() == 64) {
                    mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
                    stage = Stage.SHOP_BUY_ONE;
                    stageStart = now;
                    return;
                }
            }
            if (now - stageStart > (speedMode.get() ? 300 : 1000)) {
                mc.player.closeHandledScreen();
                stage = Stage.SHOP;
                stageStart = now;
            }
        }
    }

    private void handleShopBuyOne(long now) {
        long waitDelay = speedMode.get() ? 500 : 1000;
        if (now - stageStart >= waitDelay) {
            if (mc.currentScreen instanceof GenericContainerScreen screen) {
                ScreenHandler handler = screen.getScreenHandler();
                for (Slot slot : handler.slots) {
                    ItemStack stack = slot.getStack();
                    if (!stack.isEmpty() && isGreenGlass(stack) && stack.getCount() == 1) {
                        int maxClicks = speedMode.get() ? 50 : 30;
                        for (int i = 0; i < maxClicks; i++) {
                            mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
                            if (isInventoryFull()) break;
                        }
                        stage = Stage.SHOP_CHECK_FULL;
                        stageStart = now;
                        return;
                    }
                }
                if (now - stageStart > (speedMode.get() ? 2000 : 3000)) {
                    stage = Stage.SHOP_GLASS_PANE;
                    stageStart = now;
                }
            }
        }
    }

    private void handleShopExit(long now) {
        if (mc.currentScreen == null) {
            stage = Stage.WAIT;
            stageStart = now;
        }
        if (now - stageStart > (speedMode.get() ? 1000 : 5000)) {
            mc.player.closeHandledScreen();
            stage = Stage.SHOP;
            stageStart = now;
        }
    }

    private void handleWait(long now) {
        long waitTime = speedMode.get() ? 25 : WAIT_TIME_MS;
        if (now - stageStart >= waitTime) {
            if (isTargetingActive && !targetPlayer.isEmpty()) {
                stage = Stage.TARGET_ORDERS;
            } else {
                ChatUtils.sendPlayerMsg("/orders " + selectedItemType.get().orderSearch);
                stage = Stage.ORDERS;
            }
            stageStart = now;
        }
    }

    private void handleOrders(long now) {
        if (mc.currentScreen instanceof GenericContainerScreen screen) {
            ScreenHandler handler = screen.getScreenHandler();

            if (speedMode.get() && now - stageStart < 200) return;

            for (Slot slot : handler.slots) {
                ItemStack stack = slot.getStack();
                if (!stack.isEmpty() && isTargetItem(stack)) {
                    String orderPlayer = getOrderPlayerName(stack);
                    if (isBlacklisted(orderPlayer)) continue;

                    boolean shouldTakeOrder = false;
                    boolean isTargetedOrder = isTargetingActive && orderPlayer != null &&
                        orderPlayer.equalsIgnoreCase(targetPlayer);

                    if (isTargetedOrder) {
                        shouldTakeOrder = true;
                        if (notifications.get()) {
                            double orderPrice = getOrderPrice(stack);
                            info("🎯 Found TARGET order from %s: %s", orderPlayer,
                                orderPrice > 0 ? formatPrice(orderPrice) : "Unknown price");
                        }
                    } else if (!targetOnlyMode.get()) {
                        double orderPrice = getOrderPrice(stack);
                        double minPriceValue = parsePrice(getMinPriceForCurrentType());

                        // Skip orders with unreasonably high prices (likely custom orders)
                        if (orderPrice > 1500 && selectedItemType.get() != ItemType.TOTEM) continue;

                        if (orderPrice >= minPriceValue) {
                            shouldTakeOrder = true;
                            if (notifications.get()) info("✅ Found order: %s", formatPrice(orderPrice));
                        }
                    }

                    if (shouldTakeOrder) {
                        mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
                        stage = Stage.ORDERS_SELECT;
                        stageStart = now;
                        itemMoveIndex = 0;
                        lastItemMoveTime = 0;
                        return;
                    }
                }
            }

            if (now - stageStart > (speedMode.get() ? 3000 : 5000)) {
                mc.player.closeHandledScreen();
                stage = Stage.SHOP;
                stageStart = now;
            }
        }
    }

    private void handleOrdersSelect(long now) {
        if (mc.currentScreen instanceof GenericContainerScreen screen) {
            ScreenHandler handler = screen.getScreenHandler();

            if (itemMoveIndex >= 36) {
                mc.player.closeHandledScreen();
                stage = Stage.ORDERS_CONFIRM;
                stageStart = now;
                itemMoveIndex = 0;
                return;
            }

            long moveDelay = speedMode.get() ? 10 : 100;
            if (now - lastItemMoveTime >= moveDelay) {
                int batchSize = speedMode.get() ? 3 : 1;

                for (int batch = 0; batch < batchSize && itemMoveIndex < 36; batch++) {
                    ItemStack stack = mc.player.getInventory().getStack(itemMoveIndex);
                    if (isTargetItem(stack)) {
                        int playerSlotId = -1;
                        for (Slot slot : handler.slots) {
                            if (slot.inventory == mc.player.getInventory() && slot.getIndex() == itemMoveIndex) {
                                playerSlotId = slot.id;
                                break;
                            }
                        }

                        if (playerSlotId != -1) {
                            mc.interactionManager.clickSlot(handler.syncId, playerSlotId, 0, SlotActionType.QUICK_MOVE, mc.player);
                        }
                    }
                    itemMoveIndex++;
                }
                lastItemMoveTime = now;
            }
        }
    }

    private void handleOrdersConfirm(long now) {
        if (mc.currentScreen instanceof GenericContainerScreen screen) {
            ScreenHandler handler = screen.getScreenHandler();
            for (Slot slot : handler.slots) {
                ItemStack stack = slot.getStack();
                if (!stack.isEmpty() && isGreenGlass(stack)) {
                    for (int i = 0; i < (speedMode.get() ? 15 : 5); i++) {
                        mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
                    }
                    stage = Stage.ORDERS_FINAL_EXIT;
                    stageStart = now;
                    finalExitCount = 0;
                    finalExitStart = now;
                    if (notifications.get()) info("✅ Order completed!");
                    return;
                }
            }
            if (now - stageStart > (speedMode.get() ? 2000 : 5000)) {
                mc.player.closeHandledScreen();
                stage = Stage.SHOP;
                stageStart = now;
            }
        }
    }

    private void handleOrdersFinalExit(long now) {
        long exitDelay = speedMode.get() ? 50 : 200;

        if (finalExitCount < 2) {
            if (System.currentTimeMillis() - finalExitStart >= exitDelay) {
                mc.player.closeHandledScreen();
                finalExitCount++;
                finalExitStart = System.currentTimeMillis();
            }
        } else {
            finalExitCount = 0;
            stage = Stage.CYCLE_PAUSE;
            stageStart = System.currentTimeMillis();
        }
    }

    private void handleCyclePause(long now) {
        long cycleWait = speedMode.get() ? 25 : WAIT_TIME_MS;
        if (now - stageStart >= cycleWait) {
            updateTargetPlayer();
            stage = Stage.SHOP;
            stageStart = now;
        }
    }

    // Helper methods
    private boolean isCategoryItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        String name = stack.getName().getString().toLowerCase(Locale.ROOT);
        return switch (selectedItemType.get()) {
            case BLAZE_ROD -> stack.getItem() == Items.NETHERRACK || name.contains("nether");
            case TOTEM -> stack.getItem() == Items.TOTEM_OF_UNDYING;
            case SHULKER_SHELL, SHULKER_BOX -> stack.getItem() == Items.END_STONE || name.contains("end");
        };
    }

    private boolean isTargetItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return switch (selectedItemType.get()) {
            case BLAZE_ROD -> stack.getItem() == Items.BLAZE_ROD;
            case TOTEM -> stack.getItem() == Items.TOTEM_OF_UNDYING;
            case SHULKER_SHELL -> stack.getItem() == Items.SHULKER_SHELL;
            case SHULKER_BOX -> stack.getItem() == Items.SHULKER_BOX ||
                stack.getItem().getName().getString().toLowerCase(Locale.ROOT).contains("shulker box");
        };
    }

    private boolean isGlassPane(ItemStack stack) {
        String itemName = stack.getItem().getName().getString().toLowerCase();
        return itemName.contains("glass") && itemName.contains("pane");
    }

    private boolean isGreenGlass(ItemStack stack) {
        return stack.getItem() == Items.LIME_STAINED_GLASS_PANE || stack.getItem() == Items.GREEN_STAINED_GLASS_PANE;
    }

    private boolean isInventoryFull() {
        for (int i = 9; i <= 35; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) return false;
        }
        return true;
    }

    private boolean isBlacklisted(String playerName) {
        if (playerName == null || blacklistedPlayers.get().isEmpty()) return false;
        return blacklistedPlayers.get().stream().anyMatch(p -> p.equalsIgnoreCase(playerName));
    }

    private String getOrderPlayerName(ItemStack stack) {
        if (stack.isEmpty()) return null;
        Item.TooltipContext ctx = Item.TooltipContext.create(mc.world);
        List<Text> tooltip = stack.getTooltip(ctx, mc.player, TooltipType.BASIC);
        Pattern[] patterns = {
            Pattern.compile("(?i)player\\s*:\\s*([a-zA-Z0-9_]+)"),
            Pattern.compile("(?i)from\\s*:\\s*([a-zA-Z0-9_]+)"),
            Pattern.compile("(?i)by\\s*:\\s*([a-zA-Z0-9_]+)"),
            Pattern.compile("(?i)seller\\s*:\\s*([a-zA-Z0-9_]+)"),
            Pattern.compile("(?i)owner\\s*:\\s*([a-zA-Z0-9_]+)")
        };
        for (Text line : tooltip) {
            String text = line.getString();
            for (Pattern p : patterns) {
                Matcher m = p.matcher(text);
                if (m.find()) {
                    String name = m.group(1);
                    if (name.length() >= 3 && name.length() <= 16) return name;
                }
            }
        }
        return null;
    }

    private double getOrderPrice(ItemStack stack) {
        if (stack.isEmpty()) return -1.0;
        Item.TooltipContext ctx = Item.TooltipContext.create(mc.world);
        List<Text> tooltip = stack.getTooltip(ctx, mc.player, TooltipType.BASIC);
        return parseTooltipPrice(tooltip);
    }

    private double parseTooltipPrice(List<Text> tooltip) {
        if (tooltip == null || tooltip.isEmpty()) return -1.0;

        Pattern[] pricePatterns = {
            Pattern.compile("\\$([\\d,]+(?:\\.[\\d]+)?)([kmb])?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)price\\s*:\\s*([\\d,]+(?:\\.[\\d]+)?)([kmb])?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)pay\\s*:\\s*([\\d,]+(?:\\.[\\d]+)?)([kmb])?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)reward\\s*:\\s*([\\d,]+(?:\\.[\\d]+)?)([kmb])?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("([\\d,]+(?:\\.[\\d]+)?)([kmb])?\\s*coins?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b([\\d,]+(?:\\.[\\d]+)?)([kmb])\\b", Pattern.CASE_INSENSITIVE)
        };

        for (Text line : tooltip) {
            String text = line.getString();
            for (Pattern pattern : pricePatterns) {
                Matcher matcher = pattern.matcher(text);
                if (matcher.find()) {
                    String numberStr = matcher.group(1).replace(",", "");
                    String suffix = matcher.groupCount() >= 2 && matcher.group(2) != null ?
                        matcher.group(2).toLowerCase() : "";

                    try {
                        double basePrice = Double.parseDouble(numberStr);
                        double multiplier = switch (suffix) {
                            case "k" -> 1_000.0;
                            case "m" -> 1_000_000.0;
                            case "b" -> 1_000_000_000.0;
                            default -> 1.0;
                        };
                        return basePrice * multiplier;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return -1.0;
    }

    private double parsePrice(String priceStr) {
        if (priceStr == null || priceStr.isEmpty()) return -1.0;
        String cleaned = priceStr.trim().toLowerCase().replace(",", "");
        double multiplier = 1.0;

        if (cleaned.endsWith("b")) {
            multiplier = 1_000_000_000.0;
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        } else if (cleaned.endsWith("m")) {
            multiplier = 1_000_000.0;
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        } else if (cleaned.endsWith("k")) {
            multiplier = 1_000.0;
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }

        try {
            return Double.parseDouble(cleaned) * multiplier;
        } catch (NumberFormatException e) {
            return -1.0;
        }
    }

    private String formatPrice(double price) {
        if (price >= 1_000_000_000) return String.format("$%.1fB", price / 1_000_000_000.0);
        if (price >= 1_000_000) return String.format("$%.1fM", price / 1_000_000.0);
        if (price >= 1_000) return String.format("$%.1fK", price / 1_000.0);
        return String.format("$%.0f", price);
    }

    public void info(String message, Object... args) {
        if (notifications.get()) ChatUtils.info(String.format(message, args));
    }

    private boolean isAdminNearby() {
        AdminList adminList = Modules.get().get(AdminList.class);
        if (adminList == null || !adminList.isActive()) return false;

        double range = adminDetectionRange.get();
        double rangeSq = range * range;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (mc.player.squaredDistanceTo(player) <= rangeSq) {
                if (adminList.isAdmin(player.getName().getString())) {
                    if (notifications.get()) {
                        info("🚨 Admin detected: %s", player.getName().getString());
                    }
                    return true;
                }
            }
        }
        return false;
    }
}
