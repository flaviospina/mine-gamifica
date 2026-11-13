package terralume;

import java.util.Random;

/** Geração procedural: relevo, biomas, cavernas, minérios, árvores e plantas. Função pura da semente. */
final class WorldGen {
    static final int SEA = 56;
    static final int PLAINS = 0, FOREST = 1, DESERT = 2, TUNDRA = 3, MOUNTAIN = 4;
    static final String[] BIOME_NAME = {"Planície", "Floresta", "Deserto", "Tundra", "Montanha"};

    final long seed;
    private final Noise cont, hill, detail, temp, moist, cave1, cave2;

    WorldGen(long seed) {
        this.seed = seed;
        cont = new Noise(seed); hill = new Noise(seed + 1); detail = new Noise(seed + 2);
        temp = new Noise(seed + 3); moist = new Noise(seed + 4); cave1 = new Noise(seed + 5); cave2 = new Noise(seed + 6);
    }

    /** Altura (y) do bloco sólido mais alto da coluna. */
    int height(int x, int z) {
        double c = cont.fbm2(x / 420.0, z / 420.0, 4);
        double m = Math.max(0, hill.fbm2(x / 300.0, z / 300.0, 3) - 0.12);
        double d = detail.fbm2(x / 70.0, z / 70.0, 4);
        double h = SEA + 5 + c * 26 + d * (4 + m * 30) + m * m * 150;
        return (int) Math.max(8, Math.min(Chunk.H - 12, h));
    }

    int biome(int x, int z, int h) {
        if (h > 86) return MOUNTAIN;
        double t = temp.fbm2(x / 520.0, z / 520.0, 3), w = moist.fbm2(x / 420.0, z / 420.0, 3);
        if (t > 0.2 && w < 0.12) return DESERT;
        if (t < -0.22) return TUNDRA;
        if (w > 0.06) return FOREST;
        return PLAINS;
    }

    boolean carved(int x, int y, int z, int h) {
        if (y < 5 || y > h) return false;
        if (h <= SEA + 3 && y > h - 6) return false; // não fura o fundo de lagos e mares
        double a = cave1.n3(x / 46.0, y / 30.0, z / 46.0), b = cave2.n3(x / 46.0, y / 30.0, z / 46.0);
        return a * a + b * b < 0.0075;
    }

    private static int hash(long seed, int x, int z, int s) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ (s * 0x165667B19E3779F9L);
        h ^= h >>> 31; h *= 0x7FB5D329728EA185L; h ^= h >>> 27; h *= 0x81DADEF4BC2DD44DL; h ^= h >>> 33;
        return (int) (h & 0x7fffffff);
    }

    Chunk generate(int cx, int cz) {
        Chunk c = new Chunk(cx, cz);
        final int M = 3, S = 16 + 2 * M;
        int[] hs = new int[S * S], bs = new int[S * S];
        int bx = cx << 4, bz = cz << 4;
        for (int z = 0; z < S; z++)
            for (int x = 0; x < S; x++) {
                int wx = bx + x - M, wz = bz + z - M;
                int h = height(wx, wz);
                hs[z * S + x] = h; bs[z * S + x] = biome(wx, wz, h);
            }
        byte[] b = c.blocks;
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                int wx = bx + x, wz = bz + z;
                int h = hs[(z + M) * S + x + M], biome = bs[(z + M) * S + x + M];
                boolean shore = h <= SEA + 1;
                int topB, fill;
                if (shore) { topB = h < SEA - 9 ? Block.GRAVEL : Block.SAND; fill = Block.SAND; }
                else if (biome == DESERT) { topB = Block.SAND; fill = Block.SANDSTONE; }
                else if (biome == TUNDRA) { topB = Block.SNOW_GRASS; fill = Block.DIRT; }
                else if (biome == MOUNTAIN) { topB = h > 96 ? Block.SNOW : Block.STONE; fill = Block.STONE; }
                else { topB = Block.GRASS; fill = Block.DIRT; }
                for (int y = 0; y <= h; y++) {
                    int id;
                    if (y == 0) id = Block.BEDROCK;
                    else if (carved(wx, y, wz, h)) id = Block.AIR;
                    else if (y == h) id = topB;
                    else if (y >= h - 3) id = fill;
                    else id = Block.STONE;
                    b[Chunk.idx(x, y, z)] = (byte) id;
                }
                for (int y = h + 1; y <= SEA; y++)
                    b[Chunk.idx(x, y, z)] = (byte) (y == SEA && biome == TUNDRA ? Block.ICE : Block.WATER);
            }

        Random r = new Random(seed ^ (cx * 341873128712L) ^ (cz * 132897987541L));
        veins(c, r, Block.DIRT, 5, 5, 70, 22);
        veins(c, r, Block.GRAVEL, 4, 5, 70, 20);
        veins(c, r, Block.COAL_ORE, 14, 5, 100, 10);
        veins(c, r, Block.IRON_ORE, 10, 5, 62, 6);
        veins(c, r, Block.GOLD_ORE, 3, 5, 30, 5);
        veins(c, r, Block.GEM_ORE, 2, 5, 18, 4);

        // Árvores: decididas por hash da coluna, então atravessam fronteiras de chunk sem costura.
        for (int z = 0; z < S; z++)
            for (int x = 0; x < S; x++) {
                int wx = bx + x - M, wz = bz + z - M, h = hs[z * S + x], biome = bs[z * S + x];
                if (h <= SEA + 1 || carved(wx, h, wz, h)) continue;
                int k = hash(seed, wx, wz, 1);
                int chance = biome == FOREST ? 34 : biome == PLAINS ? 420 : biome == TUNDRA ? 70 : 0;
                if (chance == 0 || k % chance != 0) continue;
                if (biome == TUNDRA) pine(c, x - M, h + 1, z - M, 6 + (k >> 8) % 3);
                else oak(c, x - M, h + 1, z - M, 4 + (k >> 8) % 3, wx, wz);
            }

        // Plantas de superfície.
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                int h = hs[(z + M) * S + x + M], biome = bs[(z + M) * S + x + M];
                if (h <= SEA + 1 || h + 4 >= Chunk.H) continue;
                int ground = c.get(x, h, z);
                if (c.get(x, h + 1, z) != Block.AIR) continue;
                int k = hash(seed, bx + x, bz + z, 2) % 1000;
                if (ground == Block.GRASS) {
                    int density = biome == FOREST ? 90 : 140;
                    if (k < density) b[Chunk.idx(x, h + 1, z)] = (byte) Block.TALL_GRASS;
                    else if (k < density + 8) b[Chunk.idx(x, h + 1, z)] = (byte) Block.FLOWER_RED;
                    else if (k < density + 18) b[Chunk.idx(x, h + 1, z)] = (byte) Block.FLOWER_YELLOW;
                } else if (ground == Block.SAND && biome == DESERT) {
                    if (k < 7) for (int i = 1; i <= 1 + k % 3; i++) b[Chunk.idx(x, h + i, z)] = (byte) Block.CACTUS;
                    else if (k < 16) b[Chunk.idx(x, h + 1, z)] = (byte) Block.DEAD_BUSH;
                }
            }
        return c;
    }

    private static void veins(Chunk c, Random r, int ore, int tries, int minY, int maxY, int size) {
        for (int t = 0; t < tries; t++) {
            int x = r.nextInt(16), y = minY + r.nextInt(maxY - minY), z = r.nextInt(16);
            for (int i = 0; i < size; i++) {
                if (x >= 0 && x < 16 && z >= 0 && z < 16 && y > 0 && y < Chunk.H) {
                    int idx = Chunk.idx(x, y, z);
                    if (c.blocks[idx] == Block.STONE) c.blocks[idx] = (byte) ore;
                }
                switch (r.nextInt(3)) {
                    case 0 -> x += r.nextInt(3) - 1;
                    case 1 -> y += r.nextInt(3) - 1;
                    default -> z += r.nextInt(3) - 1;
                }
            }
        }
    }

    private static void put(Chunk c, int x, int y, int z, int id, boolean trunk) {
        if (x < 0 || x > 15 || z < 0 || z > 15 || y < 1 || y >= Chunk.H) return;
        int i = Chunk.idx(x, y, z), cur = c.blocks[i] & 0xff;
        if (cur == Block.AIR || (trunk && (cur == Block.LEAVES || cur == Block.PINE_LEAVES || !Block.REG[cur].solid && cur != Block.WATER)))
            c.blocks[i] = (byte) id;
    }

    private void oak(Chunk c, int x, int y, int z, int th, int wx, int wz) {
        int top = y + th - 1;
        for (int ly = top - 2; ly <= top + 1; ly++) {
            int rad = ly >= top ? 1 : 2;
            for (int dz = -rad; dz <= rad; dz++)
                for (int dx = -rad; dx <= rad; dx++) {
                    boolean corner = Math.abs(dx) == rad && Math.abs(dz) == rad;
                    if (corner && (ly == top + 1 || hash(seed, wx + dx, wz + dz, ly) % 2 == 0)) continue;
                    put(c, x + dx, ly, z + dz, Block.LEAVES, false);
                }
        }
        for (int i = 0; i < th; i++) put(c, x, y + i, z, Block.LOG, true);
    }

    private void pine(Chunk c, int x, int y, int z, int th) {
        int top = y + th - 1;
        for (int ly = y + 2; ly <= top; ly++) {
            int fromTop = top - ly;
            int rad = fromTop == 0 ? 0 : (fromTop % 2 == 1 ? 1 : 2);
            if (fromTop >= 5) rad = Math.min(rad + 1, 2);
            for (int dz = -rad; dz <= rad; dz++)
                for (int dx = -rad; dx <= rad; dx++) {
                    if (rad > 0 && Math.abs(dx) == rad && Math.abs(dz) == rad) continue;
                    put(c, x + dx, ly, z + dz, Block.PINE_LEAVES, false);
                }
        }
        put(c, x, top + 1, z, Block.PINE_LEAVES, false);
        for (int i = 0; i < th; i++) put(c, x, y + i, z, Block.PINE_LOG, true);
    }
}
