package terralume;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.joml.Matrix4f;

/** Interface 2D: HUD, inventário, bancada, fornalha, pausa e tela de derrota. */
final class Ui {
    private static final int K_NORMAL = 0, K_RESULT = 1, K_TAKE_ONLY = 2, K_PALETTE = 3;

    private static final class Slot {
        final Supplier<ItemStack> get; final Consumer<ItemStack> set; final int x, y, kind;
        Slot(Supplier<ItemStack> get, Consumer<ItemStack> set, int x, int y, int kind) { this.get = get; this.set = set; this.x = x; this.y = y; this.kind = kind; }
    }

    private final Game g;
    private final Batch b;
    private final Matrix4f ortho = new Matrix4f();
    private final List<Slot> slots = new ArrayList<>();
    private final List<Object[]> buttons = new ArrayList<>(); // {x, y, w, h, texto, ação}
    private ItemStack cursor;
    private ItemStack[] craft = new ItemStack[4];
    private int craftW = 2, scale = 2, gw, gh, px, py, boundTex = -1;
    Furnace furnace;
    private static final int PW = 176, PH = 170;

    Ui(Game g) { this.g = g; this.b = g.batch; }

    // ------------------------------------------------------------------ primitivas

    private void bind(int tex) {
        if (boundTex == tex) return;
        b.draw(GL_TRIANGLES);
        glBindTexture(GL_TEXTURE_2D, tex);
        boundTex = tex;
    }

    private void fill(float x, float y, float w, float h, int argb) {
        bind(g.atlasTex);
        float u = Atlas.u0(Atlas.WHITE) + 0.01f, v = Atlas.v0(Atlas.WHITE) + 0.01f;
        b.rect(x, y, w, h, u, v, u, v, ((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, (argb >>> 24) / 255f);
    }

    private void tile(int t, float x, float y, float w, float h, float a) {
        bind(g.atlasTex);
        b.rect(x, y, w, h, Atlas.u0(t), Atlas.v0(t), Atlas.u1(t), Atlas.v1(t), 1, 1, 1, a);
    }

    private void text(String s, float x, float y, float size, int rgb) {
        bind(g.font.tex);
        g.font.draw(b, s, x + size * 0.09f, y + size * 0.09f, size, 0, 0, 0, 0.7f);
        g.font.draw(b, s, x, y, size, ((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, 1);
    }

    private void textCentered(String s, float cx, float y, float size, int rgb) { text(s, cx - g.font.width(s, size) / 2, y, size, rgb); }

    private void item(ItemStack s, float x, float y) {
        if (s == null) return;
        Item it = s.item();
        Block bl = it.isBlock() ? Block.REG[it.id] : null;
        bind(g.atlasTex);
        if (bl != null && bl.shape != Block.SHAPE_CROSS) {
            // cubo isométrico: topo, face esquerda e face direita
            int t = bl.top, sd = bl.side;
            float u0 = Atlas.u0(t), u1 = Atlas.u1(t), v0 = Atlas.v0(t), v1 = Atlas.v1(t);
            b.quad2(x + 8, y, u0, v0, x + 16, y + 4, u1, v0, x + 8, y + 8, u1, v1, x, y + 4, u0, v1, 1, 1, 1, 1);
            u0 = Atlas.u0(sd); u1 = Atlas.u1(sd); v0 = Atlas.v0(sd); v1 = Atlas.v1(sd);
            b.quad2(x, y + 4, u0, v0, x + 8, y + 8, u1, v0, x + 8, y + 16, u1, v1, x, y + 12, u0, v1, 0.62f, 0.62f, 0.62f, 1);
            b.quad2(x + 8, y + 8, u0, v0, x + 16, y + 4, u1, v0, x + 16, y + 12, u1, v1, x + 8, y + 16, u0, v1, 0.82f, 0.82f, 0.82f, 1);
        } else tile(it.tile, x, y, 16, 16, 1);
        if (it.durability > 0 && s.dmg > 0) {
            float f = 1 - s.dmg / (float) it.durability;
            fill(x + 1, y + 14, 14, 2, 0xff000000);
            fill(x + 1, y + 14, 14 * f, 1, f > 0.5f ? 0xff40d040 : f > 0.2f ? 0xffe0c030 : 0xffe03030);
        }
        if (s.count > 1) {
            String c = String.valueOf(s.count);
            text(c, x + 17 - g.font.width(c, 7), y + 9.5f, 7, 0xffffff);
        }
    }

    private void slotBox(float x, float y) {
        fill(x - 1, y - 1, 18, 18, 0xff4a4a52);
        fill(x, y, 17, 17, 0xff8e8e98);
        fill(x, y, 16, 16, 0xff6c6c76);
    }

    private void panel(float x, float y, float w, float h) {
        fill(x - 2, y - 2, w + 4, h + 4, 0xff1e1e24);
        fill(x, y, w, h, 0xffb9b9c4);
        fill(x, y, w, 1, 0xffe6e6ee); fill(x, y, 1, h, 0xffe6e6ee);
    }

    private float mx() { return (float) g.mouseX / scale; }
    private float my() { return (float) g.mouseY / scale; }

    // ------------------------------------------------------------------ telas com slots

    void open(int screen) {
        layout();
        slots.clear(); buttons.clear();
        Player p = g.player;
        if (screen == Game.SCR_PAUSE) {
            buttons.add(new Object[]{gw / 2 - 75, gh / 2 - 12, 150, 20, "Continuar", (Runnable) () -> g.setScreen(Game.SCR_NONE)});
            buttons.add(new Object[]{gw / 2 - 75, gh / 2 + 14, 150, 20, "Salvar e sair", (Runnable) () -> glfwSetWindowShouldClose(g.win, true)});
            return;
        }
        if (screen == Game.SCR_DEAD) {
            buttons.add(new Object[]{gw / 2 - 75, gh / 2 + 6, 150, 20, "Renascer", (Runnable) g::respawn});
            return;
        }
        for (int i = 0; i < 36; i++) {
            final int k = i;
            int sx = px + 8 + (i % 9) * 18, sy = i < 9 ? py + 146 : py + 88 + (i / 9 - 1) * 18;
            slots.add(new Slot(() -> p.inv[k], s -> p.inv[k] = s, sx, sy, K_NORMAL));
        }
        if (screen == Game.SCR_INV || screen == Game.SCR_BENCH) {
            craftW = screen == Game.SCR_INV ? 2 : 3;
            craft = new ItemStack[craftW * craftW];
            int ox = screen == Game.SCR_INV ? px + 80 : px + 30, oy = screen == Game.SCR_INV ? py + 24 : py + 18;
            for (int i = 0; i < craft.length; i++) {
                final int k = i;
                slots.add(new Slot(() -> craft[k], s -> craft[k] = s, ox + (i % craftW) * 18, oy + (i / craftW) * 18, K_NORMAL));
            }
            slots.add(new Slot(() -> Recipes.match(craft, craftW), s -> { }, screen == Game.SCR_INV ? px + 144 : px + 126, screen == Game.SCR_INV ? py + 33 : py + 36, K_RESULT));
            if (p.creative && screen == Game.SCR_INV) {
                int n = 0;
                for (Item it : Item.REG) {
                    if (it == null || it.id == Block.WATER) continue;
                    final int id = it.id;
                    slots.add(new Slot(() -> new ItemStack(id, 1), s -> { }, px - 116 + (n % 6) * 18, py - 12 + (n / 6) * 18, K_PALETTE));
                    n++;
                }
            }
        } else if (screen == Game.SCR_FURNACE) {
            Furnace f = furnace;
            slots.add(new Slot(() -> f.input, s -> f.input = s, px + 52, py + 18, K_NORMAL));
            slots.add(new Slot(() -> f.fuel, s -> f.fuel = s, px + 52, py + 54, K_NORMAL));
            slots.add(new Slot(() -> f.output, s -> f.output = s, px + 112, py + 36, K_TAKE_ONLY));
        }
    }

    /** Devolve ao jogador o que ficou na grade de montagem e no cursor. */
    void closeContainers() {
        for (int i = 0; i < craft.length; i++) { giveBack(craft[i]); craft[i] = null; }
        giveBack(cursor);
        cursor = null;
    }

    private void giveBack(ItemStack s) {
        if (s == null) return;
        ItemStack rest = g.player.give(s);
        if (rest != null) g.tossItem(rest);
    }

    void click(int button) {
        float mx = mx(), my = my();
        for (Object[] bt : buttons) {
            int x = (Integer) bt[0], y = (Integer) bt[1], w = (Integer) bt[2], h = (Integer) bt[3];
            if (mx >= x && mx < x + w && my >= y && my < y + h) { ((Runnable) bt[5]).run(); return; }
        }
        boolean hitSlot = false;
        for (Slot s : slots) {
            if (mx < s.x - 1 || mx >= s.x + 17 || my < s.y - 1 || my >= s.y + 17) continue;
            hitSlot = true;
            ItemStack in = s.get.get();
            if (s.kind == K_PALETTE) {
                cursor = (cursor != null && cursor.id == in.id) || button != GLFW_MOUSE_BUTTON_LEFT ? null : new ItemStack(in.id, in.item().maxStack);
            } else if (s.kind == K_RESULT) {
                if (in == null) break;
                if (cursor == null) cursor = in;
                else if (cursor.stacksWith(in) && cursor.count + in.count <= in.item().maxStack) cursor.count += in.count;
                else break;
                for (int i = 0; i < craft.length; i++) if (craft[i] != null && --craft[i].count <= 0) craft[i] = null;
            } else if (s.kind == K_TAKE_ONLY) {
                if (in == null) break;
                if (cursor == null) { cursor = in; s.set.accept(null); }
                else if (cursor.stacksWith(in) && cursor.count + in.count <= in.item().maxStack) { cursor.count += in.count; s.set.accept(null); }
            } else if (button == GLFW_MOUSE_BUTTON_LEFT) {
                if (cursor == null) { cursor = in; s.set.accept(null); }
                else if (in == null) { s.set.accept(cursor); cursor = null; }
                else if (in.stacksWith(cursor)) {
                    int n = Math.min(cursor.count, in.item().maxStack - in.count);
                    in.count += n; cursor.count -= n;
                    if (cursor.count <= 0) cursor = null;
                } else { s.set.accept(cursor); cursor = in; }
            } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                if (cursor == null && in != null) {
                    int n = (in.count + 1) / 2;
                    cursor = new ItemStack(in.id, n, in.dmg);
                    in.count -= n;
                    if (in.count <= 0) s.set.accept(null);
                } else if (cursor != null && (in == null || (in.stacksWith(cursor) && in.count < in.item().maxStack))) {
                    if (in == null) s.set.accept(new ItemStack(cursor.id, 1, cursor.dmg)); else in.count++;
                    if (--cursor.count <= 0) cursor = null;
                }
            }
            break;
        }
        // clicar fora do painel com um item no cursor joga o item no chão
        if (!hitSlot && cursor != null && slots.size() > 0 && (mx < px || mx > px + PW || my < py || my > py + PH)) {
            g.tossItem(cursor);
            cursor = null;
        }
    }

    // ------------------------------------------------------------------ desenho

    private void layout() {
        scale = Math.max(1, Math.min(g.fbH / 300, g.fbW / 400));
        gw = g.fbW / scale; gh = g.fbH / scale;
        px = (gw - PW) / 2; py = (gh - PH) / 2;
    }

    void render(boolean underwater) {
        int oldGw = gw, oldGh = gh;
        layout();
        if ((oldGw != gw || oldGh != gh) && g.screen != Game.SCR_NONE) open(g.screen);
        ortho.setOrtho(0, gw, gh, 0, -1, 1);
        g.basicSh.set("uMVP", ortho);
        boundTex = -1;
        Player p = g.player;
        if (underwater) fill(0, 0, gw, gh, 0x402050c0);
        if (p.hurtTime > 0.2f && !p.creative) fill(0, 0, gw, gh, 0x38ff0000);

        if (!g.hideHud) hud(p);
        int scr = g.screen;
        if (scr != Game.SCR_NONE) fill(0, 0, gw, gh, scr == Game.SCR_DEAD ? 0x90700000 : 0x90101018);
        if (scr == Game.SCR_INV || scr == Game.SCR_BENCH || scr == Game.SCR_FURNACE) container(scr);
        else if (scr == Game.SCR_PAUSE) {
            textCentered("Terralume", gw / 2f, gh / 2f - 62, 22, 0xffe9a0);
            textCentered("Jogo pausado", gw / 2f, gh / 2f - 36, 9, 0xffffff);
            textCentered("WASD mover | Espaço pular | Ctrl correr | E inventário | F4 modo | F3 info", gw / 2f, gh - 16, 6.5f, 0xd0d0d0);
        } else if (scr == Game.SCR_DEAD) {
            textCentered("Você desmaiou!", gw / 2f, gh / 2f - 40, 18, 0xffffff);
        }
        float mx = mx(), my = my();
        for (Object[] bt : buttons) {
            int x = (Integer) bt[0], y = (Integer) bt[1], w = (Integer) bt[2], h = (Integer) bt[3];
            boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
            fill(x - 1, y - 1, w + 2, h + 2, 0xff101014);
            fill(x, y, w, h, hover ? 0xff7a8fd0 : 0xff6c6c76);
            fill(x, y, w, 1, hover ? 0xffa8b8f0 : 0xff9a9aa4);
            textCentered((String) bt[4], x + w / 2f, y + 5.5f, 9, 0xffffff);
        }
        if (g.showDebug) debug(p);
        if (g.toastTime > 0) textCentered(g.toast, gw / 2f, gh - 62, 9, 0xffffff);
        b.draw(GL_TRIANGLES);
    }

    private void hud(Player p) {
        float cx = gw / 2f, cy = gh / 2f;
        if (g.screen == Game.SCR_NONE) { fill(cx - 5, cy - 0.5f, 10, 1, 0xd0ffffff); fill(cx - 0.5f, cy - 5, 1, 10, 0xd0ffffff); }
        float hx = cx - 91, hy = gh - 23;
        fill(hx - 1, hy - 1, 184, 24, 0xa0101014);
        for (int i = 0; i < 9; i++) {
            float x = hx + 1 + i * 20;
            fill(x, hy + 1, 20, 20, i == p.selected ? 0xffffffff : 0xff3c3c44);
            fill(x + 1, hy + 2, 18, 18, 0xc0606068);
            item(p.inv[i], x + 2, hy + 3);
        }
        if (!p.creative) {
            for (int i = 0; i < 10; i++) {
                int h = p.health - i * 2;
                tile(h >= 2 ? Atlas.HEART : h == 1 ? Atlas.HEART_HALF : Atlas.HEART_EMPTY, hx + i * 8, hy - 11, 9, 9, 1);
                int f = p.hunger - i * 2;
                tile(f >= 2 ? Atlas.FOOD : f == 1 ? Atlas.FOOD_HALF : Atlas.FOOD_EMPTY, hx + 173 - i * 8, hy - 11, 9, 9, 1);
            }
            if (p.air < 10) for (int i = 0; i < p.air; i++) tile(Atlas.BUBBLE, hx + 173 - i * 8, hy - 21, 9, 9, 1);
        }
        ItemStack held = p.held();
        if (held != null && g.screen == Game.SCR_NONE) textCentered(held.item().name, cx, hy - (p.creative ? 12 : 32), 7, 0xf0f0f0);
        if (g.targetMob != null && g.screen == Game.SCR_NONE)
            textCentered((g.targetMob.type == Mob.GRAZER ? "Lanudo" : "Brasal") + "  " + Math.max(0, g.targetMob.health) + " PV", cx, cy + 12, 7, 0xffffff);
    }

    private void container(int scr) {
        Player p = g.player;
        panel(px, py, PW, PH);
        String title = scr == Game.SCR_INV ? (p.creative ? "Inventário (criativo)" : "Inventário") : scr == Game.SCR_BENCH ? "Bancada" : "Fornalha";
        text(title, px + 8, py + 6, 8, 0x30303a);
        if (scr == Game.SCR_INV) text("Montagem", px + 80, py + 14, 6.5f, 0x4a4a55);
        if (scr != Game.SCR_FURNACE) {
            float ax = scr == Game.SCR_INV ? px + 122 : px + 92, ay = scr == Game.SCR_INV ? py + 38 : py + 41;
            fill(ax, ay, 14, 4, 0xff6c6c76); fill(ax + 12, ay - 3, 3, 10, 0xff6c6c76); fill(ax + 15, ay - 1, 2, 6, 0xff6c6c76);
        } else {
            Furnace f = furnace;
            fill(px + 53, py + 38, 14, 12, 0xff5a5a62);
            if (f.burn > 0 && f.burnMax > 0) {
                float k = f.burn / (float) f.burnMax;
                fill(px + 54, py + 39 + 10 * (1 - k), 12, 10 * k, 0xffff8a1e);
            }
            fill(px + 76, py + 42, 28, 4, 0xff6c6c76);
            fill(px + 76, py + 42, 28 * f.cook / (float) Furnace.COOK_TIME, 4, 0xffffffff);
        }
        boolean palette = false;
        for (Slot s : slots) if (s.kind == K_PALETTE) { palette = true; break; }
        if (palette) {
            int rows = 0;
            for (Slot s : slots) if (s.kind == K_PALETTE) rows = Math.max(rows, (s.y - (py - 12)) / 18 + 1);
            panel(px - 122, py - 26, 116, rows * 18 + 22);
            text("Todos os itens", px - 116, py - 23, 7, 0x30303a);
        }
        float mx = mx(), my = my();
        Slot hover = null;
        for (Slot s : slots) {
            slotBox(s.x, s.y);
            item(s.get.get(), s.x, s.y);
            if (mx >= s.x - 1 && mx < s.x + 17 && my >= s.y - 1 && my < s.y + 17) { hover = s; fill(s.x, s.y, 16, 16, 0x60ffffff); }
        }
        if (cursor != null) item(cursor, mx - 8, my - 8);
        else if (hover != null && hover.get.get() != null) {
            String name = hover.get.get().item().name;
            float w = g.font.width(name, 7.5f);
            float tx = Math.min(mx + 8, gw - w - 6);
            fill(tx - 3, my - 13, w + 6, 12, 0xf0181822);
            text(name, tx, my - 11, 7.5f, 0xffffff);
        }
    }

    private void debug(Player p) {
        int bx = (int) Math.floor(p.x), by = (int) Math.floor(p.y), bz = (int) Math.floor(p.z);
        World w = g.world;
        int h = w.gen.height(bx, bz);
        int hour = (int) ((w.dayFraction() * 24 + 6) % 24), min = (int) ((w.dayFraction() * 24 * 60) % 60);
        String[] lines = {
                String.format("Terralume  %.0f fps", g.fps),
                String.format("XYZ %.1f / %.1f / %.1f", p.x, p.y, p.z),
                "Chunks " + w.chunks.size() + " (fila " + w.pendingCount() + ")  entidades " + w.entities.size(),
                "Bioma: " + WorldGen.BIOME_NAME[w.gen.biome(bx, bz, h)] + "  semente " + w.gen.seed,
                "Luz: céu " + w.sky(bx, Math.min(by + 1, Chunk.H - 1), bz) + " bloco " + w.blockLight(bx, Math.min(by + 1, Chunk.H - 1), bz),
                String.format("Hora %02d:%02d  %s", hour, min, p.creative ? "criativo" : "sobrevivência")};
        for (int i = 0; i < lines.length; i++) text(lines[i], 4, 4 + i * 10, 8, 0xffffff);
    }
}
