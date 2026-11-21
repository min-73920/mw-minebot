package com.haiselita.mwminebot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.Item;
import net.minecraft.item.ItemShears;
import net.minecraft.item.ItemTool;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerDestroyItemEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Mod(modid = MWMineBot.MODID, name = "useful MW", version = "1.0", clientSideOnly = true)
public class MWMineBot {

    public static final String MODID = "mwminebot";

    public static MWMineBot instance;

    private final Minecraft mc = Minecraft.getMinecraft();

    public final MiningBot miningBot = new MiningBot();
    public final TunnelBot tunnelBot = new TunnelBot();
    public final WallTimer wallTimer = new WallTimer();

    private KeyBinding menuKey;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        instance = this;
        BotConfig.load();

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new TargetRenderer());

        menuKey = new KeyBinding("Open MineBot menu", Keyboard.KEY_RCONTROL, "useful MW");
        ClientRegistry.registerKeyBinding(menuKey);
    }

    private static final Logger LOG = LogManager.getLogger("MWMineBot");

    /**
     * Running commentary, only with debug logging on. Like everything else
     * this mod says it goes to the game log (latest.log), never to chat.
     */
    public static void info(String msg) {
        if (BotConfig.debugLog) log(msg);
    }

    /**
     * Anything worth a record. Game log only: nothing from this mod is ever
     * put in the chat, where it only got in the way. Alerts that matter are
     * the sounds played alongside these.
     */
    public static void log(String msg) {
        if (msg == null) return;
        LOG.info(msg.replaceAll("§.", ""));
    }

    public void stopAll(String reason) {
        miningBot.stop(reason);
        tunnelBot.stop(reason);
    }

    public boolean isAnyRunning() {
        return miningBot.isRunning() || tunnelBot.isRunning();
    }

    public static boolean isAllowedBackgroundScreen(GuiScreen s) {
        return s == null || s instanceof GuiIngameMenu || s instanceof GuiChat;
    }

    public static boolean isBackgroundAllowedNow() {
        MWMineBot b = instance;
        if (b == null || !BotConfig.backgroundMine || !b.isAnyRunning()) return false;
        return isAllowedBackgroundScreen(Minecraft.getMinecraft().currentScreen);
    }

    public static boolean resolveLeftClick(boolean orig) {
        if (orig) return true;
        if (!isBackgroundAllowedNow()) return false;
        return Input.isHeld(Minecraft.getMinecraft().gameSettings.keyBindAttack);
    }

    public static boolean shouldPauseScreen(Object screen) {
        if (screen instanceof GuiIngameMenu && isBackgroundAllowedNow()) return false;
        return true;
    }

    /**
     * A pickaxe or axe wearing out mid-run is easy to miss, and everything
     * after it is slower without saying why.
     *
     * Only a digging tool, and only while a bot is running. The game fires
     * this event whenever a stack runs out on use -- placing the last block
     * of a stack, throwing the last ender pearl, armour breaking -- and all
     * of those used to set this sound off out of nowhere.
     */
    @SubscribeEvent
    public void onItemBroken(PlayerDestroyItemEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.entityPlayer != mc.thePlayer || event.original == null) return;
        if (!miningBot.isRunning() && !tunnelBot.isRunning()) return;
        Item item = event.original.getItem();
        if (!(item instanceof ItemTool) && !(item instanceof ItemShears)) return;
        try {
            log("Your " + event.original.getDisplayName() + " broke");
            if (mc.thePlayer != null) mc.thePlayer.playSound("note.pling", 1.0F, 0.5F);
        } catch (Exception ignored) { }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;

        // A real mouse button must never be left held, whatever else happens.
        Input.tick();

        if (mc.theWorld == null || mc.thePlayer == null) {
            Input.releaseAll();
            stopAll("left the world");
            wallTimer.reset();
            return;
        }

        // The menu is the only way in, so a single keypress can never start a bot.
        if (menuKey.isPressed()) {
            mc.displayGuiScreen(new GuiBotMenu());
        }

        // Chat, inventory, anything that is not our own menu or an allowed background screen: hands off.
        boolean bgAllowed = BotConfig.backgroundMine && isAllowedBackgroundScreen(mc.currentScreen);
        if (mc.currentScreen != null && !(mc.currentScreen instanceof GuiBotMenu) && !bgAllowed) {
            stopAll("a screen was opened");
        }

        // Our own menu only suspends the bots; closing it resumes them.
        if (mc.currentScreen instanceof GuiBotMenu) {
            miningBot.pauseInputs();
            tunnelBot.pauseInputs();
            wallTimer.onClientTick();
            return;
        }

        // The bots refuse to run here; the Walls Fall alert below still does,
        // since it only reads the scoreboard and plays a sound.
        if (ServerGuard.isDenied()) {
            stopAll("blocked server");
        }

        miningBot.onClientTick();
        tunnelBot.onClientTick();

        tickBackgroundActions();

        // One invariant covering every stop path there is: nothing running,
        // nothing held.
        if (!miningBot.isRunning() && !tunnelBot.isRunning() && Input.anyHeld()) {
            Input.releaseAll();
        }
        wallTimer.onClientTick();
    }

    private static Field fLeftClickCounter;
    private static Field fRightClickDelay;
    private static Method mSendClickBlock;
    private static Method mRightClickMouse;
    private static boolean reflectInit = false;
    private boolean wasBgDigging = false;

    private static void ensureReflect() {
        if (reflectInit) return;
        reflectInit = true;
        fLeftClickCounter = findField("field_71429_W", "leftClickCounter");
        fRightClickDelay = findField("field_71467_ac", "rightClickDelayTimer");
        mSendClickBlock = findMethod(new String[]{"func_147115_a", "sendClickBlockToController"}, boolean.class);
        mRightClickMouse = findMethod(new String[]{"func_147121_ag", "rightClickMouse"});
    }

    private static Field findField(String... names) {
        for (String n : names) {
            try {
                Field f = Minecraft.class.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Method findMethod(String[] names, Class<?>... params) {
        for (String n : names) {
            try {
                Method m = Minecraft.class.getDeclaredMethod(n, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * Vanilla runTick skips sendClickBlockToController / rightClickMouse whenever
     * currentScreen != null && !allowUserInput (e.g. GuiIngameMenu / GuiChat)
     * and sets leftClickCounter = 10000 every tick. Drive them while a bot runs
     * in an allowed background screen.
     */
    private void tickBackgroundActions() {
        boolean activeBg = mc.currentScreen != null && isBackgroundAllowedNow()
                && mc.thePlayer != null && mc.theWorld != null;
        if (!activeBg) {
            if (wasBgDigging) {
                wasBgDigging = false;
                if (mc.playerController != null) mc.playerController.resetBlockRemoving();
            }
            return;
        }
        ensureReflect();
        if (mc.entityRenderer != null) {
            mc.entityRenderer.getMouseOver(1.0F);
        }
        try {
            if (fLeftClickCounter != null) fLeftClickCounter.setInt(mc, 0);
        } catch (Throwable ignored) {}

        boolean attackHeld = Input.isHeld(mc.gameSettings.keyBindAttack);
        if (mSendClickBlock != null) {
            try {
                mSendClickBlock.invoke(mc, Boolean.valueOf(attackHeld));
                wasBgDigging = attackHeld;
            } catch (Throwable ignored) {}
        }

        if (!mc.thePlayer.isUsingItem() && mRightClickMouse != null) {
            while (mc.gameSettings.keyBindUseItem.isPressed()) {
                try { mRightClickMouse.invoke(mc); } catch (Throwable ignored) {}
            }
            if (Input.isHeld(mc.gameSettings.keyBindUseItem)) {
                try {
                    int delay = fRightClickDelay != null ? fRightClickDelay.getInt(mc) : 1;
                    if (delay == 0) mRightClickMouse.invoke(mc);
                } catch (Throwable ignored) {}
            }
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent ev) {
        if (ev.phase != TickEvent.Phase.START) return;
        if (mc.thePlayer == null) return;
        // Sampled per frame rather than per tick, otherwise a 40-150ms curve
        // only gets one or two steps and looks like a snap.
        miningBot.onRenderTick();
        tunnelBot.onRenderTick();
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent ev) {
        if (ev.message == null) return;
        String plain = ev.message.getUnformattedText();
        if (plain == null) return;
        String formatted = null;
        try {
            formatted = ev.message.getFormattedText();
        } catch (Exception ignored) {}
        miningBot.onChat(plain, formatted);
        tunnelBot.onChat(plain, formatted);
    }
}
