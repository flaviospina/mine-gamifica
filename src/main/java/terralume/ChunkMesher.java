package terralume;

/** Converte um chunk em triângulos: faces ocultas removidas, luz suave e oclusão de ambiente por vértice. */
final class ChunkMesher {
    private static final int PW = 18, PH = Chunk.H + 2;
    private static final byte[] pb = new byte[PW * PW * PH], pl = new byte[PW * PW * PH];
    static float[] opaque = new float[1 << 18], trans = new float[1 << 14];
    static int nOpaque, nTrans;

    // Cantos de cada face em ordem anti-horária vista de fora: +X, -X, +Y, -Y, +Z, -Z.
    static final int[][] CORN = {
            {1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1}, {0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0},
            {0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 1, 0}, {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1},
            {0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1}, {1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0}};
    static final float[] FACE_SHADE = {0.68f, 0.68f, 1f, 0.5f, 0.84f, 0.84f};
    private static final float[] AO = {0.42f, 0.62f, 0.82f, 1f};

    private static int pi(int x, int y, int z) { return ((y + 1) * PW + (z + 1)) * PW + (x + 1); }

    private static void put(boolean t, float x, float y, float z, float u, float v, float sky, float blk, float shade) {
        if (t) {
            if (nTrans + 8 > trans.length) trans = java.util.Arrays.copyOf(trans, trans.length * 2);
            float[] a = trans; int n = nTrans;
            a[n] = x; a[n + 1] = y; a[n + 2] = z; a[n + 3] = u; a[n + 4] = v; a[n + 5] = sky; a[n + 6] = blk; a[n + 7] = shade;
            nTrans += 8;
        } else {
            if (nOpaque + 8 > opaque.length) opaque = java.util.Arrays.copyOf(opaque, opaque.length * 2);
            float[] a = opaque; int n = nOpaque;
            a[n] = x; a[n + 1] = y; a[n + 2] = z; a[n + 3] = u; a[n + 4] = v; a[n + 5] = sky; a[n + 6] = blk; a[n + 7] = shade;
            nOpaque += 8;
        }
    }

    private static final float[] vx = new float[4], vy = new float[4], vz = new float[4], vu = new float[4], vv = new float[4],
            vs = new float[4], vb = new float[4], vh = new float[4];

    private static final int[] ORDER = {0, 1, 2, 0, 2, 3}, ORDER_FLIP = {1, 2, 3, 1, 3, 0};

    private static void emitQuad(boolean t, boolean flip, boolean twoSided) {
        int[] order = flip ? ORDER_FLIP : ORDER;
        for (int k : order) put(t, vx[k], vy[k], vz[k], vu[k], vv[k], vs[k], vb[k], vh[k]);
        if (twoSided) for (int i = 5; i >= 0; i--) { int k = order[i]; put(t, vx[k], vy[k], vz[k], vu[k], vv[k], vs[k], vb[k], vh[k]); }
    }

    /** Monta as malhas do chunk (resultado nos vetores estáticos). Requer os 8 vizinhos carregados. */
    static void build(World w, Chunk c) {
        nOpaque = nTrans = 0;
        Chunk[] nb = new Chunk[9];
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) nb[(dz + 1) * 3 + dx + 1] = w.chunk(c.cx + dx, c.cz + dz);
        for (int z = -1; z <= 16; z++)
            for (int x = -1; x <= 16; x++) {
                Chunk s = nb[((z >> 4) + 1) * 3 + (x >> 4) + 1];
                int lx = x & 15, lz = z & 15;
                pb[pi(x, -1, z)] = (byte) Block.BEDROCK; pl[pi(x, -1, z)] = 0;
                pb[pi(x, Chunk.H, z)] = 0; pl[pi(x, Chunk.H, z)] = (byte) 0xF0;
                for (int y = 0; y < Chunk.H; y++) {
                    int i = Chunk.idx(lx, y, lz), p = pi(x, y, z);
                    pb[p] = s.blocks[i]; pl[p] = s.light[i];
                }
            }
        Block[] R = Block.REG;
        for (int y = 0; y < Chunk.H; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int id = pb[pi(x, y, z)] & 0xff;
                    if (id == 0) continue;
                    Block b = R[id];
                    if (b.shape == Block.SHAPE_CROSS) { cross(b, x, y, z); continue; }
                    boolean liquid = b.shape == Block.SHAPE_LIQUID;
                    float topH = liquid && (pb[pi(x, y + 1, z)] & 0xff) != id ? 0.875f : 1f;
                    for (int f = 0; f < 6; f++) {
                        int nx = x + World.DX[f], ny = y + World.DY[f], nz = z + World.DZ[f];
                        int nid = pb[pi(nx, ny, nz)] & 0xff;
                        Block n = R[nid];
                        if (n.opaque || (nid == id && b.cullSame)) continue;
                        int tile = f == 2 ? b.top : f == 3 ? b.bottom : b.side;
                        float u0 = Atlas.u0(tile), u1 = Atlas.u1(tile), v0 = Atlas.v0(tile), v1 = Atlas.v1(tile);
                        int[] cn = CORN[f];
                        int axis = f >> 1;
                        int a0 = 0, a1 = 0, a2 = 0, a3 = 0;
                        for (int k = 0; k < 4; k++) {
                            int cx = cn[k * 3], cy = cn[k * 3 + 1], cz = cn[k * 3 + 2];
                            int sx = cx == 1 ? 1 : -1, sy = cy == 1 ? 1 : -1, sz = cz == 1 ? 1 : -1;
                            int p1, p2, p3;
                            if (axis == 0) { p1 = pi(nx, ny + sy, nz); p2 = pi(nx, ny, nz + sz); p3 = pi(nx, ny + sy, nz + sz); }
                            else if (axis == 1) { p1 = pi(nx + sx, ny, nz); p2 = pi(nx, ny, nz + sz); p3 = pi(nx + sx, ny, nz + sz); }
                            else { p1 = pi(nx + sx, ny, nz); p2 = pi(nx, ny + sy, nz); p3 = pi(nx + sx, ny + sy, nz); }
                            if (ny + sy < -1 || ny + sy > Chunk.H) { p1 = p2 = p3 = pi(nx, ny, nz); }
                            boolean o1 = R[pb[p1] & 0xff].opaque, o2 = R[pb[p2] & 0xff].opaque, o3 = R[pb[p3] & 0xff].opaque;
                            int ao = (o1 && o2) ? 0 : 3 - ((o1 ? 1 : 0) + (o2 ? 1 : 0) + (o3 ? 1 : 0));
                            int l = pl[pi(nx, ny, nz)];
                            int sky = (l >> 4) & 15, blk = l & 15, cnt = 1;
                            if (!o1) { l = pl[p1]; sky += (l >> 4) & 15; blk += l & 15; cnt++; }
                            if (!o2) { l = pl[p2]; sky += (l >> 4) & 15; blk += l & 15; cnt++; }
                            if (!o3 && !(o1 && o2)) { l = pl[p3]; sky += (l >> 4) & 15; blk += l & 15; cnt++; }
                            vx[k] = x + cx; vy[k] = y + (cy == 1 ? topH : 0); vz[k] = z + cz;
                            vu[k] = (k == 1 || k == 2) ? u1 : u0; vv[k] = k < 2 ? v1 : v0;
                            vs[k] = sky / (15f * cnt); vb[k] = blk / (15f * cnt);
                            vh[k] = FACE_SHADE[f] * (liquid ? 1f : AO[ao]);
                            if (k == 0) a0 = ao; else if (k == 1) a1 = ao; else if (k == 2) a2 = ao; else a3 = ao;
                        }
                        emitQuad(b.translucent, a1 + a3 > a0 + a2, liquid && f == 2);
                    }
                }
    }

    private static void cross(Block b, int x, int y, int z) {
        int l = pl[pi(x, y, z)];
        float sky = ((l >> 4) & 15) / 15f, blk = (l & 15) / 15f;
        int tile = b.side;
        float u0 = Atlas.u0(tile), u1 = Atlas.u1(tile), v0 = Atlas.v0(tile), v1 = Atlas.v1(tile);
        for (int d = 0; d < 2; d++) {
            float ax = x + 0.146f, az = d == 0 ? z + 0.146f : z + 0.854f;
            float bx = x + 0.854f, bz = d == 0 ? z + 0.854f : z + 0.146f;
            vx[0] = ax; vy[0] = y; vz[0] = az; vu[0] = u0; vv[0] = v1;
            vx[1] = bx; vy[1] = y; vz[1] = bz; vu[1] = u1; vv[1] = v1;
            vx[2] = bx; vy[2] = y + 1; vz[2] = bz; vu[2] = u1; vv[2] = v0;
            vx[3] = ax; vy[3] = y + 1; vz[3] = az; vu[3] = u0; vv[3] = v0;
            for (int k = 0; k < 4; k++) { vs[k] = sky; vb[k] = blk; vh[k] = 1f; }
            emitQuad(false, false, true);
        }
    }
}
