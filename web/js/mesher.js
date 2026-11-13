// Converte um chunk em triângulos: faces ocultas removidas, luz suave e oclusão de ambiente por vértice.
import { BLOCKS, B, SHAPE_CROSS, SHAPE_LIQUID } from './blocks.js';
import { H, idx } from './worldgen.js';
import { u0, u1, v0, v1 } from './atlas.js';
import { DX, DY, DZ } from './world.js';

const PW = 18, PH = H + 2;
const pb = new Uint8Array(PW * PW * PH), pl = new Uint8Array(PW * PW * PH);
export const out = { opaque: new Float32Array(1 << 18), trans: new Float32Array(1 << 14), nOpaque: 0, nTrans: 0 };

// Cantos de cada face em ordem anti-horária vista de fora: +X, -X, +Y, -Y, +Z, -Z.
export const CORN = [
  [1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1], [0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0],
  [0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 1, 0], [0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1],
  [0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1], [1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0]];
export const FACE_SHADE = [0.68, 0.68, 1, 0.5, 0.84, 0.84];
const AO = [0.42, 0.62, 0.82, 1];
const ORDER = [0, 1, 2, 0, 2, 3], ORDER_FLIP = [1, 2, 3, 1, 3, 0];
const pi = (x, y, z) => ((y + 1) * PW + (z + 1)) * PW + (x + 1);
const vx = new Float32Array(4), vy = new Float32Array(4), vz = new Float32Array(4), vu = new Float32Array(4),
  vv = new Float32Array(4), vs = new Float32Array(4), vb = new Float32Array(4), vh = new Float32Array(4);

function put(t, k) {
  let a, n;
  if (t) {
    if (out.nTrans + 8 > out.trans.length) { const g = new Float32Array(out.trans.length * 2); g.set(out.trans); out.trans = g; }
    a = out.trans; n = out.nTrans; out.nTrans += 8;
  } else {
    if (out.nOpaque + 8 > out.opaque.length) { const g = new Float32Array(out.opaque.length * 2); g.set(out.opaque); out.opaque = g; }
    a = out.opaque; n = out.nOpaque; out.nOpaque += 8;
  }
  a[n] = vx[k]; a[n + 1] = vy[k]; a[n + 2] = vz[k]; a[n + 3] = vu[k]; a[n + 4] = vv[k]; a[n + 5] = vs[k]; a[n + 6] = vb[k]; a[n + 7] = vh[k];
}
function emitQuad(t, flip, twoSided) {
  const order = flip ? ORDER_FLIP : ORDER;
  for (let i = 0; i < 6; i++) put(t, order[i]);
  if (twoSided) for (let i = 5; i >= 0; i--) put(t, order[i]);
}

function cross(b, x, y, z) {
  const l = pl[pi(x, y, z)], sky = (l >> 4) / 15, blk = (l & 15) / 15, tile = b.side;
  const a0 = u0(tile), a1 = u1(tile), b0 = v0(tile), b1 = v1(tile);
  for (let d = 0; d < 2; d++) {
    const ax = x + 0.146, az = d === 0 ? z + 0.146 : z + 0.854, bx = x + 0.854, bz = d === 0 ? z + 0.854 : z + 0.146;
    vx[0] = ax; vy[0] = y; vz[0] = az; vu[0] = a0; vv[0] = b1;
    vx[1] = bx; vy[1] = y; vz[1] = bz; vu[1] = a1; vv[1] = b1;
    vx[2] = bx; vy[2] = y + 1; vz[2] = bz; vu[2] = a1; vv[2] = b0;
    vx[3] = ax; vy[3] = y + 1; vz[3] = az; vu[3] = a0; vv[3] = b0;
    for (let k = 0; k < 4; k++) { vs[k] = sky; vb[k] = blk; vh[k] = 1; }
    emitQuad(false, false, true);
  }
}

/** Monta as malhas do chunk (resultado em `out`). Requer os 8 vizinhos carregados. */
export function buildMesh(w, c) {
  out.nOpaque = out.nTrans = 0;
  const nb = [];
  for (let dz = -1; dz <= 1; dz++) for (let dx = -1; dx <= 1; dx++) nb.push(w.chunk(c.cx + dx, c.cz + dz));
  for (let z = -1; z <= 16; z++) for (let x = -1; x <= 16; x++) {
    const s = nb[((z >> 4) + 1) * 3 + (x >> 4) + 1], lx = x & 15, lz = z & 15, sb = s.blocks, sl = s.light;
    pb[pi(x, -1, z)] = B.BEDROCK; pl[pi(x, -1, z)] = 0;
    pb[pi(x, H, z)] = 0; pl[pi(x, H, z)] = 0xF0;
    let p = pi(x, 0, z), i = idx(lx, 0, lz);
    for (let y = 0; y < H; y++, p += PW * PW, i += 256) { pb[p] = sb[i]; pl[p] = sl[i]; }
  }
  const R = BLOCKS;
  for (let y = 0; y < H; y++) for (let z = 0; z < 16; z++) for (let x = 0; x < 16; x++) {
    const id = pb[pi(x, y, z)];
    if (id === 0) continue;
    const b = R[id];
    if (b.shape === SHAPE_CROSS) { cross(b, x, y, z); continue; }
    const liquid = b.shape === SHAPE_LIQUID, topH = (liquid && pb[pi(x, y + 1, z)] !== id) ? 0.875 : 1;
    for (let f = 0; f < 6; f++) {
      const nx = x + DX[f], ny = y + DY[f], nz = z + DZ[f], pn = pi(nx, ny, nz), nid = pb[pn];
      if (R[nid].opaque || (nid === id && b.cullSame)) continue;
      const tile = f === 2 ? b.top : f === 3 ? b.bottom : b.side;
      const a0 = u0(tile), a1 = u1(tile), b0 = v0(tile), b1 = v1(tile), cn = CORN[f], axis = f >> 1;
      let ao0 = 0, ao1 = 0, ao2 = 0, ao3 = 0;
      for (let k = 0; k < 4; k++) {
        const cx = cn[k * 3], cy = cn[k * 3 + 1], cz = cn[k * 3 + 2];
        const sx = cx === 1 ? 1 : -1, sy = cy === 1 ? PW * PW : -PW * PW, sz = cz === 1 ? PW : -PW;
        let p1, p2;
        if (axis === 0) { p1 = pn + sy; p2 = pn + sz; } else if (axis === 1) { p1 = pn + sx; p2 = pn + sz; } else { p1 = pn + sx; p2 = pn + sy; }
        const p3 = p1 + p2 - pn;
        const o1 = R[pb[p1]].opaque, o2 = R[pb[p2]].opaque, o3 = R[pb[p3]].opaque;
        const ao = (o1 && o2) ? 0 : 3 - ((o1 ? 1 : 0) + (o2 ? 1 : 0) + (o3 ? 1 : 0));
        let l = pl[pn], sky = l >> 4, blk = l & 15, cnt = 1;
        if (!o1) { l = pl[p1]; sky += l >> 4; blk += l & 15; cnt++; }
        if (!o2) { l = pl[p2]; sky += l >> 4; blk += l & 15; cnt++; }
        if (!o3 && !(o1 && o2)) { l = pl[p3]; sky += l >> 4; blk += l & 15; cnt++; }
        vx[k] = x + cx; vy[k] = y + (cy === 1 ? topH : 0); vz[k] = z + cz;
        vu[k] = (k === 1 || k === 2) ? a1 : a0; vv[k] = k < 2 ? b1 : b0;
        vs[k] = sky / (15 * cnt); vb[k] = blk / (15 * cnt);
        vh[k] = FACE_SHADE[f] * (liquid ? 1 : AO[ao]);
        if (k === 0) ao0 = ao; else if (k === 1) ao1 = ao; else if (k === 2) ao2 = ao; else ao3 = ao;
      }
      emitQuad(b.translucent, ao1 + ao3 > ao0 + ao2, liquid && f === 2);
    }
  }
}
