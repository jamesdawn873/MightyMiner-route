package com.jelly.mightyminerv2.feature.impl;

import com.jelly.mightyminerv2.config.MightyMinerConfig;
import com.jelly.mightyminerv2.failsafe.AbstractFailsafe.Failsafe;
import com.jelly.mightyminerv2.feature.AbstractFeature;
import com.jelly.mightyminerv2.feature.impl.BlockMiner.BlockMiner;
import com.jelly.mightyminerv2.util.InventoryUtil;
import com.jelly.mightyminerv2.util.InventoryUtil.ClickMode;
import com.jelly.mightyminerv2.util.InventoryUtil.ClickType;
import kotlin.Pair;
import lombok.Getter;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.inventory.ContainerChest;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// A separate class for inventory-related tasks that aren't significant enough to warrant their own feature class
public class AutoInventory extends AbstractFeature {

    private static AutoInventory instance;
    private Task mainTask = Task.NONE;
    //<editor-fold desc="Get Mining Speed And Mining Speed Boost (Gemstone Later)">
    @Getter
    private int[] speedBoostValues = new int[2]; // [Mining Speed, Mining Speed Boost]
    private SB sbState = SB.STARTING;
    @Getter
    private SBError sbError = SBError.NONE;
    //<editor-fold desc="Move Specific Items To Hotbar">
    private MoveState moveState = MoveState.STARTING;
    private MoveError moveError = MoveError.NONE;
    private final Queue<String> elementsToSwap = new LinkedList<>();
    private final Queue<Integer> availableSlots = new LinkedList<>();
    public AutoInventory() {
        this.failsafesToIgnore = new ArrayList<>(Collections.singletonList(Failsafe.ITEM_CHANGE));
    }

    public static AutoInventory getInstance() {
        if (instance == null) {
            instance = new AutoInventory();
        }
        return instance;
    }

    @Override
    public String getName() {
        return "AutoInventory";
    }

    @Override
    public void resetStatesAfterStop() {
        this.mainTask = Task.NONE;
        this.sbState = SB.STARTING;
        this.moveState = MoveState.STARTING;
    }

    @SubscribeEvent
    protected void onTick(ClientTickEvent event) {
        if (!this.enabled) {
            return;
        }

        switch (this.mainTask) {
            case NONE:
                this.stop();
                break;
            case GET_SPEED_BOOST:
                this.handleGetSpeedBoost();
                break;
            case MOVE_ITEMS_TO_HOTBAR:
                this.handleMoveItems();
                break;
        }
    }

    public void retrieveSpeedBoost() {
        this.mainTask = Task.GET_SPEED_BOOST;
        this.sbError = SBError.NONE;
        this.speedBoostValues = new int[2];

        this.enabled = true;
        this.start();
    }

    public boolean sbSucceeded() {
        return !this.enabled && this.sbError == SBError.NONE;
    }

    private void swapSbState(final SB state, final int time) {
        this.sbState = state;
        this.timer.schedule(time);
    }
    //</editor-fold>

    private void handleGetSpeedBoost() {
        switch (this.sbState) {
            case STARTING:
                this.swapSbState(SB.OPEN_MENU, MightyMinerConfig.getRandomGuiWaitDelay());
                break;
            case OPEN_MENU:
                mc.thePlayer.sendChatMessage("/sbmenu");
                this.swapSbState(SB.GET_SPEED, 2000);
                break;
            case GET_SPEED:
                if (this.hasTimerEnded()) {
                    this.stop();
                    this.sbError = SBError.CANNOT_GET_VALUE;
                    sendError("Could Not Get Speed In Time.");
                    break;
                }

                if (!(mc.currentScreen instanceof GuiChest) || !InventoryUtil.getInventoryName().equals("SkyBlock Menu") || !InventoryUtil.isInventoryLoaded()) {
                    break;
                }

                List<String> loreList = InventoryUtil.getItemLoreFromOpenContainer("Your SkyBlock Profile");
                for (String lore : loreList) {
                    if (!lore.contains("Mining Speed")) {
                        continue;
                    }

                    try {
                        String[] splitValues = lore.replace(",", "").split(" ");
                        this.speedBoostValues[0] = (int) Float.parseFloat(splitValues[splitValues.length - 1]);
                        this.swapSbState(SB.OPEN_HOTM_MENU, MightyMinerConfig.getRandomGuiWaitDelay());
                        return;
                    } catch (Exception e) {
                        e.printStackTrace();
                        log("Couldn't parse value properly.");
                    }
                }

                this.stop();
                this.sbError = SBError.CANNOT_GET_VALUE;
                sendError("Could not get mining speed from profile.");
                break;
            case OPEN_HOTM_MENU:
                if (this.isTimerRunning()) {
                    break;
                }

                if (InventoryUtil.getInventoryName().equals("SkyBlock Menu")) {
                    InventoryUtil.clickContainerSlot(
                            InventoryUtil.getSlotIdOfItemInContainer("Heart of the Mountain"),
                            ClickType.LEFT,
                            ClickMode.PICKUP
                    );
                } else {
                    log("Menu Name Is NOT SkyBlock Menu");
                    mc.thePlayer.sendChatMessage("/hotm"); // should never be a thing
                }

                this.swapSbState(SB.GET_SPEED_BOOST, 2000);
                break;
            case GET_SPEED_BOOST:
                if (this.hasTimerEnded()) {
                    this.stop();
                    this.sbError = SBError.CANNOT_OPEN_INV;
                    sendError("Could Not Open HOTM Inventory in Time.");
                    break;
                }
                if (!(mc.thePlayer.openContainer instanceof ContainerChest)
                        || !InventoryUtil.getInventoryName().equals("Heart of the Mountain")
                        || !InventoryUtil.isInventoryLoaded()) {
                    break;
                }

                final int speedBoostSlot = InventoryUtil.getSlotIdOfItemInContainer("Mining Speed Boost");
                final String speedBoostLore = String.join(" ", InventoryUtil.getLoreOfItemInContainer(speedBoostSlot));

                final Matcher matcher = Pattern.compile("\\+(\\d+)%").matcher(speedBoostLore);
                if (matcher.find()) {
                    this.speedBoostValues[1] = Integer.parseInt(matcher.group(1));
                    this.swapSbState(SB.END, 500);
                    break;
                }

                this.stop();
                this.sbError = SBError.CANNOT_GET_VALUE;
                sendError("Could Not Get Speed Boost Value. You may have scrolled up in your HOTM gui");
                break;
            case END:
                if (this.isTimerRunning()) {
                    break;
                }
                InventoryUtil.closeScreen();
                this.stop();
                break;
        }
    }

    public void moveItems(Collection<String> items) {
        if (items.isEmpty()) {
            return;
        }

        this.availableSlots.clear();
        this.elementsToSwap.clear();

        Pair<List<Integer>, List<String>> itemsToMove = InventoryUtil.getAvailableHotbarSlots(items);
        this.availableSlots.addAll(itemsToMove.getFirst());
        this.elementsToSwap.addAll(itemsToMove.getSecond());

        if (this.elementsToSwap.isEmpty()) {
            log("No Elements to move");
            return;
        }

        if (this.availableSlots.size() < this.elementsToSwap.size()) {
            sendError("Not enough slots to move items to. Disabling"); // should never happen
            this.moveError = MoveError.NOT_ENOUGH_HOTBAR_SPACE;
            return;
        }

        this.mainTask = Task.MOVE_ITEMS_TO_HOTBAR;
        this.moveError = MoveError.NONE;
        this.enabled = true;

        this.start();
        log("Started moving items into hotbar");
    }

    private void changeMoveState(MoveState to, int time) {
        this.moveState = to;
        this.timer.schedule(time);
    }

    public boolean moveFailed() {
        return !this.enabled && this.moveError != MoveError.NONE;
    }

    public MoveError getMoveError() {
        return this.moveError;
    }

    private void handleMoveItems() {
        if (this.timer.isScheduled() && !this.timer.passed()) {
            return;
        }

        switch (this.moveState) {
            case STARTING:
                InventoryUtil.openInventory();
                this.changeMoveState(MoveState.SWAP_SLOTS, MightyMinerConfig.getRandomGuiWaitDelay());

                log("Opened Inventory");
                break;
            case SWAP_SLOTS:
                if (!(this.elementsToSwap.isEmpty() || this.availableSlots.isEmpty())) {
                    InventoryUtil.swapSlots(InventoryUtil.getSlotIdOfItemInContainer(this.elementsToSwap.poll()), this.availableSlots.poll());
                    this.changeMoveState(MoveState.SWAP_SLOTS, MightyMinerConfig.getRandomGuiWaitDelay());
                } else {
                    this.changeMoveState(MoveState.FINISH, 0);
                }

                log("Swapped Item");
                break;
            case FINISH:
                InventoryUtil.closeScreen();

                if (!this.elementsToSwap.isEmpty()) {
                    this.moveError = MoveError.NOT_ENOUGH_HOTBAR_SPACE;
                }
                this.stop();
                log("Closed");
                break;
        }
    }

    enum Task {
        NONE, GET_SPEED_BOOST, MOVE_ITEMS_TO_HOTBAR
    }

    enum SB {
        STARTING, OPEN_MENU, GET_SPEED, OPEN_HOTM_MENU, GET_SPEED_BOOST, END
    }

    public enum SBError {
        NONE, CANNOT_OPEN_INV, CANNOT_GET_VALUE
    }

    enum MoveState {
        STARTING, SWAP_SLOTS, FINISH
    }

    public enum MoveError {
        NONE, NOT_ENOUGH_HOTBAR_SPACE
    }
    //</editor-fold>
}
