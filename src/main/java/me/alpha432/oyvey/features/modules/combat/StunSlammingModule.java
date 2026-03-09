package me.alpha432.oyvey.features.modules.combat;

import me.alpha432.oyvey.features.modules.Module;
import me.alpha432.oyvey.features.settings.Setting;
import me.alpha432.oyvey.features.settings.Bind;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.Random;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;

public class StunSlammingModule extends Module {

    private enum RotationMode { NONE, INSTANT, SMOOTH, PACKET }
    private enum AntiCheat { NONE, GRIM, VULCAN, INTRAVE }
    private enum TargetMode { CLOSEST, LOWEST_ARMOR, LOWEST_HP, MOST_VULNERABLE }

    // ==================== SETTINGS ====================
    private final Setting<TargetMode> targetMode = this.register(new Setting<>("TargetMode", TargetMode.MOST_VULNERABLE));
    private final Setting<Integer> targetSwitchDelay = this.register(new Setting<>("TargetSwitch", 10, 0, 40));
    private final Setting<Boolean> predictMovement = this.register(new Setting<>("Prediction", true));
    private final Setting<Integer> predictionTicks = this.register(new Setting<>("PredictionTicks", 2, 0, 10));
    private final Setting<Boolean> pingCompensation = this.register(new Setting<>("PingCompensation", true));

    private final Setting<Integer> stunChance = this.register(new Setting<>("StunChance", 100, 0, 100));
    private final Setting<Double> range = this.register(new Setting<>("Range", 4.5, 1.0, 6.0));
    private final Setting<Integer> minFallHeight = this.register(new Setting<>("MinFallHeight", 3, 1, 10));
    private final Setting<Boolean> autoJumpSlam = this.register(new Setting<>("AutoJumpSlam", false));
    private final Setting<Integer> jumpCooldown = this.register(new Setting<>("JumpCooldown", 20, 0, 40));
    private final Setting<Boolean> onlyAxeIfShield = this.register(new Setting<>("OnlyAxeIfShield", true));
    private final Setting<Boolean> critWindow = this.register(new Setting<>("CritWindow", true));
    private final Setting<Boolean> criticals = this.register(new Setting<>("Criticals", true));

    private final Setting<Integer> preAxeDelay = this.register(new Setting<>("PreAxeDelay", 1, 0, 10));
    private final Setting<Integer> postAxeDelay = this.register(new Setting<>("PostAxeDelay", 1, 0, 10));
    private final Setting<Integer> preMaceDelay = this.register(new Setting<>("PreMaceDelay", 1, 0, 10));
    private final Setting<Boolean> randomize = this.register(new Setting<>("Randomize", true));

    private final Setting<RotationMode> rotateMode = this.register(new Setting<>("RotateMode", RotationMode.PACKET));
    private final Setting<Integer> maxRotationSpeed = this.register(new Setting<>("MaxRotSpeed", 15, 5, 30));
    private final Setting<Boolean> keepHeadYaw = this.register(new Setting<>("KeepHeadYaw", true));

    private final Setting<AntiCheat> antiCheat = this.register(new Setting<>("AntiCheat", AntiCheat.GRIM));
    private final Setting<Integer> cpsLimit = this.register(new Setting<>("CPSLimit", 12, 1, 20));
    private final Setting<Boolean> waitForCooldown = this.register(new Setting<>("WaitCooldown", true));
    private final Setting<Boolean> silentSwitch = this.register(new Setting<>("SilentSwitch", true));

    private final Setting<Boolean> autoSwitchBack = this.register(new Setting<>("AutoSwitchBack", true));
    private final Setting<Boolean> inventory = this.register(new Setting<>("Inventory", false));
    private final Setting<Integer> scanInterval = this.register(new Setting<>("ScanInterval", 8, 2, 20));
    private final Setting<Boolean> debug = this.register(new Setting<>("Debug", true));
    private final Setting<Bind> keyBind = this.register(new Setting<>("Keybind", new Bind(-1)));

    // Items - MOJANG MAPPINGS!
    private final Item[] AXES = {Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE};
    private final Item[] MACES = {Items.MACE}; // ✅ ECHTES MACE!

    // ==================== INTERNE VARIABLEN ====================
    private final Random random = new Random();
    private final SimpleTimer timer = new SimpleTimer();
    private final SimpleTimer attackTimer = new SimpleTimer();
    private final SimpleTimer targetScanTimer = new SimpleTimer();
    private final SimpleTimer jumpTimer = new SimpleTimer();

    private Player target = null;
    private Player lastTarget = null;
    private int stage = 0;
    private int axeSlot = -1;
    private int maceSlot = -1;
    private int previousSlot = -1;
    private int clientSlot = -1;
    private int failedAttempts = 0;
    private int currentDelay = 0;
    private int tickCounter = 0;
    private int targetSwitchCounter = 0;
    private long lastShieldBreakTime = 0;
    private boolean shieldBroken = false;
    private boolean justBrokeShield = false;
    private boolean targetWasBlocking = false;
    private int hurtTimeTracker = 0;

    public StunSlammingModule() {
        super("StunSlamming", "Professional shield break + MACE slam combo for 1.21.11", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        if (mc.player == null) {
            this.disable();
            return;
        }

        reset();
        attackTimer.reset();
        targetScanTimer.reset();
        jumpTimer.reset();
        shieldBroken = false;
        justBrokeShield = false;
        targetWasBlocking = false;
        clientSlot = mc.player.getInventory().selected;

        if (debug.getValue()) {
            sendMessage("§a[StunSlamming] §fModule enabled! AntiCheat: §e" + antiCheat.getValue());
            sendMessage("§a[1.21.11] §fUsing real MACE item!");
        }
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null) return;

        tickCounter++;
        targetSwitchCounter++;

        if (target != null && target.hurtTime > 0) {
            hurtTimeTracker = target.hurtTime;
        }

        if (autoJumpSlam.getValue() && stage > 0 && stage < 8 && 
            mc.player.onGround() && mc.player.fallDistance == 0 && 
            jumpTimer.passedMs(jumpCooldown.getValue() * 50L)) {
            mc.player.jumpFromGround();
            jumpTimer.reset();
            if (debug.getValue()) sendMessage("§7[AutoJump] Jumping for MACE slam!");
        }

        if (tickCounter % scanInterval.getValue() == 0 || targetSwitchCounter > targetSwitchDelay.getValue()) {
            scanForTarget();
            targetSwitchCounter = 0;
        }

        if (justBrokeShield) handleShieldBreakCheck();

        if (shieldBroken && target != null && target.isBlocking()) {
            shieldBroken = false;
            if (debug.getValue()) sendMessage("§7[Shield] §cRecovered!");
        }

        boolean validFall = mc.player.fallDistance >= minFallHeight.getValue() && !mc.player.onGround();
        if (validFall && stage == 0 && target != null) {
            if (onlyAxeIfShield.getValue() && !target.isBlocking()) return;
            startCombo();
        }

        if (stage > 0) handleStateMachine();

        if (rotateMode.getValue() != RotationMode.NONE && target != null && target.isAlive()) handleRotation();

        if (stage == 0 && (target != null && (!target.isBlocking() || !target.isAlive()))) target = null;

        if (stage > 0 && !validateTarget()) reset();
    }

    private void handleShieldBreakCheck() {
        if (target == null) return;
        
        if (!target.isBlocking() && targetWasBlocking) {
            shieldBroken = true;
            justBrokeShield = false;
            lastShieldBreakTime = System.currentTimeMillis();
            if (debug.getValue()) sendMessage("§7[Shield] §aBreak confirmed!");
        } else if (!target.isBlocking()) {
            justBrokeShield = false;
            targetWasBlocking = false;
        }
    }

    private void scanForTarget() {
        if (mc.level == null || mc.player == null) return;

        List<Player> validTargets = new ArrayList<>();

        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;
            if (!player.isAlive() || player.isSpectator()) continue;
            if (!player.isBlocking()) continue;

            double distanceSq = mc.player.distanceToSqr(player);
            double rangeSq = range.getValue() * range.getValue();

            if (distanceSq <= rangeSq) {
                validTargets.add(player);
            }
        }

        if (validTargets.isEmpty()) {
            target = null;
            return;
        }

        switch (targetMode.getValue()) {
            case CLOSEST:
                target = validTargets.stream()
                    .min(Comparator.comparingDouble(p -> mc.player.distanceToSqr(p)))
                    .orElse(null);
                break;
            case LOWEST_ARMOR:
                target = validTargets.stream()
                    .min(Comparator.comparingDouble(this::getArmorProtection))
                    .orElse(null);
                break;
            case LOWEST_HP:
                target = validTargets.stream()
                    .min(Comparator.comparingDouble(Player::getHealth))
                    .orElse(null);
                break;
            case MOST_VULNERABLE:
                target = validTargets.stream()
                    .max(Comparator.comparingDouble(this::getVulnerabilityScore))
                    .orElse(null);
                break;
        }

        if (target != null && target != lastTarget && debug.getValue()) {
            sendMessage(String.format("§a[Target] §fNew: §e%s §7(%s) HP: §c%.1f §7Armor: §b%d §7Score: §d%.1f",
                target.getName().getString(), targetMode.getValue(),
                target.getHealth(), getArmorProtection(target), getVulnerabilityScore(target)));
            lastTarget = target;
        }
    }

    private double getArmorProtection(Player player) {
        double protection = 0;
        for (ItemStack stack : player.getInventory().armor) {
            if (!stack.isEmpty()) {
                protection += 2; // Simplified armor value
            }
        }
        return protection;
    }

    private double getVulnerabilityScore(Player player) {
        double score = 0;
        
        score += (20 - player.getHealth()) * 5;
        score -= getArmorProtection(player) * 0.5;
        
        if (player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING) score -= 15;
        if (player.getMainHandItem().getItem() == Items.TOTEM_OF_UNDYING) score -= 10;
        
        MobEffectInstance weakness = player.getEffect(MobEffects.WEAKNESS);
        if (weakness != null) score += (weakness.getAmplifier() + 1) * 10;
        
        MobEffectInstance resistance = player.getEffect(MobEffects.DAMAGE_RESISTANCE);
        if (resistance != null) score -= (resistance.getAmplifier() + 1) * 15;
        
        if (player.getAbsorptionAmount() > 0) score -= player.getAbsorptionAmount() * 2;
        
        if (shieldBroken && player == target) score += 30;
        
        if (player.hurtTime > 0) score += player.hurtTime * 2;
        
        return Math.max(0, score);
    }

    private Vec3 getPredictedPosition(Player player) {
        if (!predictMovement.getValue() || predictionTicks.getValue() == 0) {
            return player.position();
        }

        Vec3 velocity = player.getDeltaMovement();
        if (velocity == null) velocity = Vec3.ZERO;

        return player.position().add(
            velocity.x * predictionTicks.getValue(),
            velocity.y * predictionTicks.getValue(),
            velocity.z * predictionTicks.getValue()
        );
    }

    private void startCombo() {
        if (target == null) return;
        if (random.nextInt(100) >= stunChance.getValue()) {
            failedAttempts++;
            return;
        }

        axeSlot = findItemInHotbar(AXES);
        if (axeSlot == -1 && inventory.getValue()) {
            axeSlot = findItemInInventory(AXES);
        }

        maceSlot = findItemInHotbar(MACES);
        if (maceSlot == -1 && inventory.getValue()) {
            maceSlot = findItemInInventory(MACES);
        }

        if (axeSlot == -1 || maceSlot == -1) {
            if (debug.getValue()) {
                sendMessage("§c[Error] Missing items! Axe: " + (axeSlot != -1 ? "✓" : "✗") + 
                    " Mace: " + (maceSlot != -1 ? "✓" : "✗"));
            }
            return;
        }

        previousSlot = mc.player.getInventory().selected;
        clientSlot = previousSlot;
        targetWasBlocking = target.isBlocking();

        if (debug.getValue()) {
            sendMessage(String.format("§a[StunSlamming] §fStarting MACE combo on: §e%s §7(HP: %.1f)",
                target.getName().getString(), target.getHealth()));
        }

        stage = 1;
        currentDelay = getAntiCheatDelay(preAxeDelay.getValue());
        timer.reset();
    }

    private void handleStateMachine() {
        if (!timer.passedMs(currentDelay * 50L)) return;

        switch (stage) {
            case 1: stage = 2; currentDelay = 0; timer.reset(); break;
            case 2: performSwitch(axeSlot); stage = 3; currentDelay = getAntiCheatDelay(postAxeDelay.getValue()); timer.reset(); break;
            case 3: stage = 4; currentDelay = 0; timer.reset(); break;
            case 4: performAxeAttack(); stage = 5; currentDelay = getAntiCheatDelay(preMaceDelay.getValue()); timer.reset(); break;
            case 5: stage = 6; currentDelay = 0; timer.reset(); break;
            case 6: performSwitch(maceSlot); stage = 7; currentDelay = getAntiCheatDelay(preMaceDelay.getValue()); timer.reset(); break;
            case 7: stage = 8; currentDelay = 0; timer.reset(); break;
            case 8: performSlam(); reset(); break;
        }
    }

    private void performSwitch(int slot) {
        if (slot == -1) return;

        if (slot < 9) {
            if (silentSwitch.getValue()) {
                mc.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
            } else {
                mc.player.getInventory().selected = slot;
                clientSlot = slot;
            }
        } else {
            swapItemToHotbar(slot, previousSlot);
        }
    }

    private void performAxeAttack() {
        if (!validateTarget()) return;
        if (!checkAttackConditions()) return;

        targetWasBlocking = target.isBlocking();

        if (rotateMode.getValue() != RotationMode.NONE) {
            rotateToPosition(getPredictedPosition(target), true);
        }

        mc.player.attack(target);
        mc.player.swing(InteractionHand.MAIN_HAND);
        attackTimer.reset();

        if (targetWasBlocking) {
            justBrokeShield = true;
        }

        if (debug.getValue()) {
            sendMessage("§7[Axe] Shield break attempt" + (targetWasBlocking ? " (blocking)" : " (not blocking)"));
        }
    }

    private void performSlam() {
        if (!validateTarget()) return;
        if (!checkAttackConditions()) return;

        String shieldStatus = "";
        if (shieldBroken) {
            long timeSinceBreak = System.currentTimeMillis() - lastShieldBreakTime;
            if (timeSinceBreak < 5000) {
                shieldStatus = " §a(NO SHIELD! " + (5000 - timeSinceBreak)/1000 + "s)";
            }
        }

        if (rotateMode.getValue() != RotationMode.NONE) {
            rotateToPosition(getPredictedPosition(target), true);
        }

        mc.player.attack(target);
        mc.player.swing(InteractionHand.MAIN_HAND);
        attackTimer.reset();

        if (autoSwitchBack.getValue() && previousSlot != -1 && previousSlot < 9) {
            if (silentSwitch.getValue()) {
                mc.player.connection.send(new ServerboundSetCarriedItemPacket(previousSlot));
            } else {
                mc.player.getInventory().selected = previousSlot;
                clientSlot = previousSlot;
            }
        }

        if (debug.getValue()) {
            float maceDamage = calculateMaceDamage(mc.player.fallDistance);
            sendMessage(String.format("§a§lMACE SLAM! §fDamage: §c%.1f §7(%.1f blocks)%s §7[HT:%d]", 
                maceDamage, mc.player.fallDistance, shieldStatus, hurtTimeTracker));
        }

        failedAttempts = 0;
        shieldBroken = false;
        justBrokeShield = false;
    }

    private boolean validateTarget() {
        if (target == null) return false;
        if (!target.isAlive() || target.isSpectator()) {
            target = null;
            return false;
        }
        return true;
    }

    private boolean checkAttackConditions() {
        if (mc.player == null) return false;

        if (!attackTimer.passedMs(1000 / cpsLimit.getValue())) return false;

        if (waitForCooldown.getValue() && mc.player.getAttackStrengthScale(0.5f) < 1.0f) {
            return false;
        }

        Vec3 targetPos = getPredictedPosition(target);
        double rangeSq = range.getValue() * range.getValue();
        if (mc.player.distanceToSqr(targetPos) > rangeSq) return false;

        return true;
    }

    private void handleRotation() {
        if (target == null) return;
        rotateToPosition(getPredictedPosition(target), false);
    }

    private void rotateToPosition(Vec3 targetPos, boolean force) {
        if (mc.player == null) return;

        Vec3 eyes = mc.player.getEyePosition();
        double diffX = targetPos.x - eyes.x;
        double diffY = (targetPos.y + target.getBbHeight() / 2) - eyes.y;
        double diffZ = targetPos.z - eyes.z;
        double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float calcYaw = (float) (Math.toDegrees(Math.atan2(diffZ, diffX)) - 90F);
        float calcPitch = (float) -Math.toDegrees(Math.atan2(diffY, dist));

        switch (rotateMode.getValue()) {
            case INSTANT:
                mc.player.setYRot(calcYaw);
                mc.player.setXRot(calcPitch);
                if (keepHeadYaw.getValue()) mc.player.yHeadRot = calcYaw;
                break;
            case SMOOTH:
                smoothRotate(calcYaw, calcPitch);
                break;
            case PACKET:
                float yawDiff = Math.abs(Mth.wrapDegrees(mc.player.getYRot() - calcYaw));
                float pitchDiff = Math.abs(mc.player.getXRot() - calcPitch);
                if (force || yawDiff > 1.0f || pitchDiff > 1.0f) {
                    mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                        calcYaw, calcPitch, mc.player.onGround()
                    ));
                }
                break;
        }
    }

    private void smoothRotate(float targetYaw, float targetPitch) {
        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();

        float yawDiff = Mth.wrapDegrees(targetYaw - currentYaw);
        float pitchDiff = targetPitch - currentPitch;

        float maxSpeed = maxRotationSpeed.getValue();

        yawDiff = Mth.clamp(yawDiff, -maxSpeed, maxSpeed);
        pitchDiff = Mth.clamp(pitchDiff, -maxSpeed, maxSpeed);

        mc.player.setYRot(currentYaw + yawDiff);
        mc.player.setXRot(currentPitch + pitchDiff);

        if (keepHeadYaw.getValue()) {
            mc.player.yHeadRot = currentYaw + yawDiff;
        }
    }

    private void swapItemToHotbar(int inventorySlot, int hotbarSlot) {
        if (inventorySlot < 9 || inventorySlot > 35) return;
        if (hotbarSlot < 0 || hotbarSlot > 8) return;

        mc.gameMode.handleInventoryMouseClick(
            mc.player.containerMenu.containerId,
            inventorySlot,
            hotbarSlot,
            ClickType.SWAP,
            mc.player
        );
    }

    private int findItemInHotbar(Item... items) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            for (Item item : items) {
                if (stack.getItem() == item) return i;
            }
        }
        return -1;
    }

    private int findItemInInventory(Item... items) {
        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            for (Item item : items) {
                if (stack.getItem() == item) return i;
            }
        }
        return -1;
    }

    private float calculateMaceDamage(float fallDistance) {
        return Math.max(0, (fallDistance - 3) * 2.5f);
    }

    private int getAntiCheatDelay(int baseDelay) {
        int delay = baseDelay;
        
        switch (antiCheat.getValue()) {
            case GRIM: delay = Math.max(delay, 4); break;
            case VULCAN: delay = Math.max(delay, 5); break;
            case INTRAVE: delay = Math.max(delay, 6); break;
        }
        
        if (randomize.getValue() && delay > 0) {
            delay += random.nextInt(2);
        }
        
        return delay;
    }

    private void reset() {
        stage = 0;
        target = null;
        timer.reset();
        justBrokeShield = false;
        targetWasBlocking = false;
        if (silentSwitch.getValue() && clientSlot != -1) {
            mc.player.getInventory().selected = clientSlot;
        }
    }

    @Override
    public void onDisable() {
        reset();
        if (debug.getValue()) {
            sendMessage("§c[StunSlamming] Module disabled");
        }
    }

    @Override
    public String getDisplayInfo() {
        if (target != null) {
            String shieldIcon = shieldBroken ? "§c🛡️" : "§a🛡️";
            String critIcon = mc.player.fallDistance >= 3 ? "§c⬇️" : "§7⬇️";
            return target.getName().getString() + " §7(" + stage + ") " + shieldIcon + critIcon + " §5⚔️";
        }
        if (failedAttempts > 0) return failedAttempts + " fails";
        return antiCheat.getValue().toString();
    }

    private class SimpleTimer {
        private long time = System.currentTimeMillis();
        public void reset() { time = System.currentTimeMillis(); }
        public boolean passedMs(long ms) { return System.currentTimeMillis() - time >= ms; }
    }
}
