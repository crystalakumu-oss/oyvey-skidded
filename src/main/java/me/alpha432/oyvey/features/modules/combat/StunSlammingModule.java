package me.alpha432.oyvey.features.modules.combat;

import me.alpha432.oyvey.features.modules.Module;
import me.alpha432.oyvey.features.settings.Setting;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

public class StunSlammingModule extends Module {
    
    // SETTINGS
    private final Setting<Integer> stunChance = this.register(new Setting<>("StunChance", 100, 0, 100));
    private final Setting<Integer> minDelay = this.register(new Setting<>("MinDelay", 2, 0, 10));
    private final Setting<Integer> maxDelay = this.register(new Setting<>("MaxDelay", 5, 0, 10));
    private final Setting<Boolean> randomize = this.register(new Setting<>("Randomize", true));
    private final Setting<Double> range = this.register(new Setting<>("Range", 4.5, 1.0, 6.0));
    private final Setting<Boolean> rotate = this.register(new Setting<>("Rotate", true));
    private final Setting<Boolean> criticals = this.register(new Setting<>("Criticals", true));
    private final Setting<Boolean> autoSwitchBack = this.register(new Setting<>("AutoSwitchBack", true));
    private final Setting<Integer> minFallHeight = this.register(new Setting<>("MinFallHeight", 3, 1, 10));
    private final Setting<Boolean> inventory = this.register(new Setting<>("Inventory", false));
    private final Setting<Boolean> debug = this.register(new Setting<>("Debug", true));
    
    // INTERNE VARIABLEN
    private final Random random = new Random();
    private final SimpleTimer timer = new SimpleTimer();
    private PlayerEntity target = null;
    private int stage = 0;
    private int axeSlot = -1;
    private int maceSlot = -1;
    private int previousSlot = -1;
    private double fallStartY = 0;
    private boolean wasFalling = false;
    private int failedAttempts = 0;
    private int currentDelay = 0;
    
    public StunSlammingModule() {
        super("StunSlamming", "Breaks shield with axe then slams with mace while falling", Category.COMBAT);
    }
    
    @Override
    public void onEnable() {
        if (mc.player == null) {
            this.disable();
            return;
        }
        
        stage = 0;
        target = null;
        failedAttempts = 0;
        wasFalling = false;
        timer.reset();
        
        if (debug.getValue()) {
            this.sendMessage("§a[StunSlamming] §fModule enabled!");
        }
    }
    
    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) return;
        
        // Fall tracking - YARN MAPPINGS: isOnGround() -> isOnGround(), getVelocity() -> getVelocity(), getY() -> getY()
        if (!mc.player.isOnGround() && mc.player.getVelocity().y < 0) {
            if (!wasFalling) {
                wasFalling = true;
                fallStartY = mc.player.getY();
                if (debug.getValue()) {
                    this.sendMessage("§7[Debug] Started falling from Y: " + fallStartY);
                }
            }
        } else {
            wasFalling = false;
        }
        
        // Check if falling high enough
        boolean validFall = wasFalling && (fallStartY - mc.player.getY()) >= minFallHeight.getValue();
        
        if (validFall && stage == 0) {
            findTargetAndStart();
        }
        
        switch (stage) {
            case 1:
                if (timer.passedMs(currentDelay)) {
                    switchToAxe();
                }
                break;
            case 2:
                if (timer.passedMs(currentDelay)) {
                    performAxeAttack();
                }
                break;
            case 3:
                if (timer.passedMs(currentDelay)) {
                    switchToMace();
                }
                break;
            case 4:
                if (timer.passedMs(currentDelay)) {
                    performSlam();
                }
                break;
        }
    }
    
    private void findTargetAndStart() {
        if (random.nextInt(100) >= stunChance.getValue()) {
            failedAttempts++;
            return;
        }
        
        target = findBlockingTarget();
        if (target == null) return;
        
        axeSlot = findAxeSlot();
        if (axeSlot == -1 && inventory.getValue()) {
            axeSlot = findAxeInInventory();
        }
        
        maceSlot = findMaceSlot();
        if (maceSlot == -1 && inventory.getValue()) {
            maceSlot = findMaceInInventory();
        }
        
        if (axeSlot == -1 || maceSlot == -1) return;
        
        previousSlot = mc.player.getInventory().selectedSlot;
        
        if (debug.getValue()) {
            this.sendMessage("§a[StunSlamming] §fTarget: " + target.getName().getString());
        }
        
        stage = 1;
        currentDelay = getNextDelay();
        timer.reset();
    }
    
    private void switchToAxe() {
        if (axeSlot != -1) {
            if (axeSlot < 9) {
                mc.player.getInventory().selectedSlot = axeSlot;
            }
            stage = 2;
            currentDelay = getNextDelay();
            timer.reset();
        } else {
            reset();
        }
    }
    
    private void performAxeAttack() {
        if (target == null || mc.player == null || mc.interactionManager == null) return;
        
        double distance = mc.player.distanceTo(target);
        if (!target.isBlocking() || distance > range.getValue()) {
            reset();
            return;
        }
        
        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
        
        stage = 3;
        currentDelay = getNextDelay();
        timer.reset();
    }
    
    private void switchToMace() {
        if (maceSlot != -1) {
            if (maceSlot < 9) {
                mc.player.getInventory().selectedSlot = maceSlot;
            }
            stage = 4;
            currentDelay = getNextDelay();
            timer.reset();
        } else {
            reset();
        }
    }
    
    private void performSlam() {
        if (target == null || mc.player == null || mc.interactionManager == null) return;
        
        double distance = mc.player.distanceTo(target);
        if (distance > range.getValue()) {
            reset();
            return;
        }
        
        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
        
        // Criticals are handled automatically in 1.21.11 when falling
        
        if (autoSwitchBack.getValue() && previousSlot != -1 && previousSlot < 9) {
            mc.player.getInventory().selectedSlot = previousSlot;
        }
        
        failedAttempts = 0;
        reset();
    }
    
    private PlayerEntity findBlockingTarget() {
        if (mc.world == null || mc.player == null) return null;
        
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (!player.isBlocking()) continue;
            
            double distance = mc.player.distanceTo(player);
            if (distance <= range.getValue()) {
                return player;
            }
        }
        return null;
    }
    
    private int findAxeSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == Items.NETHERITE_AXE ||
                stack.getItem() == Items.DIAMOND_AXE ||
                stack.getItem() == Items.IRON_AXE) {
                return i;
            }
        }
        return -1;
    }
    
    private int findAxeInInventory() {
        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == Items.NETHERITE_AXE ||
                stack.getItem() == Items.DIAMOND_AXE ||
                stack.getItem() == Items.IRON_AXE) {
                return i;
            }
        }
        return -1;
    }
    
    private int findMaceSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.MACE) {
                return i;
            }
        }
        return -1;
    }
    
    private int findMaceInInventory() {
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.MACE) {
                return i;
            }
        }
        return -1;
    }
    
    private int getNextDelay() {
        if (!randomize.getValue()) {
            return (minDelay.getValue() + maxDelay.getValue()) / 2;
        }
        
        if (maxDelay.getValue() <= minDelay.getValue()) {
            return minDelay.getValue();
        }
        
        return minDelay.getValue() + random.nextInt(maxDelay.getValue() - minDelay.getValue() + 1);
    }
    
    public void reset() {
        stage = 0;
        target = null;
        timer.reset();
    }
    
    @Override
    public void onDisable() {
        reset();
        if (debug.getValue()) {
            this.sendMessage("§c[StunSlamming] Module disabled");
        }
    }
    
    @Override
    public String getDisplayInfo() {
        if (target != null) return target.getName().getString();
        if (failedAttempts > 0) return failedAttempts + " fails";
        return null;
    }
    
    // EINFACHER INTERNER TIMER
    private class SimpleTimer {
        private long time = System.currentTimeMillis();
        
        public void reset() {
            time = System.currentTimeMillis();
        }
        
        public boolean passedMs(long ms) {
            return System.currentTimeMillis() - time >= ms;
        }
    }
}
