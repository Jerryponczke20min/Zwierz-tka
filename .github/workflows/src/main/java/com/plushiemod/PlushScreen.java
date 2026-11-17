package com.plushiemod;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/** Animated, immediate-mode GUI. Everything is drawn by hand so it looks the same everywhere. */
public class PlushScreen extends Screen {
    private static final int W = 470, H = 322;
    private static final int BG = 0xFF161621, PANEL2 = 0xFF22222F, PANEL3 = 0xFF2F2F42;
    private static final int TEXT = 0xFFEDEDF7, DIM = 0xFF9696B2;
    private static final float[] SIZES = {0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 5f, 8f};
    private static final float[][] PRESETS = {
            {0.93f, 0.45f, 1f}, {0.77f, 0.5f, 1f}, {0.58f, 0.55f, 1f}, {0.45f, 0.5f, 0.95f},
            {0.15f, 0.55f, 1f}, {0.07f, 0.65f, 1f}, {0.0f, 0.7f, 1f}, {0f, 0f, 1f}};
    private static final int[] DYE_RGB = {
            0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52,
            0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21};

    private record Hit(int x, int y, int w, int h, Runnable click, DoubleConsumer drag) {
    }

    // ---- caches ----
    private static List<EntityType<?>> mobTypes;
    private static final Map<EntityType<?>, String> MOB_NAMES = new HashMap<>();
    private static List<Item> allItems;
    private static final Map<Item, String> ITEM_NAMES = new HashMap<>();
    private static final Map<Item, ItemStack> ITEM_STACKS = new HashMap<>();
    private List<Item> itemCache;
    private String itemCacheKey = null;

    // ---- state ----
    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, Float> anims = new HashMap<>();
    private Hit drag;
    private int tab = 0;
    private float tabX = -1f;
    private float open = 0f;
    private float time = 0f;
    private float dt = 0.016f;
    private long lastNs = 0;
    private double mx, my;
    private int clipY1 = Integer.MIN_VALUE, clipY2 = Integer.MAX_VALUE;
    private float scroll = 0, scrollTarget = 0, maxScroll = 0;
    private String search = "";
    private boolean searchFocus = false;
    private boolean onlySelected = false;
    private int pickMode = 0; // 1 plush, 2 feed, 3 decoy
    private String hoverItemName = "";
    private float fit = 1f;

    public PlushScreen() {
        super(Text.literal("Plushie Party"));
    }

    private static String t(String en, String pl) {
        String lang = MinecraftClient.getInstance().options.language;
        return lang != null && lang.startsWith("pl") ? pl : en;
    }

    @Override
    public boolean shouldPause() {
        return false; // keep the world (and your mobs) animating behind the menu
    }

    // =========================================================== colors
    private int accent() {
        PlushConfig c = PlushConfig.get();
        float h = c.rainbowUi ? (time * 0.12f) % 1f : c.hue;
        return 0xFF000000 | MathHelper.hsvToRgb(h, c.sat, c.bri);
    }

    private int onAccent() {
        int a = accent();
        float lum = 0.299f * ((a >> 16) & 255) + 0.587f * ((a >> 8) & 255) + 0.114f * (a & 255);
        return lum > 150 ? 0xFF14141C : 0xFFFFFFFF;
    }

    private static int mix(int a, int b, float t) {
        t = MathHelper.clamp(t, 0f, 1f);
        int r = (int) (((a >> 16) & 255) + ((((b >> 16) & 255) - ((a >> 16) & 255)) * t));
        int g = (int) (((a >> 8) & 255) + ((((b >> 8) & 255) - ((a >> 8) & 255)) * t));
        int bl = (int) ((a & 255) + (((b & 255) - (a & 255)) * t));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // =========================================================== primitives
    private void rr(DrawContext c, int x, int y, int w, int h, int r, int col) {
        if (w <= 0 || h <= 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        if (r <= 0) {
            c.fill(x, y, x + w, y + h, col);
            return;
        }
        c.fill(x, y + r, x + w, y + h - r, col);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int d = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
            c.fill(x + d, y + i, x + w - d, y + i + 1, col);
            c.fill(x + d, y + h - i - 1, x + w - d, y + h - i, col);
        }
    }

    private float anim(String key, float target) {
        float v = anims.getOrDefault(key, 0f);
        v += (target - v) * Math.min(1f, dt * 14f);
        anims.put(key, v);
        return v;
    }

    private boolean over(int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h && my >= clipY1 && my < clipY2;
    }

    private void hit(int x, int y, int w, int h, Runnable click, DoubleConsumer drag) {
        int y1 = Math.max(y, clipY1), y2 = Math.min(y + h, clipY2);
        if (y2 <= y1) return;
        hits.add(new Hit(x, y1, w, y2 - y1, click, drag));
    }

    private void text(DrawContext c, String s, int x, int y, int col) {
        c.drawText(textRenderer, s, x, y, col, false);
    }

    private void para(DrawContext c, String s, int x, int y, int w, int col) {
        for (OrderedText line : textRenderer.wrapLines(Text.literal(s), w)) {
            c.drawText(textRenderer, line, x, y, col, false);
            y += 10;
        }
    }

    private void button(DrawContext c, String id, int x, int y, int w, int h, String label, boolean on, Runnable r) {
        float k = anim("b" + id, over(x, y, w, h) ? 1f : 0f);
        int col = on ? mix(accent(), 0xFFFFFFFF, k * 0.18f) : mix(PANEL3, accent(), k * 0.45f);
        rr(c, x, y, w, h, 7, col);
        int tc = on ? onAccent() : (k > 0.5f ? onAccentIfHover() : TEXT);
        c.drawCenteredTextWithShadow(textRenderer, label, x + w / 2, y + (h - 8) / 2, tc);
        hit(x, y, w, h, r, null);
    }

    private int onAccentIfHover() {
        return TEXT;
    }

    private void smallBtn(DrawContext c, String id, int x, int y, String label, Runnable r) {
        float k = anim("s" + id, over(x, y, 14, 14) ? 1f : 0f);
        rr(c, x, y, 14, 14, 5, mix(PANEL3, accent(), k * 0.7f));
        c.drawCenteredTextWithShadow(textRenderer, label, x + 7, y + 3, TEXT);
        hit(x, y, 14, 14, r, null);
    }

    private void toggle(DrawContext c, String id, int x, int y, String label, boolean v, Consumer<Boolean> set) {
        float k = anim("t" + id, v ? 1f : 0f);
        rr(c, x, y, 26, 14, 7, mix(PANEL3, accent(), k));
        rr(c, x + 2 + (int) (12 * k), y + 2, 10, 10, 5, 0xFFFFFFFF);
        text(c, label, x + 34, y + 3, TEXT);
        hit(x, y, 34 + textRenderer.getWidth(label), 14, () -> {
            set.accept(!v);
            PlushConfig.get().save();
        }, null);
    }

    private void slider(DrawContext c, String id, int x, int y, int w, String label, float v, float min, float max,
                        String fmt, Consumer<Float> set) {
        float k = anim("l" + id, over(x, y, w, 22) ? 1f : 0f);
        text(c, label, x, y, DIM);
        String val = String.format(Locale.ROOT, fmt, v);
        text(c, val, x + w - textRenderer.getWidth(val), y, TEXT);
        int ty = y + 13;
        rr(c, x, ty, w, 5, 2, PANEL3);
        int fw = (int) (w * (v - min) / (max - min));
        rr(c, x, ty, Math.max(4, fw), 5, 2, accent());
        rr(c, x + Math.max(0, fw - 5), ty - 3, 10, 11, 4, mix(0xFFDDDDEE, 0xFFFFFFFF, k));
        hit(x, y, w, 22, null, d -> {
            float f = MathHelper.clamp((float) ((d - x) / w), 0f, 1f);
            set.accept(min + f * (max - min));
        });
    }

    private void searchBox(DrawContext c, int x, int y, int w) {
        if (searchFocus) rr(c, x - 1, y - 1, w + 2, 20, 7, accent());
        rr(c, x, y, w, 18, 6, PANEL2);
        String s;
        if (search.isEmpty() && !searchFocus) s = t("Search...", "Szukaj...");
        else s = search + (searchFocus && ((int) (time * 2) % 2 == 0) ? "_" : "");
        text(c, s, x + 7, y + 5, search.isEmpty() ? DIM : TEXT);
        hit(x, y, w, 18, () -> searchFocus = true, null);
    }

    // =========================================================== render
    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float partial) {
        long now = System.nanoTime();
        dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        time += dt;
        open = Math.min(1f, open + dt * 3.2f);
        fit = Math.min(1f, Math.min((height - 6f) / H, (width - 6f) / W));
        mx = ux(mouseX);
        my = uy(mouseY);
        hits.clear();
        scroll += (scrollTarget - scroll) * Math.min(1f, dt * 14f);

        c.fill(0, 0, width, height, ((int) (140 * Math.min(1f, open * 1.5f)) << 24));

        float tt = open - 1f;
        float c1 = 1.70158f, c3 = c1 + 1f;
        float ease = 1f + c3 * tt * tt * tt + c1 * tt * tt;
        float sc = fit * (0.82f + 0.18f * ease);

        var ms = c.getMatrices();
        ms.push();
        ms.translate(width / 2f, height / 2f, 0f);
        ms.scale(sc, sc, 1f);
        ms.translate(-width / 2f, -height / 2f, 0f);
        drawPanel(c, (width - W) / 2, (height - H) / 2);
        ms.pop();
    }

    private double ux(double v) {
        return (v - width / 2.0) / fit + width / 2.0;
    }

    private double uy(double v) {
        return (v - height / 2.0) / fit + height / 2.0;
    }

    private void drawPanel(DrawContext c, int px, int py) {
        rr(c, px - 3, py - 3, W + 6, H + 6, 13, 0x55000000);
        rr(c, px, py, W, H, 11, BG);

        int ac = accent();
        rr(c, px + 14, py + 14, 8, 8, 4, ac);
        c.drawText(textRenderer, t("Plushie Party", "Pluszakowa Impreza"), px + 28, py + 12, TEXT, true);
        text(c, t("Client-side only - nobody else sees any of this",
                "Tylko u Ciebie - nikt inny tego nie widzi"), px + 28, py + 24, DIM);

        int cx = px + W - 30;
        boolean hc = over(cx, py + 10, 18, 18);
        rr(c, cx, py + 10, 18, 18, 7, mix(PANEL3, 0xFFE05566, anim("close", hc ? 1f : 0f)));
        c.drawCenteredTextWithShadow(textRenderer, "x", cx + 9, py + 15, TEXT);
        hit(cx, py + 10, 18, 18, this::close, null);

        String[] names = {t("Plush", "Pluszak"), t("Mobs", "Moby"), t("Hay", "Siano"), t("Style", "Styl")};
        int tw = 86, tx0 = px + 14, ty = py + 40;
        float target = tx0 + tab * (tw + 6);
        if (tabX < 0) tabX = target;
        tabX += (target - tabX) * Math.min(1f, dt * 14f);
        rr(c, (int) tabX, ty, tw, 20, 8, ac);
        for (int i = 0; i < names.length; i++) {
            int tx = tx0 + i * (tw + 6);
            float k = anim("tab" + i, over(tx, ty, tw, 20) ? 1f : 0f);
            int col = i == tab ? onAccent() : mix(DIM, TEXT, k);
            c.drawCenteredTextWithShadow(textRenderer, names[i], tx + tw / 2, ty + 6, col);
            final int fi = i;
            hit(tx, ty, tw, 20, () -> switchTab(fi), null);
        }

        int x = px + 14, y = py + 70, w = W - 28, h = H - 70 - 12;
        hoverItemName = "";
        if (pickMode != 0) pickerView(c, x, y, w, h);
        else if (tab == 0) plushTab(c, x, y, w, h);
        else if (tab == 1) mobsTab(c, x, y, w, h);
        else if (tab == 2) hayTab(c, x, y, w, h);
        else styleTab(c, x, y, w, h);
    }

    private void switchTab(int i) {
        tab = i;
        pickMode = 0;
        search = "";
        scroll = 0;
        scrollTarget = 0;
        searchFocus = i == 1;
    }

    // =========================================================== tab: plush
    private void plushTab(DrawContext c, int x, int y, int w, int h) {
        PlushConfig cfg = PlushConfig.get();
        int lw = 220;

        itemCard(c, "plush", x, y, lw, cfg.plushItem, t("Plush is made from", "Pluszak z przedmiotu"), 1);

        slider(c, "pscale", x, y + 56, lw, t("Plush size", "Rozmiar pluszaka"), cfg.plushScale, 0.5f, 4f, "%.1fx",
                v -> cfg.plushScale = v);
        slider(c, "hop", x, y + 86, lw, t("Hop height", "Wysokość skoku"), cfg.hopHeight, 0.2f, 1.5f, "%.2f",
                v -> cfg.hopHeight = v);
        slider(c, "dsec", x, y + 116, lw, t("Dance length (s)", "Czas tańca (s)"), cfg.danceSeconds, 2, 30, "%.0f",
                v -> cfg.danceSeconds = Math.round(v));
        toggle(c, "phop", x, y + 150, t("I hop too", "Ja też podskakuję"), cfg.playerHop, v -> cfg.playerHop = v);
        toggle(c, "pspin", x, y + 172, t("I spin too (camera)", "Ja też się kręcę (kamera)"), cfg.playerSpin,
                v -> cfg.playerSpin = v);

        int rx = x + lw + 18, rw = w - lw - 18;
        rr(c, rx, y, rw, 92, 9, PANEL2);
        text(c, t("How it works", "Jak to działa"), rx + 10, y + 8, accent());
        para(c, t("Right-click the plush: it bounces, every mob hops with it, and you dance too. Click again to stop.",
                        "Kliknij pluszaka PPM: podskakuje, wszystkie moby tańczą razem z nim i Ty też. Kliknij jeszcze raz, żeby przerwać."),
                rx + 10, y + 22, rw - 20, TEXT);

        int by = y + 102;
        button(c, "pl1", rx, by, rw, 22, t("Place at crosshair  [N]", "Postaw w celowniku  [N]"), true,
                () -> PlushManager.placePlush(MinecraftClient.getInstance(), true));
        button(c, "pl2", rx, by + 28, rw, 22, t("Place at my feet", "Postaw pod nogami"), false,
                () -> PlushManager.placePlush(MinecraftClient.getInstance(), false));
        button(c, "pl3", rx, by + 56, rw, 22, PlushieMod.isDancing() ? t("Stop dance", "Zatrzymaj taniec")
                : t("Test dance!", "Testowy taniec!"), false, PlushieMod::toggleDance);
        button(c, "pl4", rx, by + 84, rw, 22, t("Remove all plushies", "Usuń wszystkie pluszaki") + " ("
                + PlushManager.plushCount() + ")", false, PlushManager::clearPlushes);

        text(c, t("Plushes aren't solid: you can walk through them and stand 'on' them.",
                "Pluszaki nie są solidne: możesz przez nie przejść i stać 'na' nich."), x, y + h - 10, DIM);
    }

    private void itemCard(DrawContext c, String id, int x, int y, int w, String itemId, String caption, int mode) {
        rr(c, x, y, w, 44, 9, PANEL2);
        ItemStack st = PlushManager.stackOf(itemId);
        rr(c, x + 8, y + 10, 24, 24, 6, PANEL3);
        c.drawItem(st, x + 12, y + 14);
        text(c, caption, x + 40, y + 9, DIM);
        String nm = st.getName().getString();
        text(c, nm, x + 40, y + 22, TEXT);
        button(c, "chg" + id, x + w - 66, y + 12, 58, 20, t("Change", "Zmień"), false, () -> openPicker(mode));
    }

    private void openPicker(int mode) {
        pickMode = mode;
        search = "";
        scroll = 0;
        scrollTarget = 0;
        searchFocus = true;
    }

    // =========================================================== tab: hay
    private void hayTab(DrawContext c, int x, int y, int w, int h) {
        PlushConfig cfg = PlushConfig.get();
        int hw = (w - 14) / 2;
        itemCard(c, "feed", x, y, hw, cfg.feedItem, t("Mobs follow me when I hold", "Moby idą za mną z"), 2);
        itemCard(c, "decoy", x + hw + 14, y, hw, cfg.decoyItem, t("Visual hay looks like", "Wizualne siano wygląda jak"), 3);

        slider(c, "tempt", x, y + 60, hw, t("Reaction range", "Zasięg reakcji"), cfg.temptRadius, 4, 32, "%.0f",
                v -> cfg.temptRadius = Math.round(v));

        int rx = x + hw + 14;
        button(c, "hay1", rx, y + 56, hw, 22, t("Place visual hay at crosshair  [M]", "Postaw wizualne siano  [M]"), true,
                () -> PlushManager.placeDecoy(MinecraftClient.getInstance()));
        button(c, "hay2", rx, y + 84, hw, 22, t("Remove all hay", "Usuń całe siano") + " (" + PlushManager.decoyCount() + ")",
                false, PlushManager::clearDecoys);

        rr(c, x, y + 122, w, 104, 9, PANEL2);
        text(c, t("What it does", "Co to robi"), x + 10, y + 130, accent());
        para(c, t("Pick ANY item (even dirt) as the 'wheat' - hold it and every fake mob walks up to you and looks at you, "
                        + "just like cows with wheat. Visual hay is a fake block made from any item: mobs gather around "
                        + "the nearest one and nibble at it. Real mobs and other players don't see or react to any of it.",
                "Wybierz DOWOLNY przedmiot (nawet ziemię) jako 'pszenicę' - trzymaj go, a wszystkie wirtualne moby podejdą "
                        + "i będą na Ciebie patrzeć, jak krowy z pszenicą. Wizualne siano to sztuczny blok z dowolnego "
                        + "przedmiotu: moby zbierają się wokół najbliższego i go skubią. Prawdziwe moby i inni gracze "
                        + "tego nie widzą."),
                x + 10, y + 144, w - 20, TEXT);
    }

    // =========================================================== tab: style
    private void styleTab(DrawContext c, int x, int y, int w, int h) {
        PlushConfig cfg = PlushConfig.get();

        text(c, t("Accent color", "Kolor motywu"), x, y, DIM);
        for (int i = 0; i < PRESETS.length; i++) {
            float[] p = PRESETS[i];
            int bx = x + i * 30, by = y + 12;
            float k = anim("pre" + i, over(bx, by, 24, 24) ? 1f : 0f);
            int col = 0xFF000000 | MathHelper.hsvToRgb(p[0], p[1], p[2]);
            rr(c, bx - 1 - (int) k, by - 1 - (int) k, 26 + (int) (2 * k), 26 + (int) (2 * k), 8, mix(col, 0xFFFFFFFF, 0.5f));
            rr(c, bx, by, 24, 24, 7, col);
            hit(bx, by, 24, 24, () -> {
                cfg.hue = p[0];
                cfg.sat = p[1];
                cfg.bri = p[2];
                cfg.rainbowUi = false;
                cfg.save();
            }, null);
        }
        int sw = (w - 30) / 3;
        slider(c, "hue", x, y + 46, sw, t("Hue", "Odcień"), cfg.hue, 0f, 1f, "%.2f", v -> cfg.hue = v);
        slider(c, "sat", x + sw + 15, y + 46, sw, t("Saturation", "Nasycenie"), cfg.sat, 0f, 1f, "%.2f", v -> cfg.sat = v);
        slider(c, "bri", x + 2 * (sw + 15), y + 46, sw, t("Brightness", "Jasność"), cfg.bri, 0.3f, 1f, "%.2f",
                v -> cfg.bri = v);
        toggle(c, "rainbowui", x, y + 80, t("Rainbow menu (animated)", "Tęczowe menu (animowane)"), cfg.rainbowUi,
                v -> cfg.rainbowUi = v);

        text(c, t("Sheep wool color (fake sheep)", "Kolor wełny owiec (wirtualne owce)"), x, y + 108, DIM);
        int sy = y + 122;
        // jeb_ rainbow swatch
        drawSwatch(c, "jeb", x, sy, 0xFF000000 | MathHelper.hsvToRgb((time * 0.3f) % 1f, 0.7f, 1f), cfg.sheepColor == -2,
                () -> setSheep(-2));
        c.drawCenteredTextWithShadow(textRenderer, "j", x + 12, sy + 8, 0xFF111111);
        drawSwatch(c, "rnd", x + 26, sy, 0xFF8A8AA0, cfg.sheepColor == -1, () -> setSheep(-1));
        c.drawCenteredTextWithShadow(textRenderer, "?", x + 38, sy + 8, 0xFFFFFFFF);
        for (int i = 0; i < 16; i++) {
            final int di = i;
            drawSwatch(c, "dye" + i, x + 56 + i * 24, sy, 0xFF000000 | DYE_RGB[i], cfg.sheepColor == i, () -> setSheep(di));
        }
        text(c, t("j = jeb_ rainbow sheep   ? = random   (applies after a moment)",
                "j = tęczowa owca jeb_   ? = losowy kolor   (zmiana po chwili)"), x, sy + 34, DIM);

        rr(c, x, y + 176, w, 50, 9, PANEL2);
        para(c, t("Settings are saved automatically to config/plushie-party.json.",
                        "Ustawienia zapisują się automatycznie w config/plushie-party.json."),
                x + 10, y + 186, w - 20, DIM);
    }

    private void setSheep(int v) {
        PlushConfig cfg = PlushConfig.get();
        cfg.sheepColor = v;
        cfg.save();
        MobManager.requestRebuild();
    }

    private void drawSwatch(DrawContext c, String id, int x, int y, int col, boolean sel, Runnable r) {
        float k = anim("sw" + id, over(x, y, 22, 22) ? 1f : 0f);
        if (sel) rr(c, x - 2, y - 2, 26, 26, 8, accent());
        else if (k > 0.05f) rr(c, x - 1, y - 1, 24, 24, 7, mix(BG, 0xFFFFFFFF, k * 0.6f));
        rr(c, x, y, 22, 22, 6, col);
        hit(x, y, 22, 22, r, null);
    }

    // =========================================================== tab: mobs
    private static void buildMobTypes() {
        if (mobTypes != null) return;
        mobTypes = new ArrayList<>();
        for (EntityType<?> et : Registries.ENTITY_TYPE) {
            if (et.getSpawnGroup() == SpawnGroup.MISC || !et.isSummonable()) continue;
            mobTypes.add(et);
            MOB_NAMES.put(et, et.getName().getString());
        }
        mobTypes.sort(Comparator.comparing(MOB_NAMES::get, String.CASE_INSENSITIVE_ORDER));
    }

    private void mobsTab(DrawContext c, int x, int y, int w, int h) {
        buildMobTypes();
        PlushConfig cfg = PlushConfig.get();

        toggle(c, "mobson", x, y + 2, t("Show mobs", "Pokaż moby"), cfg.mobsEnabled, v -> {
            cfg.mobsEnabled = v;
            MobManager.requestRebuild();
        });
        slider(c, "radius", x + 140, y - 2, 140, t("Radius", "Zasięg"), cfg.radius, 3, 20, "%.0f",
                v -> cfg.radius = Math.round(v));
        slider(c, "wspeed", x + 296, y - 2, w - 296, t("Walk speed", "Prędkość chodu"), cfg.wanderSpeed, 0.3f, 3f, "%.1fx",
                v -> cfg.wanderSpeed = v);

        searchBox(c, x, y + 28, w - 150);
        toggle(c, "onlysel", x + w - 140, y + 30, t("Only selected", "Tylko wybrane"), onlySelected, v -> onlySelected = v);

        String q = search.toLowerCase(Locale.ROOT).trim();
        List<EntityType<?>> list = new ArrayList<>();
        for (EntityType<?> et : mobTypes) {
            String id = Registries.ENTITY_TYPE.getId(et).toString();
            if (onlySelected && !cfg.mobs.containsKey(id)) continue;
            if (!q.isEmpty() && !MOB_NAMES.get(et).toLowerCase(Locale.ROOT).contains(q)
                    && !id.contains(q)) continue;
            list.add(et);
        }

        int rowH = 23;
        int ly = y + 54, lh = h - 54;
        maxScroll = Math.max(0, list.size() * rowH - lh);
        scrollTarget = MathHelper.clamp(scrollTarget, 0, maxScroll);

        c.enableScissor(x, ly, x + w, ly + lh);
        clipY1 = ly;
        clipY2 = ly + lh;
        int first = Math.max(0, (int) (scroll / rowH));
        for (int i = first; i < list.size(); i++) {
            int ry = ly + (int) (i * rowH - scroll);
            if (ry > ly + lh) break;
            mobRow(c, list.get(i), x, ry, w - 8);
        }
        clipY1 = Integer.MIN_VALUE;
        clipY2 = Integer.MAX_VALUE;
        c.disableScissor();

        if (list.isEmpty()) text(c, t("Nothing found.", "Nic nie znaleziono."), x + 10, ly + 8, DIM);
        scrollbar(c, x + w - 5, ly, lh, maxScroll);
    }

    private void scrollbar(DrawContext c, int x, int y, int h, float max) {
        if (max <= 0) return;
        int bh = Math.max(20, (int) (h * h / (h + max)));
        int by = y + (int) ((h - bh) * (scroll / max));
        rr(c, x, y, 4, h, 2, PANEL2);
        rr(c, x, by, 4, bh, 2, accent());
    }

    private void mobRow(DrawContext c, EntityType<?> et, int x, int y, int w) {
        PlushConfig cfg = PlushConfig.get();
        String id = Registries.ENTITY_TYPE.getId(et).toString();
        PlushConfig.MobEntry e = cfg.mobs.get(id);
        boolean sel = e != null;
        float k = anim("row" + id, over(x, y, w, 21) ? 1f : 0f);
        int bg = sel ? mix(PANEL2, accent(), 0.30f) : mix(PANEL2, PANEL3, k);
        rr(c, x, y, w, 21, 7, bg);
        text(c, MOB_NAMES.get(et), x + 9, y + 6, sel ? 0xFFFFFFFF : TEXT);

        int rx = x + w - 6;
        int cxn = rx - 58;
        smallBtn(c, id + "c-", cxn, y + 3, "-", () -> adjustCount(id, -1));
        String cs = String.valueOf(sel ? e.count : 0);
        c.drawCenteredTextWithShadow(textRenderer, cs, cxn + 29, y + 6, sel ? 0xFFFFFFFF : DIM);
        smallBtn(c, id + "c+", cxn + 44, y + 3, "+", () -> adjustCount(id, +1));

        int bx = cxn - 46;
        boolean baby = sel && e.baby;
        float kb = anim("baby" + id, baby ? 1f : 0f);
        rr(c, bx, y + 3, 40, 14, 5, mix(PANEL3, accent(), kb));
        c.drawCenteredTextWithShadow(textRenderer, t("Baby", "Małe"), bx + 20, y + 6, kb > 0.5f ? onAccent() : DIM);
        hit(bx, y + 3, 40, 14, () -> {
            PlushConfig.MobEntry en = ensure(id);
            en.baby = !en.baby;
            changed();
        }, null);

        int sx = bx - 84;
        float scale = sel ? e.scale : 1f;
        smallBtn(c, id + "s-", sx, y + 3, "-", () -> adjustSize(id, -1));
        c.drawCenteredTextWithShadow(textRenderer, "x" + trim(scale), sx + 41, y + 6, sel ? 0xFFFFFFFF : DIM);
        smallBtn(c, id + "s+", sx + 56 + 12, y + 3, "+", () -> adjustSize(id, +1));
        if (sel && over(sx - 4, y, 90, 21)) {
            // tiny hint, drawn dim
        }
    }

    private static String trim(float f) {
        return f == (int) f ? String.valueOf((int) f) : String.format(Locale.ROOT, "%.2f", f).replaceAll("0+$", "");
    }

    private PlushConfig.MobEntry ensure(String id) {
        PlushConfig cfg = PlushConfig.get();
        PlushConfig.MobEntry e = cfg.mobs.get(id);
        if (e == null) {
            e = new PlushConfig.MobEntry();
            cfg.mobs.put(id, e);
        }
        return e;
    }

    private void adjustCount(String id, int d) {
        PlushConfig cfg = PlushConfig.get();
        PlushConfig.MobEntry e = cfg.mobs.get(id);
        if (d > 0) {
            if (e == null) ensure(id);
            else e.count = Math.min(30, e.count + 1);
        } else if (e != null) {
            if (e.count <= 1) cfg.mobs.remove(id);
            else e.count--;
        }
        changed();
    }

    private void adjustSize(String id, int d) {
        PlushConfig.MobEntry e = ensure(id);
        int idx = 0;
        float best = 99f;
        for (int i = 0; i < SIZES.length; i++) {
            float diff = Math.abs(SIZES[i] - e.scale);
            if (diff < best) {
                best = diff;
                idx = i;
            }
        }
        idx = MathHelper.clamp(idx + d, 0, SIZES.length - 1);
        e.scale = SIZES[idx];
        changed();
    }

    private void changed() {
        PlushConfig.get().save();
        MobManager.requestRebuild();
    }

    // =========================================================== item picker
    private static void buildItems() {
        if (allItems != null) return;
        allItems = new ArrayList<>();
        for (Item it : Registries.ITEM) {
            if (it == Items.AIR) continue;
            allItems.add(it);
            ITEM_NAMES.put(it, it.getName().getString());
        }
        allItems.sort(Comparator.comparing(i -> Registries.ITEM.getId(i).toString()));
    }

    private List<Item> filteredItems() {
        buildItems();
        String q = search.toLowerCase(Locale.ROOT).trim();
        if (itemCache == null || !q.equals(itemCacheKey)) {
            itemCache = new ArrayList<>();
            for (Item it : allItems) {
                if (q.isEmpty() || ITEM_NAMES.get(it).toLowerCase(Locale.ROOT).contains(q)
                        || Registries.ITEM.getId(it).getPath().contains(q)) itemCache.add(it);
            }
            itemCacheKey = q;
        }
        return itemCache;
    }

    private void pickerView(DrawContext c, int x, int y, int w, int h) {
        String title = pickMode == 1 ? t("Choose the plush item", "Wybierz przedmiot na pluszaka")
                : pickMode == 2 ? t("Choose the 'wheat' item", "Wybierz przedmiot-'pszenicę'")
                : t("Choose the hay look", "Wybierz wygląd siana");
        text(c, title, x, y + 2, accent());
        button(c, "back", x + w - 70, y - 4, 70, 20, t("< Back", "< Wróć"), false, () -> {
            pickMode = 0;
            search = "";
        });
        searchBox(c, x, y + 20, w);

        List<Item> items = filteredItems();
        int cell = 24;
        int cols = Math.max(1, (w - 8) / cell);
        int rows = (items.size() + cols - 1) / cols;
        int gy = y + 46, gh = h - 46 - 14;
        maxScroll = Math.max(0, rows * cell - gh);
        scrollTarget = MathHelper.clamp(scrollTarget, 0, maxScroll);

        c.enableScissor(x, gy, x + w, gy + gh);
        clipY1 = gy;
        clipY2 = gy + gh;
        int firstRow = Math.max(0, (int) (scroll / cell));
        for (int r = firstRow; r < rows; r++) {
            int iy = gy + (int) (r * cell - scroll);
            if (iy > gy + gh) break;
            for (int col = 0; col < cols; col++) {
                int idx = r * cols + col;
                if (idx >= items.size()) break;
                Item it = items.get(idx);
                int ix = x + col * cell;
                boolean ho = over(ix, iy, cell, cell);
                float k = anim("it" + idx, ho ? 1f : 0f);
                rr(c, ix + 1, iy + 1, cell - 2, cell - 2, 6, mix(PANEL2, accent(), k * 0.55f));
                ItemStack st = ITEM_STACKS.computeIfAbsent(it, Item::getDefaultStack);
                c.drawItem(st, ix + 4, iy + 4);
                if (ho) hoverItemName = ITEM_NAMES.get(it);
                hit(ix, iy, cell, cell, () -> choose(it), null);
            }
        }
        clipY1 = Integer.MIN_VALUE;
        clipY2 = Integer.MAX_VALUE;
        c.disableScissor();
        scrollbar(c, x + w - 4, gy, gh, maxScroll);
        text(c, hoverItemName.isEmpty() ? items.size() + " " + t("items", "przedmiotów") : hoverItemName,
                x, y + h - 9, hoverItemName.isEmpty() ? DIM : TEXT);
    }

    private void choose(Item it) {
        PlushConfig cfg = PlushConfig.get();
        String id = Registries.ITEM.getId(it).toString();
        if (pickMode == 1) cfg.plushItem = id;
        else if (pickMode == 2) cfg.feedItem = id;
        else if (pickMode == 3) cfg.decoyItem = id;
        cfg.save();
        pickMode = 0;
        search = "";
        scroll = 0;
        scrollTarget = 0;
    }

    // =========================================================== input
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (open < 1f) return true;
        double ux = ux(mouseX), uy = uy(mouseY);
        searchFocus = false;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (ux >= h.x() && ux < h.x() + h.w() && uy >= h.y() && uy < h.y() + h.h()) {
                if (h.drag() != null) {
                    drag = h;
                    h.drag().accept(ux);
                }
                if (h.click() != null) h.click().run();
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (drag != null) {
            drag.drag().accept(ux(mouseX));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (drag != null) {
            drag = null;
            PlushConfig.get().save();
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scrollTarget = MathHelper.clamp(scrollTarget - (float) vertical * 26f, 0f, maxScroll);
        return true;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (searchFocus && chr >= 32 && chr != 127 && search.length() < 24) {
            search += chr;
            scroll = 0;
            scrollTarget = 0;
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 && pickMode != 0) { // ESC closes the picker first
            pickMode = 0;
            search = "";
            return true;
        }
        if (searchFocus && keyCode == 259) { // backspace
            if (!search.isEmpty()) search = search.substring(0, search.length() - 1);
            scroll = 0;
            scrollTarget = 0;
            return true;
        }
        if (!searchFocus && PlushieMod.openKey() != null && PlushieMod.openKey().matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
