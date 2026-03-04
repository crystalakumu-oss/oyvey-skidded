package me.alpha432.oyvey.features.modules.combat;

import me.alpha432.oyvey.features.modules.Module;
import me.alpha432.oyvey.features.settings.Setting;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

public class StunSlammingModule extends Module {
    
    // ==================== KONFIGURIERBARE SETTINGS ====================
    // --- Wahrscheinlichkeit ---
    private final Setting<Integer> stunChance = this.register(new Setting<>("Chance %", 100, 0, 100));
    
    // --- Tick-Randomizer ---
    private final Setting<Integer> minTickDelay = this.register(new Setting<>("Min Ticks", 2, 0, 12));
    private final Setting<Integer> maxTickDelay = this.register(new Setting<>("Max Ticks", 8, 0, 12));
    private final Setting<Boolean> randomizeTicks = this.register(new Setting<>("Randomize", true));
    
    // --- REICHWEITE (min und max Blöcke) ---
    private final Setting<Double> minRange = this.register(new Setting<>("Min Range", 3.0, 1.0, 6.0));
    private final Setting<Double> maxRange = this.register(new Setting<>("Max Range", 4.5, 1.0, 6.0));
    
    // --- Optionale Features ---
    private final Setting<Boolean> rotateToTarget = this.register(new Setting<>("Rotate", true));
    private final Setting<Boolean> useCriticals = this.register(new Setting<>("Criticals", true));
    
    // ==================== INTERNE VARIABLEN ====================
    private final Random random = new Random();
    private int currentTickDelay = 0;
    private int tickCounter = 0;
    private int actionPhase = 0; // 0=idle, 1=switchToAxe, 2=attackAxe, 3=switchToMace, 4=attackMace
    private PlayerEntity target = null;
    private int failedAttempts = 0;
    private int axeSlot = -1;
    private int maceSlot = -1;
    
    public StunSlammingModule() {
        super("StunSlamming", "Schild brechen mit Axt + Slam mit Mace im Fall (1.21.11)", Category.COMBAT);
    }
    
    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || !this.isEnabled()) return;
        
        // Prüfen ob wir fallen (Stun Slam nur im Fall möglich)
        if (!isFalling()) {
            reset();
            return;
        }
        
        // Gegner finden der blockt und in Reichweite ist
        PlayerEntity blockTarget = findBlockingTarget();
        if (blockTarget == null) {
            reset();
            return;
        }
        
        // State Machine
        switch (actionPhase) {
            case 0:
                handleIdle(blockTarget);
                break;
            case 1:
            case 2:
            case 3:
            case 4:
                handleWaiting();
                break;
        }
    }
    
    private void handleIdle(PlayerEntity blockTarget) {
        // Zufällige Chance prüfen
        if (random.nextInt(100) >= stunChance.getValue()) {
            failedAttempts++;
            return;
        }
        
        // Inventar-Slots finden
        axeSlot = findBestAxe();
        maceSlot = findMaceSlot();
        
        if (axeSlot == -1 || maceSlot == -1) return;
        
        target = blockTarget;
        actionPhase = 1;
        currentTickDelay = getNextDelay();
        tickCounter = 0;
        
        if (rotateToTarget.getValue()) {
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
            case 1: // Zur Axt wechseln
                if (axeSlot != -1) {
                    mc.player.getInventory().selectedSlot = axeSlot;
                    actionPhase = 2;
                    currentTickDelay = getNextDelay();
                    tickCounter = 0;
                } else reset();
                break;
                
            case 2: // Mit Axt angreifen (Schild brechen)
                attackTarget();
                actionPhase = 3;
                currentTickDelay = getNextDelay();
                tickCounter = 0;
                break;
                
            case 3: // Zur Mace wechseln
                if (maceSlot != -1) {
                    mc.player.getInventory().selectedSlot = maceSlot;
                    actionPhase = 4;
                    currentTickDelay = getNextDelay();
                    tickCounter = 0;
                } else reset();
                break;
                
            case 4: // Mit Mace angreifen (Der Slam!)
                attackTarget();
                if (useCriticals.getValue() && mc.player.isOnGround()) {
                    doCriticalPacket();
                }
                failedAttempts = 0;
                reset();
                break;
        }
    }
    
    private void attackTarget() {
        if (target == null || mc.player == null || mc.interactionManager == null) return;
        
        // Prüfen ob Ziel noch blockt und in Reichweite ist
        double distance = mc.player.distanceTo(target);
        if (!target.isBlocking() || distance < minRange.getValue() || distance > maxRange.getValue()) {
            reset();
            return;
        }
        
        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
    }
    
    private void doCriticalPacket() {
        if (mc.player == null) return;
        
        Vec3d pos = mc.player.getPos();
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(pos.x, pos.y + 0.0625, pos.z, false));
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(pos.x, pos.y, pos.z, false));
    }
    
    private int getNextDelay() {
        if (!randomizeTicks.getValue()) {
            return (minTickDelay.getValue() + maxTickDelay.getValue()) / 2;
        }
        
        if (maxTickDelay.getValue() <= minTickDelay.getValue()) {
            return minTickDelay.getValue();
        }
        
        // Zufälliger Delay zwischen Min und Max
        int delay = minTickDelay.getValue() + random.nextInt(maxTickDelay.getValue() - minTickDelay.getValue() + 1);
        
        // Dynamische Anpassung bei sehr schnellem Fall
        if (mc.player != null && mc.player.getVelocity().y < -0.8) {
            delay = Math.max(minTickDelay.getValue(), delay - 1);
        }
        
        return Math.max(minTickDelay.getValue(), Math.min(maxTickDelay.getValue(), delay));
    }
    
    private boolean isFalling() {
        if (mc.player == null) return false;
        return mc.player.getVelocity().y < -0.3 && !mc.player.isOnGround();
    }
    
    private PlayerEntity findBlockingTarget() {
        if (mc.player == null || mc.world == null) return null;
        
        // Nur Gegner in der eingestellten Reichweite
        double currentMaxRange = maxRange.getValue();
        
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (!player.isBlocking()) continue;
            
            double distance = mc.player.distanceTo(player);
            if (distance >= minRange.getValue() && distance <= currentMaxRange && mc.player.canSee(player)) {
                return player;
            }
        }
        return null;
    }
    
    private int findBestAxe() {
        for (int i = 0; i < 9; i++) {
            var stack = mc.player.getInventory().getStack(i);
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
    
    private void rotateToTarget() {
        if (target == null || mc.player == null) return;
        
        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        float yaw = (float) (Math.atan2(dz, dx) * 180 / Math.PI) - 90;
        mc.player.setYaw(yaw);
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
        if (target != null) return target.getName().getString();
        if (failedAttempts > 0) return failedAttempts + " fails";
        return null;
    }
}
