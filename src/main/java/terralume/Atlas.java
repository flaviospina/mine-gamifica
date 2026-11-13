package terralume;

import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;

/** Atlas de texturas 256x256 (tiles 16x16) desenhado inteiramente por código. */
final class Atlas {
    static final int TILE = 16, GRID = 16, SIZE = 256;

    static final int STONE = 0, GRASS_TOP = 1, GRASS_SIDE = 2, DIRT = 3, COBBLE = 4, PLANKS = 5, LOG_SIDE = 6,
            LOG_TOP = 7, LEAVES = 8, SAND = 9, GRAVEL = 10, WATER = 11, GLASS = 12, COAL_ORE = 13, IRON_ORE = 14,
            GOLD_ORE = 15, GEM_ORE = 16, BEDROCK = 17, SNOW = 18, SNOW_GRASS_SIDE = 19, ICE = 20, CACTUS_SIDE = 21,
            CACTUS_TOP = 22, SANDSTONE = 23, STONE_BRICKS = 24, WORKBENCH_TOP = 25, WORKBENCH_SIDE = 26,
            FURNACE_FRONT = 27, FURNACE_SIDE = 28, TORCH = 29, TALL_GRASS = 30, FLOWER_RED = 31, FLOWER_YELLOW = 32,
            PINE_LOG_SIDE = 33, PINE_LEAVES = 34, IRON_BLOCK = 35, GOLD_BLOCK = 36, GEM_BLOCK = 37, LAMP = 38,
            DEAD_BUSH = 39, FURNACE_FRONT_LIT = 40, WHITE = 41, CRACK = 42 /* 42..45 */, SUN = 46, MOON = 47,
            I_STICK = 48, I_COAL = 49, I_IRON = 50, I_GOLD = 51, I_GEM = 52, I_RAW_MEAT = 53, I_COOKED_MEAT = 54,
            I_FRUIT = 55, I_TOOLS = 56 /* 56..71 */, HEART = 72, HEART_HALF = 73, HEART_EMPTY = 74,
            FOOD = 75, FOOD_HALF = 76, FOOD_EMPTY = 77, BUBBLE = 78, FUR = 79;

    interface Gen { int at(int x, int y); }

    final int[] px = new int[SIZE * SIZE]; // ARGB

    private void tile(int idx, Gen g) {
        int ox = (idx % GRID) * TILE, oy = (idx / GRID) * TILE;
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) px[(oy + y) * SIZE + ox + x] = g.at(x, y);
    }

    static int hash(int x, int y, int s) {
        int h = x * 374761393 + y * 668265263 + s * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) & 0x7fffffff;
    }
    static float rnd(int x, int y, int s) { return (hash(x, y, s) & 0xffff) / 65535f; }
    private static int cl(float v) { return v < 0 ? 0 : v > 255 ? 255 : (int) v; }
    static int rgb(int r, int g, int b) { return 0xff000000 | cl(r) << 16 | cl(g) << 8 | cl(b); }
    static int rgba(int r, int g, int b, int a) { return cl(a) << 24 | cl(r) << 16 | cl(g) << 8 | cl(b); }
    static int mul(int c, float f) {
        return (c & 0xff000000) | cl(((c >> 16) & 255) * f) << 16 | cl(((c >> 8) & 255) * f) << 8 | cl((c & 255) * f);
    }
    /** Cor com granulação pseudoaleatória. */
    static int grain(int c, int x, int y, int s, float amt) { return mul(c, 1 - amt + 2 * amt * rnd(x, y, s)); }

    private static int stone(int x, int y) {
        float f = 0.9f + 0.12f * rnd(x, y, 1) + (rnd(x / 3, (y + x / 5) / 2, 2) > 0.7f ? -0.1f : 0);
        return mul(rgb(128, 130, 134), f);
    }
    private static int dirt(int x, int y) {
        int c = grain(rgb(121, 85, 58), x, y, 3, 0.13f);
        return rnd(x, y, 4) > 0.93f ? rgb(150, 140, 128) : c;
    }
    private static int grass(int x, int y) { return grain(rgb(96, 160, 62), x, y, 5, 0.16f); }
    private static int snow(int x, int y) { return grain(rgb(240, 246, 252), x, y, 6, 0.04f); }
    private static int sand(int x, int y) { return grain(rgb(222, 207, 150), x, y, 7, 0.07f); }
    private static int wood(int x, int y, int s) {
        return mul(rgb(176, 138, 84), 0.86f + 0.1f * rnd(0, y / 4, s) + 0.08f * rnd(x, y, s + 1));
    }
    private static int planks(int x, int y) {
        int row = y / 4;
        if (y % 4 == 3 || x == hash(row, 0, 11) % 16) return mul(wood(x, y, 12), 0.68f);
        return wood(x, y, 12);
    }
    private static int cobble(int x, int y) {
        int sx = (x + ((y / 4) % 2) * 3) % 16;
        if (y % 4 == 0 || sx % 5 == 0) return grain(rgb(78, 80, 84), x, y, 13, 0.1f);
        return mul(rgb(140, 141, 145), 0.8f + 0.3f * rnd(sx / 5, y / 4, 14) + 0.08f * rnd(x, y, 15));
    }
    private static Gen ore(int r, int g, int b, int s) {
        return (x, y) -> {
            boolean spot = rnd((x + 1) / 2, (y + s) / 2, s) > 0.74f && x > 0 && x < 15 && y > 0 && y < 15;
            return spot ? grain(rgb(r, g, b), x, y, s + 1, 0.15f) : stone(x, y);
        };
    }
    private static Gen metal(int r, int g, int b) {
        return (x, y) -> {
            float f = 1f;
            if (x == 0 || y == 0) f = 1.18f; else if (x == 15 || y == 15) f = 0.7f;
            else if ((x + y) % 7 == 0) f = 1.08f;
            return mul(rgb(r, g, b), f);
        };
    }
    private static Gen leaves(int r, int g, int b, int s) {
        return (x, y) -> rnd(x, y, s) > 0.8f ? 0 : mul(rgb(r, g, b), 0.7f + 0.5f * rnd(x / 2, y / 2, s + 1) * rnd(x, y, s + 2) + 0.15f);
    }
    private static Gen logSide(int r, int g, int b, int s) {
        return (x, y) -> mul(rgb(r, g, b), 0.8f + 0.25f * rnd(x, 0, s) + 0.1f * rnd(x, y / 3, s + 1));
    }
    private static Gen flower(int r, int g, int b) {
        return (x, y) -> {
            float dx = x - 7.5f, dy = y - 5f, d = dx * dx + dy * dy;
            if (d < 2) return rgb(250, 225, 90);
            if (d < 11 && Math.abs(dx) + Math.abs(dy) < 4.6f) return mul(rgb(r, g, b), 0.85f + 0.3f * rnd(x, y, 40));
            if ((x == 7 || x == 8) && y > 6) return rgb(58, 130, 46);
            if (y == 11 && (x == 6 || x == 9) || y == 10 && (x == 5 || x == 10)) return rgb(70, 150, 56);
            return 0;
        };
    }
    private static Gen disc(float cx, float cy, float rx, float ry, int r, int g, int b) {
        return (x, y) -> {
            float dx = (x - cx) / rx, dy = (y - cy) / ry, d = dx * dx + dy * dy;
            if (d > 1) return 0;
            float f = 1.15f - 0.45f * d - 0.25f * (dx + dy) * 0.5f;
            return mul(rgb(r, g, b), d > 0.72f ? f * 0.7f : f);
        };
    }
    private static Gen ingot(int r, int g, int b) {
        return (x, y) -> {
            int lx = x - (15 - y) / 3;
            if (y < 5 || y > 11 || lx < 1 || lx > 10) return 0;
            float f = y == 5 || lx == 1 ? 1.25f : y == 11 || lx == 10 ? 0.65f : 1f;
            return mul(rgb(r, g, b), f);
        };
    }
    private static Gen heart(int mode) { // 0 cheio, 1 metade, 2 vazio
        return (x, y) -> {
            float u = (x - 7.5f) / 6.5f, v = -(y - 7f) / 6.5f;
            float a = u * u + v * v - 0.55f;
            boolean in = a * a * a - u * u * v * v * v * 1.6f < 0;
            if (!in) return 0;
            boolean on = mode == 0 || (mode == 1 && x < 8);
            if (!on) return rgb(52, 28, 34);
            return mul(rgb(226, 44, 60), (x < 6 && y < 6 && y > 3) ? 1.35f : 1f - 0.03f * y);
        };
    }
    private static Gen food(int mode) {
        Gen base = disc(7.5f, 8f, 6f, 6f, 236, 160, 52);
        return (x, y) -> {
            int c = base.at(x, y);
            if (c == 0) return (y < 4 && y > 0 && x == 8 + (3 - y) / 2) ? rgb(70, 140, 50) : 0;
            boolean on = mode == 0 || (mode == 1 && x < 8);
            return on ? c : rgb(58, 44, 30);
        };
    }

    private static final String[][] TOOL_ART = {
        { // picareta
            "................", ".....HHHHHH.....", "....HhhhhhhHH...", ".....HHHHhhhH...", ".........shhH...",
            "........sSHhhH..", ".......sS..HhH..", "......sS...HhH..", ".....sS....HhH..", "....sS......H...",
            "...sS...........", "..sS............", ".sS.............", ".S..............", "................",
            "................"},
        { // machado
            "................", ".........HH.....", "........HhhH....", ".......HhhhhH...", "......HhhhhsH...",
            "......HhhhsS....", ".......HHsS.H...", "........sS......", ".......sS.......", "......sS........",
            ".....sS.........", "....sS..........", "...sS...........", "..sS............", "..S.............",
            "................"},
        { // pá
            "................", "...........HHH..", "..........HhhhH.", ".........HhhhhH.", ".........HhhhhH.",
            ".........sHhhH..", "........sSHHH...", ".......sS.......", "......sS........", ".....sS.........",
            "....sS..........", "...sS...........", "..sS............", ".sS.............", ".S..............",
            "................"},
        { // espada
            "................", ".............HH.", "............HhH.", "...........HhH..", "..........HhH...",
            ".........HhH....", "........HhH.....", "..S....HhH......", "..sS..HhH.......", "...sSHhH........",
            "....sSH.........", "...sSsS.........", "..sS..sS........", ".sS....S........", ".S..............",
            "................"}};
    private static final String[] STICK_ART = {
            "................", "................", "............sS..", "...........sS...", "..........sS....",
            ".........sS.....", "........sS......", ".......sS.......", "......sS........", ".....sS.........",
            "....sS..........", "...sS...........", "..sS............", "................", "................",
            "................"};
    private static final int[] TIER_COLOR = {rgb(176, 138, 84), rgb(150, 152, 156), rgb(226, 226, 232), rgb(86, 226, 214)};

    private static Gen art(String[] rows, int head) {
        return (x, y) -> switch (rows[y].charAt(x)) {
            case 'h' -> head;
            case 'H' -> mul(head, 0.62f);
            case 's' -> rgb(132, 96, 56);
            case 'S' -> rgb(88, 62, 36);
            default -> 0;
        };
    }

    Atlas() {
        tile(STONE, Atlas::stone);
        tile(GRASS_TOP, Atlas::grass);
        tile(GRASS_SIDE, (x, y) -> y < 3 + hash(x, 0, 20) % 3 ? grass(x, y) : dirt(x, y));
        tile(DIRT, Atlas::dirt);
        tile(COBBLE, Atlas::cobble);
        tile(PLANKS, Atlas::planks);
        tile(LOG_SIDE, logSide(104, 80, 50, 21));
        tile(LOG_TOP, (x, y) -> {
            float d = (float) Math.sqrt((x - 7.5f) * (x - 7.5f) + (y - 7.5f) * (y - 7.5f));
            if (d > 6.8f) return mul(rgb(104, 80, 50), 0.9f + 0.1f * rnd(x, y, 22));
            return mul(rgb(188, 152, 96), ((int) d) % 2 == 0 ? 1f : 0.82f);
        });
        tile(LEAVES, leaves(62, 140, 48, 23));
        tile(SAND, Atlas::sand);
        tile(GRAVEL, (x, y) -> mul(rgb(136, 128, 124), 0.7f + 0.5f * rnd(x / 2, (y + x / 2) / 2, 24) + 0.1f * rnd(x, y, 25)));
        tile(WATER, (x, y) -> rgba(40 + (int) (20 * rnd(x / 4, (y + x) / 4, 26)), 104, 214, 168));
        tile(GLASS, (x, y) -> {
            if (x == 0 || y == 0 || x == 15 || y == 15) return rgb(214, 236, 242);
            if ((x + y == 9 || x + y == 10) && x > 2 && x < 8) return rgba(255, 255, 255, 255);
            return 0;
        });
        tile(COAL_ORE, ore(36, 36, 40, 27));
        tile(IRON_ORE, ore(214, 164, 128, 29));
        tile(GOLD_ORE, ore(248, 210, 60, 31));
        tile(GEM_ORE, ore(86, 226, 214, 33));
        tile(BEDROCK, (x, y) -> mul(rgb(70, 70, 76), 0.5f + 0.9f * rnd(x / 2, y / 2, 35) * rnd(x, y, 36)));
        tile(SNOW, Atlas::snow);
        tile(SNOW_GRASS_SIDE, (x, y) -> y < 3 + hash(x, 0, 37) % 3 ? snow(x, y) : dirt(x, y));
        tile(ICE, (x, y) -> rgba(150 + (int) (30 * rnd(x, y, 38)), 200, 248, (x + y) % 9 == 0 ? 235 : 190));
        tile(CACTUS_SIDE, (x, y) -> {
            if (x % 4 == 1 && y % 3 == 1) return rgb(226, 232, 190);
            return mul(rgb(64, 142, 58), x % 4 == 3 ? 0.78f : 0.95f + 0.1f * rnd(x, y, 39));
        });
        tile(CACTUS_TOP, (x, y) -> mul(rgb(84, 160, 70), (x == 0 || y == 0 || x == 15 || y == 15) ? 0.7f : 0.92f + 0.14f * rnd(x, y, 41)));
        tile(SANDSTONE, (x, y) -> mul(sand(x, y), y % 5 == 4 ? 0.82f : y < 2 ? 1.05f : 0.94f));
        tile(STONE_BRICKS, (x, y) -> {
            int sx = (x + ((y / 8) % 2) * 8) % 16;
            if (y % 8 == 7 || sx == 15) return rgb(86, 88, 92);
            return mul(stone(x, y), y % 8 == 0 || sx == 0 ? 1.12f : 1f);
        });
        tile(WORKBENCH_TOP, (x, y) -> {
            if (x == 0 || y == 0 || x == 15 || y == 15) return rgb(92, 66, 38);
            if (x == 5 || x == 10 || y == 5 || y == 10) return mul(wood(x, y, 42), 0.7f);
            return wood(x, y, 42);
        });
        tile(WORKBENCH_SIDE, (x, y) -> {
            if (x == 0 || x == 15 || y < 3) return rgb(92, 66, 38);
            if (x > 3 && x < 12 && y > 5 && y < 13) return (x + y) % 2 == 0 ? rgb(126, 96, 58) : rgb(112, 84, 50);
            return planks(x, y);
        });
        Gen furnaceSide = (x, y) -> (x == 0 || y == 0 || x == 15 || y == 15) ? rgb(74, 76, 80) : mul(cobble(x, y), 0.92f);
        tile(FURNACE_SIDE, furnaceSide);
        tile(FURNACE_FRONT, (x, y) -> (x > 3 && x < 12 && y > 7 && y < 14) ? rgb(26, 24, 26)
                : (x > 2 && x < 13 && y > 2 && y < 6) ? rgb(52, 52, 56) : furnaceSide.at(x, y));
        tile(FURNACE_FRONT_LIT, (x, y) -> (x > 3 && x < 12 && y > 7 && y < 14)
                ? rgb(255, 150 + (int) (90 * rnd(x, y, 43)) - (13 - y) * 14, 30)
                : (x > 2 && x < 13 && y > 2 && y < 6) ? rgb(96, 60, 44) : furnaceSide.at(x, y));
        tile(TORCH, (x, y) -> {
            if ((x == 7 || x == 8) && y >= 7) return x == 7 ? rgb(132, 96, 56) : rgb(96, 68, 40);
            float dx = x - 7.5f, dy = y - 4.5f;
            if (dx * dx + dy * dy * 0.6f < 3.2f) return dx * dx + dy * dy < 1.2f ? rgb(255, 246, 170) : rgb(255, 168, 40);
            return 0;
        });
        tile(TALL_GRASS, (x, y) -> {
            int h = 5 + hash(x, 0, 44) % 10;
            if (x % 2 == hash(x / 2, 1, 45) % 2 && y >= 16 - h) return mul(rgb(84, 156, 58), 0.75f + 0.4f * (16 - y) / 16f);
            return 0;
        });
        tile(FLOWER_RED, flower(220, 50, 70));
        tile(FLOWER_YELLOW, flower(240, 150, 40));
        tile(PINE_LOG_SIDE, logSide(72, 52, 38, 46));
        tile(PINE_LEAVES, leaves(38, 98, 70, 47));
        tile(IRON_BLOCK, metal(214, 214, 220));
        tile(GOLD_BLOCK, metal(246, 204, 62));
        tile(GEM_BLOCK, metal(92, 220, 208));
        tile(LAMP, (x, y) -> {
            if (x == 0 || y == 0 || x == 15 || y == 15 || x == 7 || x == 8 || y == 7 || y == 8) return rgb(110, 78, 44);
            return grain(rgb(255, 226, 132), x, y, 48, 0.08f);
        });
        tile(DEAD_BUSH, (x, y) -> {
            boolean b = (x == 7 || x == 8) && y > 8 || Math.abs(x - 7.5f) == (12.5f - y) && y > 3 && y < 13
                    || Math.abs(x - 7.5f) + 3 == (11.5f - y) * 2 && y > 5;
            return b ? rgb(120, 88, 50) : 0;
        });
        tile(WHITE, (x, y) -> 0xffffffff);
        for (int i = 0; i < 4; i++) {
            final int st = i;
            tile(CRACK + i, (x, y) -> {
                boolean line = rnd(x / 2, y / 2, 50) < 0.16f * (st + 1) && rnd(x, y, 51) < 0.75f;
                return line ? rgba(10, 10, 12, 170) : 0;
            });
        }
        tile(SUN, (x, y) -> {
            float d = (x - 7.5f) * (x - 7.5f) + (y - 7.5f) * (y - 7.5f);
            return d < 30 ? rgb(255, 250, 215) : d < 52 ? rgba(255, 224, 120, 255) : 0;
        });
        tile(MOON, (x, y) -> {
            float d = (x - 7.5f) * (x - 7.5f) + (y - 7.5f) * (y - 7.5f);
            if (d >= 42) return 0;
            return rnd(x / 3, y / 3, 52) > 0.72f ? rgb(176, 184, 204) : rgb(226, 232, 244);
        });
        tile(I_STICK, art(STICK_ART, 0));
        tile(I_COAL, disc(7.5f, 8f, 5.5f, 5f, 44, 44, 50));
        tile(I_IRON, ingot(220, 220, 226));
        tile(I_GOLD, ingot(248, 208, 64));
        tile(I_GEM, (x, y) -> {
            float d = Math.abs(x - 7.5f) + Math.abs(y - 7.5f) * 0.8f;
            if (d > 6) return 0;
            return mul(rgb(86, 226, 214), d > 4.8f ? 0.6f : (x < 8 && y < 8) ? 1.25f : (x + y) % 5 == 0 ? 1.1f : 0.92f);
        });
        tile(I_RAW_MEAT, disc(7.5f, 8f, 6f, 4.5f, 226, 96, 104));
        tile(I_COOKED_MEAT, disc(7.5f, 8f, 6f, 4.5f, 150, 92, 52));
        Gen fruit = disc(7.5f, 9f, 5f, 5f, 214, 48, 92);
        tile(I_FRUIT, (x, y) -> {
            if (y < 5 && y > 1 && x == 8) return rgb(96, 68, 40);
            if (y == 3 && (x == 9 || x == 10) || y == 2 && x == 10) return rgb(80, 160, 60);
            return fruit.at(x, y);
        });
        for (int k = 0; k < 4; k++)
            for (int t = 0; t < 4; t++) tile(I_TOOLS + k * 4 + t, art(TOOL_ART[k], TIER_COLOR[t]));
        for (int m = 0; m < 3; m++) { tile(HEART + m, heart(m)); tile(FOOD + m, food(m)); }
        tile(BUBBLE, (x, y) -> {
            float d = (x - 7.5f) * (x - 7.5f) + (y - 7.5f) * (y - 7.5f);
            return d < 36 && d > 20 ? rgb(170, 214, 255) : (x == 5 && y == 5) ? rgb(255, 255, 255) : 0;
        });
        tile(FUR, (x, y) -> { int v = 205 + (int) (50 * rnd(x, y, 53) * rnd(x / 2, y / 2, 54)); return rgb(v, v, v); });
    }

    ByteBuffer toBuffer() {
        ByteBuffer b = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        for (int c : px) b.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
        b.flip();
        return b;
    }

    static float u0(int tile) { return ((tile % GRID) * TILE + 0.02f) / SIZE; }
    static float u1(int tile) { return ((tile % GRID) * TILE + TILE - 0.02f) / SIZE; }
    static float v0(int tile) { return ((tile / GRID) * TILE + 0.02f) / SIZE; }
    static float v1(int tile) { return ((tile / GRID) * TILE + TILE - 0.02f) / SIZE; }
}
