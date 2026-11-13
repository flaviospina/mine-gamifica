package terralume;

import java.util.ArrayList;
import java.util.List;

/** Receitas de montagem com formato (aceita espelhamento e qualquer posição na grade). */
final class Recipes {
    record R(int w, int h, int[] in, int out, int count) { }

    static final List<R> ALL = new ArrayList<>();

    /** rows: desenho da receita; keys: pares (char, id do item). Espaço = vazio. */
    private static void add(int out, int count, String[] rows, Object... keys) {
        int h = rows.length, w = rows[0].length();
        int[] in = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                char ch = rows[y].charAt(x);
                in[y * w + x] = -1;
                for (int k = 0; k < keys.length; k += 2) if ((Character) keys[k] == ch) in[y * w + x] = (Integer) keys[k + 1];
            }
        ALL.add(new R(w, h, in, out, count));
    }

    static {
        add(Block.PLANKS, 4, new String[]{"L"}, 'L', Block.LOG);
        add(Block.PLANKS, 4, new String[]{"L"}, 'L', Block.PINE_LOG);
        add(Item.STICK, 4, new String[]{"P", "P"}, 'P', Block.PLANKS);
        add(Block.WORKBENCH, 1, new String[]{"PP", "PP"}, 'P', Block.PLANKS);
        add(Block.FURNACE, 1, new String[]{"CCC", "C C", "CCC"}, 'C', Block.COBBLE);
        add(Block.TORCH, 4, new String[]{"C", "S"}, 'C', Item.COAL, 'S', Item.STICK);
        add(Block.STONE_BRICKS, 4, new String[]{"SS", "SS"}, 'S', Block.STONE);
        add(Block.SANDSTONE, 1, new String[]{"SS", "SS"}, 'S', Block.SAND);
        add(Block.SNOW, 1, new String[]{"SS", "SS"}, 'S', Block.SNOW_GRASS);
        add(Block.LAMP, 1, new String[]{" G ", "GTG", " G "}, 'G', Block.GLASS, 'T', Block.TORCH);
        add(Block.IRON_BLOCK, 1, new String[]{"III", "III", "III"}, 'I', Item.IRON_INGOT);
        add(Block.GOLD_BLOCK, 1, new String[]{"III", "III", "III"}, 'I', Item.GOLD_INGOT);
        add(Block.GEM_BLOCK, 1, new String[]{"III", "III", "III"}, 'I', Item.GEM);
        add(Item.IRON_INGOT, 9, new String[]{"B"}, 'B', Block.IRON_BLOCK);
        add(Item.GOLD_INGOT, 9, new String[]{"B"}, 'B', Block.GOLD_BLOCK);
        add(Item.GEM, 9, new String[]{"B"}, 'B', Block.GEM_BLOCK);
        int[] mats = {Block.PLANKS, Block.COBBLE, Item.IRON_INGOT, Item.GEM};
        for (int t = 0; t < 4; t++) {
            int m = mats[t];
            add(Item.PICK + t, 1, new String[]{"MMM", " S ", " S "}, 'M', m, 'S', Item.STICK);
            add(Item.AXE + t, 1, new String[]{"MM", "MS", " S"}, 'M', m, 'S', Item.STICK);
            add(Item.SHOVEL + t, 1, new String[]{"M", "S", "S"}, 'M', m, 'S', Item.STICK);
            add(Item.SWORD + t, 1, new String[]{"M", "M", "S"}, 'M', m, 'S', Item.STICK);
        }
    }

    static ItemStack match(ItemStack[] grid, int gw) {
        int gh = grid.length / gw, x0 = gw, y0 = gh, x1 = -1, y1 = -1;
        for (int y = 0; y < gh; y++)
            for (int x = 0; x < gw; x++)
                if (grid[y * gw + x] != null) { x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
        if (x1 < 0) return null;
        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        for (R r : ALL) {
            if (r.w != w || r.h != h) continue;
            for (int mirror = 0; mirror < 2; mirror++) {
                boolean ok = true;
                for (int y = 0; y < h && ok; y++)
                    for (int x = 0; x < w; x++) {
                        ItemStack s = grid[(y0 + y) * gw + x0 + x];
                        int want = r.in[y * w + (mirror == 0 ? x : w - 1 - x)];
                        if ((s == null ? -1 : s.id) != want) { ok = false; break; }
                    }
                if (ok) return new ItemStack(r.out, r.count);
            }
        }
        return null;
    }
}
