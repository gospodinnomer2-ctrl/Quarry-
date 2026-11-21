package quarry.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Hud {
    private static final int BG = Ui.HUD_BG;
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    // состояния анимаций: у каждого элемента худа своё плавное значение 0..1
    private static final Map<String, Smooth> SM = new HashMap<>();
    // последние показанные тексты, чтобы элемент мог красиво исчезнуть
    private static final Map<String, String[]> LAST = new HashMap<>();
    private static final Map<String, Boolean> PREV = new HashMap<>();
    private static final List<Toast> TOASTS = new ArrayList<>();

    private record Toast(String name, boolean on, long at) {
    }

    // данные кадра
    private static GuiGraphics G;
    private static Font TR;
    private static int ACC, ACC2, SW;

    // последняя цель (для плавного исчезновения карточки)
    private static int tgtId = -1;
    private static String tgtName = "";
    private static double tgtDist = 0;
    private static float tgtHp = 0, tgtMax = 1;

    private static Smooth sm(String k) {
        return SM.computeIfAbsent(k, x -> new Smooth());
    }

    /** Колонка плашек: каждая плавно выезжает с края и гаснет, остальные плавно сдвигаются. */
    private static final class Stack {
        float y;
        final boolean right;

        Stack(float y, boolean right) {
            this.y = y;
            this.right = right;
        }

        void pill(String id, boolean show, String label, String value) {
            float p = sm("p_" + id).to(show ? 1f : 0f, 13f);
            if (show) LAST.put(id, new String[]{label, value});
            if (p <= 0.01f) return;
            String[] t = LAST.get(id);
            if (t == null) return;
            int w = Ui.w(TR, t[0]) + (t[1].isEmpty() ? 0 : Ui.w(TR, t[1]) + 7) + 20;
            int slide = Math.round((1f - p) * 16f);
            int px = right ? SW - w - 6 + slide : 6 - slide;
            int py = Math.round(y);
            Ui.GA = p;
            Ui.rrect(G, px, py, w, 17, 8, BG);
            Ui.rrect(G, px + 5, py + 5, 2, 7, 1, ACC);
            Ui.draw(G, TR, t[0], px + 11, py + 5, ACC, false);
            if (!t[1].isEmpty()) Ui.draw(G, TR, t[1], px + 11 + Ui.w(TR, t[0]) + 7, py + 5, 0xFFFFFFFF, false);
            Ui.GA = 1f;
            y += 20f * p;
        }

        void watermark(Minecraft mc) {
            float p = sm("p_wm").to(Config.get("hud_watermark") ? 1f : 0f, 13f);
            if (p <= 0.01f) return;
            String name = "Quarry";
            String user = mc.player.getName().getString();
            boolean showName = Config.setting("hud_watermark", "name") == 1;
            int nw = Ui.w(TR, name);
            int w = nw + (showName ? Ui.w(TR, user) + 14 : 0) + 22;
            int px = 6 - Math.round((1f - p) * 16f);
            int py = Math.round(y);
            Ui.GA = p;
            Ui.rrect(G, px, py, w, 19, 9, BG);
            for (int i = 0; i < name.length(); i++) {
                float u = i / (float) Math.max(1, name.length() - 1);
                Ui.draw(G, TR, name.substring(i, i + 1), px + 11 + Ui.w(TR, name.substring(0, i)), py + 6, Ui.lerp(ACC, ACC2, u), false);
            }
            if (showName) {
                Ui.rrect(G, px + 11 + nw + 6, py + 5, 1, 9, 0, 0x50FFFFFF);
                Ui.draw(G, TR, user, px + 11 + nw + 13, py + 6, 0xFFFFFFFF, false);
            }
            Ui.GA = 1f;
            y += 23f * p;
        }
    }

    public static void render(GuiGraphics ctx, DeltaTracker tick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        Font tr = mc.font;
        G = ctx;
        TR = tr;
        int acc = Config.accent();
        ACC = acc;
        ACC2 = Config.accent2();
        int accDim = (acc & 0x00FFFFFF);
        int sw = ctx.guiWidth();
        int sh = ctx.guiHeight();
        SW = sw;

        pollToggles();
        // пока открыто меню, худ скрыт, но уведомления остаются; при закрытии меню худ проявляется под ним
        if (mc.screen instanceof MenuScreen menu && !menu.isClosing()) {
            toasts();
            return;
        }

        if (Config.get("edge_glow")) {
            int al = Math.min(255, Config.setting("edge_glow", "power") * 2) << 24;
            ctx.fillGradient(0, 0, sw, 30, accDim | al, accDim);
            ctx.fillGradient(0, sh - 30, sw, sh, accDim, accDim | al);
        }
        if (Config.get("lowhp") && mc.player.getHealth() <= 6f) {
            int pulse = 40 + (int) (30 * Math.sin(System.currentTimeMillis() / 220.0));
            ctx.fillGradient(0, 0, sw, 50, (pulse << 24) | 0xFF2020, 0x00FF2020);
            ctx.fillGradient(0, sh - 50, sw, sh, 0x00FF2020, (pulse << 24) | 0xFF2020);
        }
        if (Config.get("ring")) {
            int rad = Config.setting("ring", "radius");
            for (int a = 0; a < 360; a += 5) {
                double r = Math.toRadians(a);
                int px = sw / 2 + (int) Math.round(Math.cos(r) * rad);
                int py = sh / 2 + (int) Math.round(Math.sin(r) * rad);
                ctx.fill(px, py, px + 1, py + 1, acc);
            }
        }

        // FPS по центру сверху
        float pf = sm("fps").to(Config.get("hud_fps") ? 1f : 0f, 13f);
        if (pf > 0.01f) {
            boolean wm = Config.get("hud_watermark");
            String lab = wm ? "FPS" : "Quarry";
            String val = wm ? String.valueOf(mc.getFps()) : mc.getFps() + " FPS";
            int w = Ui.w(tr, lab + "  " + val) + 18;
            int ox = sw / 2 - w / 2, oy = 4 - Math.round((1f - pf) * 10f);
            Ui.GA = pf;
            Ui.rrect(ctx, ox, oy, w, 17, 8, BG);
            Ui.draw(ctx, tr, lab, ox + 9, oy + 5, acc, false);
            Ui.draw(ctx, tr, val, ox + 9 + Ui.w(tr, lab + "  "), oy + 5, 0xFFFFFFFF, false);
            Ui.GA = 1f;
        }

        // слева сверху
        Stack left = new Stack(6f, false);
        left.watermark(mc);
        boolean on = Config.get("hud_coords");
        left.pill("coords", on, "XYZ", !on ? "" : (int) Math.floor(mc.player.getX()) + "  " + (int) Math.floor(mc.player.getY())
                + "  " + (int) Math.floor(mc.player.getZ()) + "  " + mc.player.getDirection().getName());
        on = Config.get("hud_speed");
        String spv = "";
        if (on) {
            Vec3 v = mc.player.getDeltaMovement();
            double sp = Math.sqrt(v.x * v.x + v.z * v.z) * 20;
            spv = Config.setting("hud_speed", "unit") == 1 ? String.format("%.0f km/h", sp * 3.6) : String.format("%.1f b/s", sp);
        }
        left.pill("speed", on, "Speed", spv);
        on = Config.get("hud_ping");
        String ping = "—";
        if (on) {
            ClientPacketListener conn = mc.getConnection();
            if (conn != null) {
                PlayerInfo info = conn.getPlayerInfo(mc.player.getUUID());
                if (info != null) ping = info.getLatency() + " ms";
            }
        }
        left.pill("ping", on, "Ping", ping);
        on = Config.get("hud_clock");
        left.pill("clock", on, "Time", !on ? "" : LocalTime.now().format(DateTimeFormatter.ofPattern(Config.setting("hud_clock", "fmt") == 1 ? "hh:mm a" : "HH:mm")));
        on = Config.get("hud_world") && mc.level != null;
        String wt = "";
        if (on) {
            long dt = mc.level.getDayTime() % 24000L;
            wt = String.format("%02d:%02d", (dt / 1000 + 6) % 24, (dt % 1000) * 60 / 1000);
        }
        left.pill("world", on, "World", wt);
        on = Config.get("hud_memory");
        String mem = "";
        if (on) {
            Runtime rt = Runtime.getRuntime();
            long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
            mem = used + " / " + (rt.maxMemory() >> 20) + " MB";
        }
        left.pill("memory", on, "RAM", mem);

        // справа сверху
        Stack right = new Stack(6f, true);
        on = Config.get("hud_cps");
        right.pill("cps", on, "CPS", String.valueOf(QuarryClient.cps()));
        on = Config.get("hud_combo");
        right.pill("combo", on, "Combo", "x" + Combat.combo);
        on = Config.get("hud_reach");
        right.pill("reach", on, "Reach", Combat.reach > 0 ? String.format("%.2f", Combat.reach) : "—");
        boolean cdOn = Config.get("hud_cooldowns");
        Item[] its = {Items.ENDER_PEARL, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.CHORUS_FRUIT, Items.SHIELD, Items.WIND_CHARGE};
        String[] nms = {"Pearls", "Apple", "G-Apple", "Chorus", "Shield", "Wind"};
        for (int i = 0; i < its.length; i++) {
            float cd = cdOn ? mc.player.getCooldowns().getCooldownPercent(new ItemStack(its[i]), 0f) : 0f;
            right.pill("cd_" + nms[i], cdOn && cd > 0f, nms[i], Math.round(cd * 100) + "%");
        }
        boolean itOn = Config.get("hud_items");
        int[] counts = new int[4];
        String[] names = {"Totems", "Apples", "Pearls", "Arrows"};
        if (itOn) {
            Inventory inv = mc.player.getInventory();
            int apples = inv.countItem(Items.GOLDEN_APPLE) + inv.countItem(Items.ENCHANTED_GOLDEN_APPLE);
            counts = new int[]{inv.countItem(Items.TOTEM_OF_UNDYING), apples, inv.countItem(Items.ENDER_PEARL), inv.countItem(Items.ARROW)};
        }
        for (int i = 0; i < counts.length; i++) {
            right.pill("it_" + names[i], itOn && counts[i] > 0, names[i], "x" + counts[i]);
        }
        arrayList(right.y + 4f);

        // цель под прицелом: карточка плавно выезжает, полоска здоровья плавно меняется
        LivingEntity t = Combat.target;
        boolean showT = Config.get("hud_target") && t != null && t.isAlive();
        float pt = sm("tgt").to(showT ? 1f : 0f, 12f);
        if (showT) {
            if (t.getId() != tgtId) {
                tgtId = t.getId();
                sm("hp").snap(Math.min(1f, t.getHealth() / Math.max(1f, t.getMaxHealth())));
            }
            tgtName = t.getName().getString();
            tgtDist = Math.sqrt(mc.player.distanceToSqr(t));
            tgtHp = t.getHealth();
            tgtMax = Math.max(1f, t.getMaxHealth());
        }
        if (pt > 0.01f) {
            String hs = String.format("%.1f / %.0f", tgtHp, tgtMax);
            String ds = String.format("%.1f m", tgtDist);
            int w = Math.max(130, Math.max(Ui.w(tr, tgtName), Ui.w(tr, hs)) + Ui.w(tr, ds) + 34);
            int tx = sw / 2 + 28 + Math.round((1f - pt) * 14f), ty = sh / 2 + 20;
            float frac = sm("hp").to(Math.min(1f, tgtHp / tgtMax), 9f);
            int hc = frac > 0.5f ? 0xFF7CE08A : frac > 0.25f ? 0xFFFFC857 : 0xFFFF6B6B;
            Ui.GA = pt;
            Ui.rrect(ctx, tx, ty, w, 38, 10, BG);
            Ui.rrect(ctx, tx + 6, ty + 8, 2, 8, 1, acc);
            Ui.draw(ctx, tr, tgtName, tx + 13, ty + 8, 0xFFFFFFFF, false);
            Ui.draw(ctx, tr, ds, tx + w - 10 - Ui.w(tr, ds), ty + 8, acc, false);
            Ui.rrect(ctx, tx + 10, ty + 22, w - 20, 8, 4, 0xFF262B3A);
            Ui.rrect(ctx, tx + 10, ty + 22, Math.max(4, Math.round((w - 20) * frac)), 8, 4, hc);
            Ui.draw(ctx, tr, hs, tx + 13, ty + 40, 0xFF9AA8B8, false);
            Ui.GA = 1f;
        }

        // эффекты слева по центру
        Stack eff = new Stack(sh / 2f - 20f, false);
        boolean effOn = Config.get("hud_effects");
        for (MobEffectInstance e : mc.player.getActiveEffects()) {
            if (Config.get("fullbright") && e.getEffect().equals(MobEffects.NIGHT_VISION)) continue;
            int secs = e.getDuration() / 20;
            String time = e.isInfiniteDuration() ? "∞" : String.format("%d:%02d", secs / 60, secs % 60);
            String name = e.getEffect().value().getDisplayName().getString() + " " + (e.getAmplifier() + 1);
            eff.pill("ef_" + name, effOn, name, time);
        }

        // броня справа снизу
        int minPct = 100;
        int ay = sh - 112;
        for (EquipmentSlot slot : ARMOR) {
            ItemStack st = mc.player.getItemBySlot(slot);
            if (st.isEmpty() || !st.isDamageableItem()) continue;
            int pct = (st.getMaxDamage() - st.getDamageValue()) * 100 / Math.max(1, st.getMaxDamage());
            minPct = Math.min(minPct, pct);
            if (!Config.get("hud_armor")) continue;
            Ui.rrect(ctx, sw - 72, ay - 2, 66, 20, 8, BG);
            ctx.renderItem(st, sw - 69, ay);
            int col = pct > 50 ? 0xFF7CE08A : pct > 20 ? 0xFFFFC857 : 0xFFFF6B6B;
            Ui.draw(ctx, tr, pct + "%", sw - 47, ay + 4, col, false);
            ay += 22;
        }
        float pw = sm("warn").to(Config.get("hud_warn") && minPct < 10 ? 1f : 0f, 12f);
        if (pw > 0.01f) {
            String s = "Armor is breaking!";
            int w = Ui.w(tr, s) + 18;
            int wy = 26 - Math.round((1f - pw) * 8f);
            Ui.GA = pw;
            Ui.rrect(ctx, sw / 2 - w / 2, wy, w, 17, 8, 0xD0501418);
            Ui.draw(ctx, tr, s, sw / 2 - w / 2 + 9, wy + 5, 0xFFFF8A8A, false);
            Ui.GA = 1f;
        }

        keystrokes(mc, sh);
        toasts();
    }

    /** Клавиши WASD, мышь и пробел: при нажатии плавно заливаются цветом темы. */
    private static void keystrokes(Minecraft mc, int sh) {
        float p = sm("keys").to(Config.get("hud_keys") ? 1f : 0f, 12f);
        if (p <= 0.01f) return;
        boolean extra = Config.setting("hud_keys", "extra") == 1;
        int bx = 8 - Math.round((1f - p) * 24f);
        int by = sh - 24 - (extra ? 86 : 46);
        Ui.GA = p;
        key("W", "W", bx + 24, by, 22, 22, mc.options.keyUp.isDown());
        key("A", "A", bx, by + 24, 22, 22, mc.options.keyLeft.isDown());
        key("S", "S", bx + 24, by + 24, 22, 22, mc.options.keyDown.isDown());
        key("D", "D", bx + 48, by + 24, 22, 22, mc.options.keyRight.isDown());
        if (extra) {
            key("LMB", "LMB", bx, by + 48, 34, 22, mc.options.keyAttack.isDown());
            key("RMB", "RMB", bx + 36, by + 48, 34, 22, mc.options.keyUse.isDown());
            key("SPC", "", bx, by + 72, 70, 14, mc.options.keyJump.isDown());
        }
        Ui.GA = 1f;
    }

    private static void key(String id, String s, int x, int y, int w, int h, boolean down) {
        float p = sm("k_" + id).to(down ? 1f : 0f, 26f);
        Ui.rrect(G, x, y, w, h, 7, Ui.lerp(BG, ACC, p));
        int tc = Ui.lerp(0xFFFFFFFF, 0xFF04232B, p);
        if (s.isEmpty()) {
            Ui.rrect(G, x + w / 2 - 12, y + h / 2 - 1, 24, 2, 1, tc);
        } else {
            Ui.draw(G, TR, s, x + w / 2 - Ui.w(TR, s) / 2, y + h / 2 - 4, tc, false);
        }
    }

    /** Список включённых функций справа: появляются и исчезают плавно, цвет переливается между цветами темы. */
    private static void arrayList(float startY) {
        boolean master = Config.get("hud_arraylist");
        boolean grad = Config.setting("hud_arraylist", "gradient") == 1;
        boolean bg = Config.setting("hud_arraylist", "bg") == 1;
        List<String[]> mods = new ArrayList<>();
        for (String[] f : Config.FEATURES) {
            if (!f[0].equals("Interface")) mods.add(f);
        }
        mods.sort((a, b) -> Ui.w(TR, b[2]) - Ui.w(TR, a[2]));
        float y = startY;
        int i = 0;
        double time = System.currentTimeMillis() / 1000.0;
        for (String[] f : mods) {
            float p = sm("al_" + f[1]).to(master && Config.get(f[1]) ? 1f : 0f, 12f);
            if (p <= 0.01f) continue;
            int tw = Ui.w(TR, f[2]);
            int w = tw + 16;
            int px = SW - w - 4 + Math.round((1f - p) * (w + 6));
            int py = Math.round(y);
            float u = grad ? (float) (0.5 + 0.5 * Math.sin(time * 1.3 + i * 0.55)) : 0f;
            int col = Ui.lerp(ACC, ACC2, u);
            Ui.GA = p;
            if (bg) Ui.rrect(G, px, py, w, 14, 6, BG);
            Ui.draw(G, TR, f[2], px + 7, py + 3, col, false);
            Ui.rrect(G, px + w - 4, py + 3, 2, 8, 1, col);
            Ui.GA = 1f;
            y += 16f * p;
            i++;
        }
    }

    /** Следим за переключателями функций и показываем уведомления. */
    private static void pollToggles() {
        boolean first = PREV.isEmpty();
        boolean notify = Config.get("hud_notify");
        for (String[] f : Config.FEATURES) {
            boolean now = Config.get(f[1]);
            Boolean was = PREV.put(f[1], now);
            if (!first && was != null && was != now && notify && !f[1].equals("hud_notify")) {
                TOASTS.add(new Toast(f[2], now, System.currentTimeMillis()));
            }
        }
        while (TOASTS.size() > 4) TOASTS.remove(0);
    }

    private static void toasts() {
        long now = System.currentTimeMillis();
        TOASTS.removeIf(t -> now - t.at() > 2300);
        float y = 28f;
        for (Toast t : TOASTS) {
            long age = now - t.at();
            float in = Math.min(1f, age / 220f);
            float out = age > 1900 ? Math.max(0f, 1f - (age - 1900) / 400f) : 1f;
            float p = (1f - (1f - in) * (1f - in)) * out;
            String val = t.on() ? "Enabled" : "Disabled";
            int vcol = t.on() ? 0xFF7CE08A : 0xFFFF8A8A;
            int w = Ui.w(TR, t.name()) + Ui.w(TR, val) + 38;
            int px = SW / 2 - w / 2;
            int py = Math.round(y) - Math.round((1f - in) * 8f);
            Ui.GA = p;
            Ui.rrect(G, px, py, w, 19, 9, BG);
            Ui.rrect(G, px + 7, py + 7, 5, 5, 2, vcol);
            Ui.draw(G, TR, t.name(), px + 18, py + 6, 0xFFFFFFFF, false);
            Ui.draw(G, TR, val, px + 18 + Ui.w(TR, t.name()) + 8, py + 6, vcol, false);
            Ui.GA = 1f;
            y += 23f * p;
        }
    }
}
