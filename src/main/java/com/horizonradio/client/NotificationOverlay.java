package com.horizonradio.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

/** Rendered after the world and screens so the notification remains visible in-game. */
final class NotificationOverlay extends Gui {

    private static final NotificationCenter CENTER = new NotificationCenter();
    private static final NotificationOverlay INSTANCE = new NotificationOverlay();
    private static final ResourceLocation BACKGROUND = new ResourceLocation(
        "textures/gui/achievement/achievement_background.png");
    private static final ResourceLocation ICON = new ResourceLocation("horizonradio", "textures/gui/Play.png");
    private static final ResourceLocation FAVORITE_ICON = new ResourceLocation(
        "horizonradio",
        "textures/gui/Favorite.png");
    private static final ResourceLocation SEARCH_ICON = new ResourceLocation("horizonradio", "textures/gui/Search.png");
    private static final ResourceLocation REPEAT_ICON = new ResourceLocation("horizonradio", "textures/gui/Repeat.png");
    private static final ResourceLocation SHUFFLE_ICON = new ResourceLocation(
        "horizonradio",
        "textures/gui/Shuffle.png");

    static long now() {
        return System.nanoTime() / 1000000L;
    }

    static void post(String key, String title, String detail) {
        CENTER.post(key, title, detail == null ? "" : detail, now());
    }

    static void configure(ClientUiSettings settings) {
        CENTER.configure(settings);
    }

    static void preview() {
        CENTER.preview(now());
    }

    static void clear() {
        CENTER.clear();
    }

    static void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            CENTER.clear();
            return;
        }
        CENTER.observe(HorizonRadioClient.notificationSnapshot(), now());
    }

    static void render() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null || mc.gameSettings.hideGUI) return;
        long now = now();
        NotificationCenter.Notice notice = CENTER.current(now);
        if (notice == null) return;
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        long age = now - notice.startedAt;
        double visible = Math.min(1.0, Math.min(age / 250.0, (NotificationCenter.DISPLAY_MILLIS - age) / 250.0));
        visible = Math.max(0.0, visible);
        double eased = visible * visible * (3.0 - 2.0 * visible);
        ClientUiSettings.Position position = HorizonRadioClient.uiSettings().position;
        int slide = (int) ((1.0 - eased) * 168);
        int x = position.left ? 4 - slide : resolution.getScaledWidth() - 164 + slide;
        int y = position.top ? 4 : resolution.getScaledHeight() - 36;
        if (position == ClientUiSettings.Position.TOP_MIDDLE) {
            x = (resolution.getScaledWidth() - 160) / 2;
            y = 4 - (int) ((1.0 - eased) * 36);
        }
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, resolution.getScaledWidth(), resolution.getScaledHeight(), 0, -1000, 1000);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1, 1, 1, 1);
            mc.getTextureManager()
                .bindTexture(BACKGROUND);
            INSTANCE.drawTexturedModalRect(x, y, 96, 202, 160, 32);
            mc.getTextureManager()
                .bindTexture(icon(notice.key));
            Gui.func_146110_a(x + 8, y + 8, 0, 0, 16, 16, 16, 16);
            mc.fontRenderer.drawString(
                fit(mc, notice.title, 122),
                x + 30,
                y + 7,
                notice.key.equals("error") ? 0xFFFF8888 : 0xFFFFFF55);
            mc.fontRenderer.drawString(fit(mc, notice.detail, 122), x + 30, y + 18, 0xFFFFFFFF);
        } finally {
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private static ResourceLocation icon(String key) {
        if (key.startsWith("favorites")) return FAVORITE_ICON;
        if (key.startsWith("search")) return SEARCH_ICON;
        if (key.equals("loop")) return REPEAT_ICON;
        if (key.equals("shuffle")) return SHUFFLE_ICON;
        return ICON;
    }

    private static String fit(Minecraft mc, String text, int width) {
        String clean = text == null ? "" : text.replaceAll("[\\r\\n]", " ");
        return mc.fontRenderer.getStringWidth(clean) <= width ? clean
            : mc.fontRenderer.trimStringToWidth(clean, width - mc.fontRenderer.getStringWidth("...")) + "...";
    }
}
