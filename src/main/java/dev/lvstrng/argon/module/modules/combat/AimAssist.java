package dev.lvstrng.argon.module.modules.combat;

import dev.lvstrng.argon.event.events.HudListener;
import dev.lvstrng.argon.event.events.MouseMoveListener;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.Module;
import dev.lvstrng.argon.module.setting.BooleanSetting;
import dev.lvstrng.argon.module.setting.MinMaxSetting;
import dev.lvstrng.argon.module.setting.ModeSetting;
import dev.lvstrng.argon.module.setting.NumberSetting;
import dev.lvstrng.argon.utils.TimerUtils;
import dev.lvstrng.argon.utils.rotation.Rotation;
import dev.lvstrng.argon.utils.*;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.SwordItem;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public final class AimAssist extends Module implements HudListener, MouseMoveListener {

    // Targeting Settings
    private final ModeSetting<AimMode> aimAt = new ModeSetting<>("Aim At", AimMode.Head, AimMode.class);
    private final NumberSetting fov = new NumberSetting("FOV", 5, 360, 90, 1);
    private final NumberSetting radius = new NumberSetting("Radius", 0.1, 6, 4.5, 0.1);
    private final BooleanSetting seeOnly = new BooleanSetting("See Only", true);
    private final BooleanSetting stickyAim = new BooleanSetting("Sticky Aim", true)
            .setDescription("Aims at the last attacked player");
    private final NumberSetting targetSwitchDelay = new NumberSetting("Target Switch Delay", 0, 1000, 200, 50)
            .setDescription("Delay in milliseconds before switching to a new, higher-priority target");

    // Activation Conditions
    private final BooleanSetting onlyWeapon = new BooleanSetting("Only Weapon", true);
    private final BooleanSetting onLeftClick = new BooleanSetting("On Left Click", false)
            .setDescription("Only gets triggered if holding down left click");
    private final BooleanSetting attackCooldown = new BooleanSetting("Attack Cooldown", true)
            .setDescription("Only assist when the player's attack cooldown is over");

    // Speed & Smoothing
    private final MinMaxSetting horizontalSpeed = new MinMaxSetting("Horizontal Speed", 0, 10, 0.1, 1.5, 4);
    private final MinMaxSetting verticalSpeed = new MinMaxSetting("Vertical Speed", 0, 10, 0.1, 1.5, 4);
    private final NumberSetting speedChange = new NumberSetting("Speed Randomization", 0, 1000, 350, 1)
            .setDescription("Time in milliseconds to wait before randomizing speed again");
    private final ModeSetting<LerpMode> lerpMode = new ModeSetting<>("Lerp", LerpMode.Normal, LerpMode.class)
            .setDescription("The smoothing function to use for aiming");

    // Humanization
    private final NumberSetting randomization = new NumberSetting("Chance", 0, 100, 85, 1)
            .setDescription("The chance for the aim assist to update on any given frame");
    private final NumberSetting waitFor = new NumberSetting("Wait on Move", 0, 1000, 150, 1)
            .setDescription("After you move your mouse, aim assist will stop working for this amount of time");
    private final BooleanSetting smoothReset = new BooleanSetting("Smooth Reset", true)
            .setDescription("Gradually returns to a normal speed when not aiming at a target");
    private final NumberSetting breakTime = new NumberSetting("Break Time", 0, 5000, 0, 100)
            .setDescription("Randomly stops assisting for a short duration to seem more human");

    // Precision
    private final BooleanSetting horizontalAssist = new BooleanSetting("Horizontal", true);
    private final BooleanSetting verticalAssist = new BooleanSetting("Vertical", true);
    private final BooleanSetting stopAtTargetHorizontal = new BooleanSetting("Stop at Target Horiz", true)
            .setDescription("Stops horizontal assistance if already aiming at the entity");
    private final BooleanSetting stopAtTargetVertical = new BooleanSetting("Stop at Target Vert", true)
            .setDescription("Stops vertical assistance if already aiming at the entity");

    // Timers and State
    private final TimerUtils speedRandomizationTimer = new TimerUtils();
    private final TimerUtils mouseMoveTimer = new TimerUtils();
    private final TimerUtils targetSwitchTimer = new TimerUtils();
    private final TimerUtils breakTimer = new TimerUtils();
    private final TimerUtils breakDurationTimer = new TimerUtils();

    private boolean isMovingMouse;
    private float currentHorizontalSpeed, currentVerticalSpeed;
    private PlayerEntity currentTarget;
    private long lastBreakTime;

    public enum AimMode { Head, Chest, Legs }
    public enum LerpMode { Normal, Smoothstep, EaseOut, Sine }

    public AimAssist() {
        super("Aim Assist", "Automatically aims at players for you", -1, Category.COMBAT);
        addSettings(
                // Targeting
                aimAt, fov, radius, seeOnly, stickyAim, targetSwitchDelay,
                // Activation
                onlyWeapon, onLeftClick, attackCooldown,
                // Speed & Smoothing
                horizontalSpeed, verticalSpeed, speedChange, lerpMode,
                // Humanization
                randomization, waitFor, smoothReset, breakTime,
                // Precision
                horizontalAssist, verticalAssist, stopAtTargetHorizontal, stopAtTargetVertical
        );
    }

    @Override
    public void onEnable() {
        eventManager.add(HudListener.class, this);
        eventManager.add(MouseMoveListener.class, this);
        resetState();
    }

    @Override
    public void onDisable() {
        eventManager.remove(HudListener.class, this);
        eventManager.remove(MouseMoveListener.class, this);
        resetState();
    }

    private void resetState() {
        currentTarget = null;
        isMovingMouse = false;
        currentHorizontalSpeed = horizontalSpeed.getRandomValueFloat();
        currentVerticalSpeed = verticalSpeed.getRandomValueFloat();
        speedRandomizationTimer.reset();
        mouseMoveTimer.reset();
        targetSwitchTimer.reset();
        breakTimer.reset();
        breakDurationTimer.reset();
    }

    @Override
    public void onMouseMove(MouseMoveEvent event) {
        isMovingMouse = true;
        mouseMoveTimer.reset();
    }

    @Override
    public void onRenderHud(HudEvent event) {
        if (!shouldRun()) return;

        // Find a target if we don't have one or if it's time to reconsider
        if (currentTarget == null || shouldFindNewTarget()) {
            currentTarget = findBestTarget();
            if (currentTarget != null) {
                targetSwitchTimer.reset();
            }
        }

        if (currentTarget == null) {
            if (smoothReset.getValue()) {
                // Gradually decrease speed when no target
                currentHorizontalSpeed *= 0.95f;
                currentVerticalSpeed *= 0.95f;
            }
            return;
        }

        // Handle break time logic
        if (handleBreakTime()) return;

        // Randomize speed periodically
        if (speedRandomizationTimer.delay(speedChange.getValueFloat())) {
            currentHorizontalSpeed = horizontalSpeed.getRandomValueFloat();
            currentVerticalSpeed = verticalSpeed.getRandomValueFloat();
            speedRandomizationTimer.reset();
        }

        // Calculate rotations and apply assistance
        Vec3d targetPos = getTargetPos(currentTarget);
        Rotation targetRotation = RotationUtils.getDirection(mc.player, targetPos);

        applyAimAssist(targetRotation);
    }

    private boolean shouldRun() {
        if (mc.player == null || mc.currentScreen != null) return false;
        if (onlyWeapon.getValue() && !isHoldingWeapon()) return false;
        if (onLeftClick.getValue() && !isLeftClicking()) return false;
        if (attackCooldown.getValue() && mc.player.getAttackCooldownProgress(0.0f) < 1.0f) return false;
        if (isMovingMouse && !mouseMoveTimer.delay(waitFor
