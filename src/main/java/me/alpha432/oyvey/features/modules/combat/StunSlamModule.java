package me.alpha432.oyvey.features.modules.combat;

import me.alpha432.oyvey.event.impl.network.PacketEvent;
import me.alpha432.oyvey.event.system.Subscribe;
import me.alpha432.oyvey.features.modules.Module;
import me.alpha432.oyvey.features.settings.Setting;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;

import java.util.Random;

public class StunSlammingModule extends Module {
    
    // ==================== SETTINGS ====================
    private final Setting<Integer> stunChance = this.register(new Setting<>("StunChance", 100, 0, 100));
    private final Setting<Integer> minDelay = this.register(new Setting<>("MinDelay", 2, 0, 12));
    private final Setting<Integer> maxDelay = this.register(new Setting<>("MaxDelay", 8, 0, 12));
    private final Setting<Boolean> randomize = this.register(new Setting<>("Randomize", true));
    private final Setting<Double> range = this.register(new Setting<>("Range", 4.2, 1.0, 6.0));
    private final Setting<Boolean> autoSwitch = this.register(new Setting<>("AutoSwitch", true));
    private final Setting<Boolean> rotate = this.register(new Setting<>("Rotate", true));
    
    // ==================== INTERNE VARIABLEN ====================
    private final Random random = new Random();
    private int currentTickDelay = 0;
    private int tickCounter = 0;
    private int actionPhase = 0; // 0=idle, 1=zuAxt, 2=attackAxt, 3=zuMace, 4=attackMace
    private PlayerEntity target = null;
    private int failedAttempts = 0;
    private long lastAttackTime = 0;
    
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
                if (switchToAxe()) {
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
                if (switchToMace()) {
                    actionPhase = 4;
                    currentTickDelay = getNextDelay();
                    tickCounter = 0;
                } else {
                    reset();
                }
                break;
                
            case 4: // Attack with Mace (The Slam!)
                attackTarget();
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
        
        // Optional: Criticals packet senden (wie in deinem CriticalsModule)
        if (mc.player.onGround()) {
            sendCriticalPacket();
        }
    }
    
    private void sendCriticalPacket() {
        if (mc.player == null) return;
        
        boolean bl = mc.player.horizontalCollision;
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
            mc.player.getX(), mc.player.getY() + 0.1f, mc.player.getZ(), false, bl));
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
            mc.player.getX(), mc.player.getY(), mc.player.getZ(), false, bl));
    }
    
    private int getNextDelay() {
        if (!randomize.getValue()) {
            return (minDelay.getValue() + maxDelay.getValue()) / 2;
        }
        
        if (maxDelay.getValue() <= minDelay.getValue()) {
            return minDelay.getValue();
        }
        
        // Dynamischer Delay basierend auf Situation
        int baseDelay = minDelay.getValue() + random.nextInt(maxDelay.getValue() - minDelay.getValue() + 1);
        
        // Anpassungen für menschlicheres Verhalten
        if (mc.player != null) {
            // Schneller bei schnellem Fall
            if (mc.player.getVelocity().y < -0.8) {
                baseDelay = Math.max(minDelay.getValue(), baseDelay - 2);
            }
            
            // Zufällige Mikro-Schwankungen
            if (random.nextInt(100) < 30) {
                baseDelay += random.nextBoolean() ? 1 : -1;
            }
        }
        
        return Math.max(minDelay.getValue(), Math.min(maxDelay.getValue(), baseDelay));
    }
    
    private boolean isFalling() {
        if (mc.player == null) return false;
        return mc.player.getVelocity().y < -0.3 && !mc.player.isOnGround();
    }
    
    private PlayerEntity findBlockingTarget() {
        if (mc.player == null || mc.world == null) return null;
        
        Box box = mc.player.getBoundingBox().expand(range.getValue(), 2.0, range.getValue());
        
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (!player.isBlocking()) continue;
            
            if (mc.player.distanceTo(player) <= range.getValue() && mc.player.canSee(player)) {
                return player;
            }
        }
        return null;
    }
    
    private boolean switchToAxe() {
        if (mc.player == null) return false;
        
        int axeSlot = findBestAxe();
        if (axeSlot != -1) {
            if (autoSwitch.getValue()) {
                mc.player.getInventory().selectedSlot = axeSlot;
            }
            return true;
        }
        return false;
    }
    
    private boolean switchToMace() {
        if (mc.player == null) return false;
        
        int maceSlot = findItem(Items.MACE);
        if (maceSlot != -1) {
            if (autoSwitch.getValue()) {
                mc.player.getInventory().selectedSlot = maceSlot;
            }
            return true;
        }
        return false;
    }
    
    private int findBestAxe() {
        if (mc.player == null) return -1;
        
        int slot = findItem(Items.NETHERITE_AXE);
        if (slot != -1) return slot;
        
        slot = findItem(Items.DIAMOND_AXE);
        if (slot != -1) return slot;
        
        return findItem(Items.IRON_AXE);
    }
    
    private int findItem(net.minecraft.item.Item item) {
        if (mc.player == null) return -1;
        
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == item) {
                return i;
            }
        }
        return -1;
    }
    
    private void rotateToTarget() {
        if (target == null || mc.player == null) return;
        
        // Simple Rotation - du kannst hier deine eigene Rotationslogik einfügen
        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        float yaw = (float) (Math.atan2(dz, dx) * 180 / Math.PI) - 90;
        
        mc.player.setYaw(yaw);
    }
    
    private void reset() {
        actionPhase = 0;
        target = null;
        tickCounter = 0;
    }
    
    @Override
    public String getDisplayInfo() {
        if (target != null) {
            return target.getName().getString();
        }
        if (failedAttempts > 0) {
            return failedAttempts + " fails";
        }
        return "Ready";
    }
    
    @Subscribe
    private void onPacketSend(PacketEvent.Send event) {
        // Hier könntest du Packet-Manipulation für Anti-Cheat Umgehung einbauen
        // Ähnlich wie in deinem CriticalsModule
    }
}
