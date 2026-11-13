package terralume;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Mundo: chunks carregados, iluminação por inundação, edição de blocos, entidades e persistência. */
final class World {
    static final int[] DX = {1, -1, 0, 0, 0, 0}, DY = {0, 0, 1, -1, 0, 0}, DZ = {0, 0, 0, 0, 1, -1};
    static final int DOWN = 3;

    final WorldGen gen;
    final Path dir;
    final HashMap<Long, Chunk> chunks = new HashMap<>();
    final HashMap<Long, Furnace> furnaces = new HashMap<>();
    final ArrayList<Entity> entities = new ArrayList<>();
    long time = 1500; // ticks; um dia = 24000

    private final ExecutorService pool;
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Chunk> ready = new ConcurrentLinkedQueue<>();
    private Chunk last;
    private final LongQueue skyQ = new LongQueue(), blkQ = new LongQueue(), remQ = new LongQueue();
    final Random rnd = new Random();

    World(long seed, Path dir) {
        this.gen = new WorldGen(seed);
        this.dir = dir;
        int n = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        pool = Executors.newFixedThreadPool(n, r -> { Thread t = new Thread(r, "gerador"); t.setDaemon(true); return t; });
    }

    static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xffffffffL); }

    Chunk chunk(int cx, int cz) {
        Chunk l = last;
        if (l != null && l.cx == cx && l.cz == cz) return l;
        Chunk c = chunks.get(key(cx, cz));
        if (c != null) last = c;
        return c;
    }

    int block(int x, int y, int z) {
        if (y < 0 || y >= Chunk.H) return Block.AIR;
        Chunk c = chunk(x >> 4, z >> 4);
        return c == null ? Block.AIR : c.blocks[Chunk.idx(x & 15, y, z & 15)] & 0xff;
    }

    boolean loaded(int x, int z) { return chunk(x >> 4, z >> 4) != null; }

    boolean solid(int x, int y, int z) { return Block.REG[block(x, y, z)].solid; }

    int sky(int x, int y, int z) {
        if (y >= Chunk.H) return 15;
        if (y < 0) return 0;
        Chunk c = chunk(x >> 4, z >> 4);
        return c == null ? 15 : (c.light[Chunk.idx(x & 15, y, z & 15)] >> 4) & 15;
    }

    int blockLight(int x, int y, int z) {
        if (y < 0 || y >= Chunk.H) return 0;
        Chunk c = chunk(x >> 4, z >> 4);
        return c == null ? 0 : c.light[Chunk.idx(x & 15, y, z & 15)] & 15;
    }

    /** Fração do dia em [0,1): 0 = nascer do sol, 0.25 = meio-dia, 0.5 = pôr do sol. */
    float dayFraction() { return (time % 24000) / 24000f; }

    float daylight() {
        float s = (float) Math.sin(dayFraction() * Math.PI * 2);
        return Math.max(0.27f, Math.min(1f, s * 3f + 0.55f));
    }

    boolean isNight() { return Math.sin(dayFraction() * Math.PI * 2) < -0.1; }

    /** Nível de luz efetivo (0..15) na posição. */
    int lightLevel(int x, int y, int z) {
        return Math.max(Math.round(sky(x, y, z) * daylight()), blockLight(x, y, z));
    }

    // ------------------------------------------------------------------ streaming

    void request(int cx, int cz) {
        long k = key(cx, cz);
        if (chunks.containsKey(k) || !pending.add(k)) return;
        pool.submit(() -> {
            try {
                Chunk c = load(cx, cz);
                if (c == null) c = gen.generate(cx, cz);
                ready.add(c);
            } catch (Throwable t) {
                t.printStackTrace();
                pending.remove(k);
            }
        });
    }

    /** Integra chunks prontos e ilumina os novos. Retorna quantos entraram. */
    int integrate(int max) {
        int n = 0;
        Chunk c;
        while (n < max && (c = ready.poll()) != null) {
            long k = key(c.cx, c.cz);
            pending.remove(k);
            chunks.put(k, c);
            lightChunk(c);
            n++;
        }
        return n;
    }

    int pendingCount() { return pending.size(); }

    void unloadFar(int pcx, int pcz, int dist) {
        Iterator<Chunk> it = chunks.values().iterator();
        while (it.hasNext()) {
            Chunk c = it.next();
            if (Math.abs(c.cx - pcx) > dist || Math.abs(c.cz - pcz) > dist) {
                if (c.modified) save(c);
                c.free();
                if (last == c) last = null;
                it.remove();
            }
        }
    }

    boolean neighborsLit(Chunk c) {
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                Chunk n = chunk(c.cx + dx, c.cz + dz);
                if (n == null || !n.lit) return false;
            }
        return true;
    }

    // ------------------------------------------------------------------ iluminação

    private static final int OFF = 1 << 25;
    private static long pack(int x, int y, int z, int v) {
        return ((long) (x + OFF) << 38) | ((long) (z + OFF) << 12) | ((long) y << 4) | v;
    }
    private static int px(long p) { return (int) (p >>> 38) - OFF; }
    private static int pz(long p) { return (int) ((p >>> 12) & 0x3ffffff) - OFF; }
    private static int py(long p) { return (int) ((p >>> 4) & 0xff); }

    private void touch(Chunk c, int lx, int lz) {
        c.dirty = true;
        if (lx == 0) mark(c.cx - 1, c.cz); else if (lx == 15) mark(c.cx + 1, c.cz);
        if (lz == 0) mark(c.cx, c.cz - 1); else if (lz == 15) mark(c.cx, c.cz + 1);
    }

    private void mark(int cx, int cz) {
        Chunk n = chunks.get(key(cx, cz));
        if (n != null) n.dirty = true;
    }

    private void lightChunk(Chunk c) {
        int bx = c.cx << 4, bz = c.cz << 4;
        int[] top = new int[256];
        int max = 0;
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                int y = Chunk.H - 1;
                while (y >= 0 && Block.REG[c.blocks[Chunk.idx(x, y, z)] & 0xff].opacity == 0) {
                    c.light[Chunk.idx(x, y, z)] |= (byte) 0xF0;
                    y--;
                }
                top[z * 16 + x] = y;
                max = Math.max(max, y);
            }
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                boolean border = x == 0 || x == 15 || z == 0 || z == 15;
                int hi = border ? Chunk.H - 1 : Math.min(Chunk.H - 1, max + 1);
                for (int y = top[z * 16 + x] + 1; y <= hi; y++) skyQ.add(pack(bx + x, y, bz + z, 0));
            }
        for (int i = 0; i < Chunk.VOL; i++) {
            int l = Block.REG[c.blocks[i] & 0xff].light;
            if (l > 0) {
                c.light[i] = (byte) ((c.light[i] & 0xF0) | l);
                blkQ.add(pack(bx + (i & 15), i >> 8, bz + ((i >> 4) & 15), 0));
            }
        }
        // Luz que vem dos vizinhos já iluminados.
        for (int d = 0; d < 4; d++) {
            int ndx = d == 0 ? 1 : d == 1 ? -1 : 0, ndz = d == 2 ? 1 : d == 3 ? -1 : 0;
            Chunk n = chunks.get(key(c.cx + ndx, c.cz + ndz));
            if (n == null || !n.lit) continue;
            for (int y = 0; y < Chunk.H; y++)
                for (int t = 0; t < 16; t++) {
                    int lx = ndx == 1 ? 0 : ndx == -1 ? 15 : t, lz = ndz == 1 ? 0 : ndz == -1 ? 15 : t;
                    int l = n.light[Chunk.idx(lx, y, lz)];
                    if ((l & 0xF0) != 0) skyQ.add(pack((n.cx << 4) + lx, y, (n.cz << 4) + lz, 0));
                    if ((l & 0x0F) != 0) blkQ.add(pack((n.cx << 4) + lx, y, (n.cz << 4) + lz, 0));
                }
        }
        propagate(skyQ, true);
        propagate(blkQ, false);
        c.lit = true;
        c.dirty = true;
    }

    private void propagate(LongQueue q, boolean isSky) {
        while (!q.isEmpty()) {
            long p = q.poll();
            int x = px(p), y = py(p), z = pz(p);
            int L = isSky ? sky(x, y, z) : blockLight(x, y, z);
            if (L <= 1) continue;
            for (int d = 0; d < 6; d++) {
                int ny = y + DY[d];
                if (ny < 0 || ny >= Chunk.H) continue;
                int nx = x + DX[d], nz = z + DZ[d];
                Chunk c = chunk(nx >> 4, nz >> 4);
                if (c == null) continue;
                int i = Chunk.idx(nx & 15, ny, nz & 15);
                int op = Block.REG[c.blocks[i] & 0xff].opacity;
                if (op >= 15) continue;
                int nl = (isSky && d == DOWN && L == 15 && op == 0) ? 15 : L - Math.max(1, op);
                int cur = isSky ? (c.light[i] >> 4) & 15 : c.light[i] & 15;
                if (nl > cur) {
                    c.light[i] = (byte) (isSky ? (c.light[i] & 0x0F) | (nl << 4) : (c.light[i] & 0xF0) | nl);
                    touch(c, nx & 15, nz & 15);
                    q.add(pack(nx, ny, nz, 0));
                }
            }
        }
        q.clear();
    }

    private void remove(int x, int y, int z, boolean isSky, LongQueue pq) {
        Chunk c0 = chunk(x >> 4, z >> 4);
        if (c0 == null) return;
        int i0 = Chunk.idx(x & 15, y, z & 15);
        int old = isSky ? (c0.light[i0] >> 4) & 15 : c0.light[i0] & 15;
        if (old == 0) return;
        c0.light[i0] = (byte) (isSky ? c0.light[i0] & 0x0F : c0.light[i0] & 0xF0);
        touch(c0, x & 15, z & 15);
        remQ.add(pack(x, y, z, old));
        while (!remQ.isEmpty()) {
            long p = remQ.poll();
            int qx = px(p), qy = py(p), qz = pz(p), val = (int) (p & 15);
            for (int d = 0; d < 6; d++) {
                int ny = qy + DY[d];
                if (ny < 0 || ny >= Chunk.H) continue;
                int nx = qx + DX[d], nz = qz + DZ[d];
                Chunk c = chunk(nx >> 4, nz >> 4);
                if (c == null) continue;
                int i = Chunk.idx(nx & 15, ny, nz & 15);
                int cur = isSky ? (c.light[i] >> 4) & 15 : c.light[i] & 15;
                if (cur == 0) continue;
                if (cur < val || (isSky && d == DOWN && val == 15)) {
                    c.light[i] = (byte) (isSky ? c.light[i] & 0x0F : c.light[i] & 0xF0);
                    touch(c, nx & 15, nz & 15);
                    remQ.add(pack(nx, ny, nz, cur));
                } else pq.add(pack(nx, ny, nz, 0));
            }
        }
        remQ.clear();
    }

    // ------------------------------------------------------------------ edição

    /** Troca um bloco, atualiza a luz e dispara as reações dos vizinhos (gravidade, plantas sem apoio). */
    void setBlock(int x, int y, int z, int id) {
        if (y < 0 || y >= Chunk.H) return;
        Chunk c = chunk(x >> 4, z >> 4);
        if (c == null) return;
        int i = Chunk.idx(x & 15, y, z & 15);
        int old = c.blocks[i] & 0xff;
        if (old == id) return;
        c.blocks[i] = (byte) id;
        c.modified = true;
        touch(c, x & 15, z & 15);
        if ((old == Block.FURNACE || old == Block.FURNACE_LIT) && id != Block.FURNACE && id != Block.FURNACE_LIT) {
            Furnace f = furnaces.remove(posKey(x, y, z));
            if (f != null) for (ItemStack s : new ItemStack[]{f.input, f.fuel, f.output}) if (s != null) dropItem(x + 0.5, y + 0.5, z + 0.5, s);
        }
        remove(x, y, z, true, skyQ);
        remove(x, y, z, false, blkQ);
        Block nb = Block.REG[id];
        if (nb.light > 0) {
            c.light[i] = (byte) ((c.light[i] & 0xF0) | nb.light);
            blkQ.add(pack(x, y, z, 0));
        }
        if (y == Chunk.H - 1 && nb.opacity == 0) {
            c.light[i] |= (byte) 0xF0;
            skyQ.add(pack(x, y, z, 0));
        }
        for (int d = 0; d < 6; d++) {
            int ny = y + DY[d];
            if (ny < 0 || ny >= Chunk.H) continue;
            long p = pack(x + DX[d], ny, z + DZ[d], 0);
            skyQ.add(p); blkQ.add(p);
        }
        propagate(skyQ, true);
        propagate(blkQ, false);

        // Reações em cadeia.
        int above = block(x, y + 1, z);
        if (!Block.REG[id].solid && Block.REG[above].needsGround) breakBlock(x, y + 1, z, true);
        else if (!Block.REG[id].solid && Block.REG[above].falls) fall(x, y + 1, z);
        if (Block.REG[id].falls) fall(x, y, z);
        if (id == Block.AIR && y <= WorldGen.SEA && waterNear(x, y, z)) waterQ.add(pack(x, y, z, 0));
    }

    private final LongQueue waterQ = new LongQueue();

    private boolean waterNear(int x, int y, int z) {
        return block(x, y + 1, z) == Block.WATER || block(x + 1, y, z) == Block.WATER || block(x - 1, y, z) == Block.WATER
                || block(x, y, z + 1) == Block.WATER || block(x, y, z - 1) == Block.WATER;
    }

    /** Água abaixo do nível do mar inunda cavidades abertas, alguns blocos por tick. */
    void tickWater() {
        for (int n = 0; n < 48 && !waterQ.isEmpty(); n++) {
            long p = waterQ.poll();
            int x = px(p), y = py(p), z = pz(p);
            int cur = block(x, y, z);
            if (!loaded(x, z) || cur == Block.WATER || Block.REG[cur].solid || !waterNear(x, y, z)) continue;
            if (cur != Block.AIR) breakBlock(x, y, z, true);
            setBlock(x, y, z, Block.WATER);
            for (int d = 0; d < 6; d++) {
                if (d == 2) continue;
                int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
                if (ny > 0 && !Block.REG[block(nx, ny, nz)].solid && block(nx, ny, nz) != Block.WATER) waterQ.add(pack(nx, ny, nz, 0));
            }
        }
        if (waterQ.isEmpty()) waterQ.clear();
    }

    private void fall(int x, int y, int z) {
        int id = block(x, y, z);
        int ny = y;
        while (ny > 1 && !Block.REG[block(x, ny - 1, z)].solid) ny--;
        if (ny == y) return;
        setBlock(x, y, z, Block.AIR);
        int there = block(x, ny, z);
        if (there != Block.AIR && !Block.isLiquid(there)) breakBlock(x, ny, z, true);
        setBlock(x, ny, z, id);
    }

    /** Remove o bloco e (opcionalmente) solta o item correspondente. */
    void breakBlock(int x, int y, int z, boolean drop) {
        int id = block(x, y, z);
        if (id == Block.AIR) return;
        Block b = Block.REG[id];
        setBlock(x, y, z, Block.AIR);
        if (!drop) return;
        if (b.drop >= 0 && b.dropCount > 0) dropItem(x + 0.5, y + 0.3, z + 0.5, new ItemStack(b.drop, b.dropCount));
        if (id == Block.LEAVES && rnd.nextInt(10) == 0) dropItem(x + 0.5, y + 0.3, z + 0.5, new ItemStack(Item.FRUIT, 1));
        if ((id == Block.LEAVES || id == Block.PINE_LEAVES) && rnd.nextInt(8) == 0) dropItem(x + 0.5, y + 0.3, z + 0.5, new ItemStack(Item.STICK, 1));
    }

    void dropItem(double x, double y, double z, ItemStack s) {
        ItemEntity e = new ItemEntity(s);
        e.x = x; e.y = y; e.z = z;
        e.vx = (rnd.nextDouble() - 0.5) * 2; e.vy = 3; e.vz = (rnd.nextDouble() - 0.5) * 2;
        entities.add(e);
    }

    static long posKey(int x, int y, int z) { return pack(x, y, z, 0); }

    Furnace furnaceAt(int x, int y, int z) { return furnaces.computeIfAbsent(posKey(x, y, z), k -> new Furnace()); }

    void tickFurnaces() {
        for (Map.Entry<Long, Furnace> e : furnaces.entrySet()) {
            long p = e.getKey();
            int x = px(p), y = py(p), z = pz(p);
            boolean lit = e.getValue().tick();
            int cur = block(x, y, z);
            if (cur == Block.FURNACE && lit) relight(x, y, z, Block.FURNACE_LIT);
            else if (cur == Block.FURNACE_LIT && !lit) relight(x, y, z, Block.FURNACE);
        }
    }

    private void relight(int x, int y, int z, int id) {
        Furnace f = furnaces.get(posKey(x, y, z)); // setBlock não deve soltar o conteúdo aqui
        setBlock(x, y, z, id);
        if (f != null) furnaces.put(posKey(x, y, z), f);
    }

    /** DDA voxel: devolve {x,y,z,nx,ny,nz} do primeiro bloco selecionável, ou null. */
    int[] raycast(double ox, double oy, double oz, double dx, double dy, double dz, double max) {
        int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? 1e30 : Math.abs(1 / dx), tdy = dy == 0 ? 1e30 : Math.abs(1 / dy), tdz = dz == 0 ? 1e30 : Math.abs(1 / dz);
        double tx = dx == 0 ? 1e30 : (dx > 0 ? x + 1 - ox : ox - x) * tdx;
        double ty = dy == 0 ? 1e30 : (dy > 0 ? y + 1 - oy : oy - y) * tdy;
        double tz = dz == 0 ? 1e30 : (dz > 0 ? z + 1 - oz : oz - z) * tdz;
        int nx = 0, ny = 0, nz = 0;
        double t = 0;
        while (t <= max) {
            int id = block(x, y, z);
            if (id != Block.AIR && !Block.isLiquid(id)) return new int[]{x, y, z, nx, ny, nz, (int) (t * 1000)};
            if (tx < ty && tx < tz) { x += sx; t = tx; tx += tdx; nx = -sx; ny = 0; nz = 0; }
            else if (ty < tz) { y += sy; t = ty; ty += tdy; nx = 0; ny = -sy; nz = 0; }
            else { z += sz; t = tz; tz += tdz; nx = 0; ny = 0; nz = -sz; }
        }
        return null;
    }

    // ------------------------------------------------------------------ persistência

    private Path file(int cx, int cz) { return dir.resolve("c." + cx + "." + cz + ".bin"); }

    private Chunk load(int cx, int cz) {
        Path f = file(cx, cz);
        if (!Files.exists(f)) return null;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(f))) {
            Chunk c = new Chunk(cx, cz);
            if (in.readNBytes(c.blocks, 0, Chunk.VOL) != Chunk.VOL) return null;
            return c;
        } catch (IOException e) {
            return null;
        }
    }

    void save(Chunk c) {
        try {
            Files.createDirectories(dir);
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file(c.cx, c.cz)))) {
                out.write(c.blocks);
            }
            c.modified = false;
        } catch (IOException e) {
            System.err.println("Falha ao salvar chunk: " + e);
        }
    }

    void saveAll() {
        for (Chunk c : chunks.values()) if (c.modified) save(c);
    }

    void shutdown() { pool.shutdownNow(); }
}

/** Fila de longs sem boxing para as inundações de luz. */
final class LongQueue {
    private long[] a = new long[1 << 14];
    private int head, tail;

    void add(long v) {
        if (tail == a.length) {
            if (head > a.length / 2) { System.arraycopy(a, head, a, 0, tail - head); tail -= head; head = 0; }
            else a = Arrays.copyOf(a, a.length * 2);
        }
        a[tail++] = v;
    }
    long poll() { return a[head++]; }
    boolean isEmpty() { return head == tail; }
    void clear() { head = tail = 0; }
}

/** Estado de uma fornalha: entrada, combustível, saída e temporizadores (em ticks). */
final class Furnace {
    static final int COOK_TIME = 160;
    ItemStack input, fuel, output;
    int burn, burnMax, cook;

    static int smelt(int id) {
        return switch (id) {
            case Block.IRON_ORE -> Item.IRON_INGOT;
            case Block.GOLD_ORE -> Item.GOLD_INGOT;
            case Block.SAND -> Block.GLASS;
            case Block.COBBLE -> Block.STONE;
            case Block.LOG, Block.PINE_LOG -> Item.COAL;
            case Item.RAW_MEAT -> Item.COOKED_MEAT;
            default -> -1;
        };
    }

    static int fuelTime(int id) {
        return switch (id) {
            case Item.COAL -> 1600;
            case Block.LOG, Block.PINE_LOG, Block.PLANKS, Block.WORKBENCH -> 300;
            case Item.STICK -> 100;
            default -> 0;
        };
    }

    private boolean canCook() {
        if (input == null) return false;
        int out = smelt(input.id);
        if (out < 0) return false;
        return output == null || (output.id == out && output.count < Item.REG[out].maxStack);
    }

    /** Avança um tick; devolve true se está acesa. */
    boolean tick() {
        if (burn > 0) burn--;
        if (canCook()) {
            if (burn == 0 && fuel != null && fuelTime(fuel.id) > 0) {
                burn = burnMax = fuelTime(fuel.id);
                if (--fuel.count <= 0) fuel = null;
            }
            if (burn > 0) {
                if (++cook >= COOK_TIME) {
                    cook = 0;
                    int out = smelt(input.id);
                    if (output == null) output = new ItemStack(out, 1); else output.count++;
                    if (--input.count <= 0) input = null;
                }
            } else cook = Math.max(0, cook - 2);
        } else cook = 0;
        return burn > 0;
    }
}
