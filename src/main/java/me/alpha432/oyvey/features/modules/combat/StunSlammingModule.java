package me.alpha432.oyvey.features.modules.combat;

import me.alpha432.oyvey.event.impl.network.PacketEvent;
import me.alpha432.oyvey.event.system.Subscribe;
import me.alpha432.oyvey.features.modules.Module;
import me.alpha432.oyvey.features.settings.Setting;
import me.alpha432.oyvey.util.EntityUtil;
import me.alpha432.oyvey.util.InventoryUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

public class StunSlammingModule extends Module {
    
    // ==================== SETTINGS ====================
    private final Setting<Integer> stunChance = this.register(new Setting<>("StunChance", 100, 0, 100));
    private final Setting<Integer> minDelay = this.register(new Setting<>("MinDelay", 2, 0, 12));
    private final Setting<Integer> maxDelay = this.register(new Setting<>("MaxDelay", 8, 0, 12));
    private final Setting<Boolean> randomize = this.register(new Setting<>("Randomize", true));
    private final Setting<Double> range = this.register(new Setting<>("Range", 4.2, 1.0, 6.0));
    private final Setting<Boolean> rotate = this.register(new Setting<>("Rotate", true));
    private final Setting<Boolean> criticals = this.register(new Setting<>("Criticals", true));
    
    // ==================== INTERNE VARIABLEN ====================
    private final Random random = new Random();
    private int currentTickDelay = 0;
    private int tickCounter = 0;
    private int actionPhase = 0; // 0=idle, 1=zuAxt, 2=attackAxt, 3=zuMace, 4=attackMace
    private PlayerEntity target = null;
    private int failedAttempts = 0;
    private long lastAttackTime = 0;
    private int axeSlot = -1;
    private int maceSlot = -1;
    
    public StunSlammingModule() {
        super("StunSlamming", "Automated Stun Slam combo with Axe and Mace", Category.COMBAT);
    }
    
    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || !this.isEnabled()) return;
        
        // Prüfen ob wir fallen
        if (!isFalling()) {
            reset();
            return;
        }
        
        // Gegner finden der blockt
        PlayerEntity blockTarget = findBlockingTarget();
        if (blockTarget == null) {
            reset();
            return;
        }
        
        // State Machine
        switch (actionPhase) {
            case 0: // IDLE
                handleIdle(blockTarget);
                break;
                
            case 1: // WARTEN vor Axt
            case 2: // WARTEN vor Axt-Angriff
            case 3: // WARTEN vor Mace
            case 4: // WARTEN vor Mace-Angriff
                handleWaiting();
                break;
        }
    }
    
    private void handleIdle(PlayerEntity blockTarget) {
        // Stun Chance check
        if (random.nextInt(100) >= stunChance.getValue()) {
            failedAttempts++;
            return;
        }
        
        // Inventar-Slots merken
        axeSlot = findBestAxe();
        maceSlot = findItem(Items.MACE);
        
        if (axeSlot == -1 || maceSlot == -1) {
            // Keine Axt oder Mace gefunden
            return;
        }
        
        target = blockTarget;
        actionPhase = 1;
        currentTickDelay = getNextDelay();
        tickCounter = 0;
        
        // Optional: Rotate to target
        if (rotate.getValue()) {
            rotateToTarget();
        }
    }
    
    private void handleWaiting() {
        tickCounter++;
        
        if (tickCounter >= currentTickDelay) {
            executeAction();
        }
    }
    
    private void executeAction() {
        if (target == null || mc.player == null) {
            reset();
            return;
        }
        
        switch (actionPhase) {
            case 1: // Switch to Axe
                if (axeSlot != -1) {
                    mc.player.getInventory().selectedSlot = axeSlot;
                    actionPhase = 2;
                    currentTickDelay = getNextDelay();
                    tickCounter = 0;
                } else {
                    reset();
                }
                break;
                
            case 2: // Attack with Axe
                attackTarget();
                actionPhase = 3;
                currentTickDelay = getNextDelay();
                tickCounter = 0;
                break;
                
            case 3: // Switch to Mace
                if (maceSlot != -1) {
                    mc.player.getInventory().selectedSlot = maceSlot;
                    actionPhase = 4;
                    currentTickDelay = getNextDelay();
                    tickCounter = 0;
                } else {
                    reset();
                }
                break;
                
            case 4: // Attack with Mace (The Slam!)
                attackTarget();
                if (criticals.getValue() && mc.player.isOnGround()) {
                    doCriticalPacket();
                }
                failedAttempts = 0;
                reset();
                break;
        }
    }
    
    private void attackTarget() {
        if (target == null || mc.player == null || mc.interactionManager == null) return;
        
        // Prüfen ob Ziel noch blockt und in Reichweite
        if (!target.isBlocking() || mc.player.distanceTo(target) > range.getValue()) {
            reset();
            return;
        }
        
        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
        lastAttackTime = System.currentTimeMillis();
    }
    
    private void doCriticalPacket() {
        if (mc.player == null) return;
        
        Vec3d pos = mc.player.getPos();
        boolean onGround = mc.player.isOnGround();
        
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(pos.x, pos.y + 0.0625, pos.z, false));
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(pos.x, pos.y, pos.z, false));
    }
    
    private int getNextDelay() {
        if (!randomize.getValue()) {
            return (minDelay.getValue() + maxDelay.getValue()) / 2;
        }
        
        if (maxDelay.getValue() <= minDelay.getValue()) {
            return minDelay.getValue();
        }
        
        // Basis-Zufall
        int delay = minDelay.getValue() + random.nextInt(maxDelay.getValue() - minDelay.getValue() + 1);
        
        // Dynamische Anpassungen
        if (mc.player != null) {
            // Schneller bei schnellem Fall
            if (mc.player.getVelocity().y < -0.8) {
                delay = Math.max(minDelay.getValue(), delay - 1);
            }
            
            // Zufällige Mikro-Schwankungen
            if (random.nextInt(100) < 20) {
                delay += random.nextBoolean() ? 1 : -1;
            }
        }
        
        return Math.max(minDelay.getValue(), Math.min(maxDelay.getValue(), delay));
    }
    
    private boolean isFalling() {
        if (mc.player == null) return false;
        return mc.player.getVelocity().y < -0.3 && !mc.player.isOnGround();
    }
    
    private PlayerEntity findBlockingTarget() {
        if (mc.player == null || mc.world == null) return null;
        
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (!player.isBlocking()) continue;
            
            if (mc.player.distanceTo(player) <= range.getValue() && mc.player.canSee(player)) {
                return player;
            }
        }
        return null;
    }
    
    private int findBestAxe() {
        int slot = InventoryUtil.findHotbarItem(Items.NETHERITE_AXE);
        if (slot != -1) return slot;
        
        slot = InventoryUtil.findHotbarItem(Items.DIAMOND_AXE);
        if (slot != -1) return slot;
        
        return InventoryUtil.findHotbarItem(Items.IRON_AXE);
    }
    
    private int findItem(net.minecraft.item.Item item) {
        return InventoryUtil.findHotbarItem(item);
    }
    
    private void rotateToTarget() {
        if (target == null || mc.player == null) return;
        
        float[] rotations = EntityUtil.getRotations(target);
        mc.player.setYaw(rotations[0]);
        mc.player.setPitch(rotations[1]);
    }
    
    public void reset() {
        actionPhase = 0;
        target = null;
        tickCounter = 0;
        axeSlot = -1;
        maceSlot = -1;
    }
    
    @Override
    public String getDisplayInfo() {
        if (target != null) {
            return target.getName().getString();
        }
        if (failedAttempts > 0) {
            return failedAttempts + " fails";
        }
        return null;
    }
}
