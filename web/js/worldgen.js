// Geração procedural: relevo, biomas, cavernas, minérios, árvores e plantas. Função pura da semente.
// Este módulo não usa DOM: roda também dentro do Web Worker.
import { B, BLOCKS } from './blocks.js';

export const W = 16, H = 128, VOL = W * W * H, SEA = 56;
export const idx = (x, y, z) => (y << 8) | (z << 4) | x;
export const BIOME_NAME = ['Planície', 'Floresta', 'Deserto', 'Tundra', 'Montanha'];
const PLAINS = 0, FOREST = 1, DESERT = 2, TUNDRA = 3, MOUNTAIN = 4;

export function rng(seed) {
  let a = seed | 0;
  return () => {
    a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Ruído de gradiente (Perlin melhorado). */
export class Noise {
  constructor(seed) {
    const r = rng(seed), perm = new Uint8Array(256);
    for (let i = 0; i < 256; i++) perm[i] = i;
    for (let i = 255; i > 0; i--) { const j = (r() * (i + 1)) | 0, t = perm[i]; perm[i] = perm[j]; perm[j] = t; }
    this.p = new Uint8Array(512);
    for (let i = 0; i < 512; i++) this.p[i] = perm[i & 255];
  }
  n3(x, y, z) {
    const p = this.p, fx = Math.floor(x), fy = Math.floor(y), fz = Math.floor(z);
    const X = fx & 255, Y = fy & 255, Z = fz & 255;
    x -= fx; y -= fy; z -= fz;
    const u = fade(x), v = fade(y), w = fade(z);
    const A = p[X] + Y, AA = p[A] + Z, AB = p[A + 1] + Z, Bb = p[X + 1] + Y, BA = p[Bb] + Z, BB = p[Bb + 1] + Z;
    return lerp(w,
      lerp(v, lerp(u, grad(p[AA], x, y, z), grad(p[BA], x - 1, y, z)), lerp(u, grad(p[AB], x, y - 1, z), grad(p[BB], x - 1, y - 1, z))),
      lerp(v, lerp(u, grad(p[AA + 1], x, y, z - 1), grad(p[BA + 1], x - 1, y, z - 1)),
        lerp(u, grad(p[AB + 1], x, y - 1, z - 1), grad(p[BB + 1], x - 1, y - 1, z - 1))));
  }
  fbm2(x, z, oct) {
    let sum = 0, amp = 1, norm = 0;
    for (let i = 0; i < oct; i++) { sum += this.n3(x, 0.37 + i * 7.13, z) * amp; norm += amp; amp *= 0.5; x *= 2; z *= 2; }
    return sum / norm * 1.6;
  }
}
const fade = t => t * t * t * (t * (t * 6 - 15) + 10);
const lerp = (t, a, b) => a + t * (b - a);
function grad(h, x, y, z) {
  h &= 15;
  const u = h < 8 ? x : y, v = h < 4 ? y : (h === 12 || h === 14 ? x : z);
  return ((h & 1) === 0 ? u : -u) + ((h & 2) === 0 ? v : -v);
}

function hash(seed, x, z, s) {
  let h = seed ^ Math.imul(x, 0x9E3779B1) ^ Math.imul(z, 0x85EBCA6B) ^ Math.imul(s, 0xC2B2AE35);
  h = Math.imul(h ^ (h >>> 15), 0x2C1B3C6D);
  h = Math.imul(h ^ (h >>> 12), 0x297A2D39);
  return (h ^ (h >>> 15)) & 0x7fffffff;
}

export class WorldGen {
  constructor(seed) {
    this.seed = seed | 0;
    const s = this.seed;
    this.cont = new Noise(s); this.hill = new Noise(s + 1); this.detail = new Noise(s + 2); this.temp = new Noise(s + 3);
    this.moist = new Noise(s + 4); this.cave1 = new Noise(s + 5); this.cave2 = new Noise(s + 6);
  }

  /** Altura (y) do bloco sólido mais alto da coluna. */
  height(x, z) {
    const c = this.cont.fbm2(x / 420, z / 420, 4);
    const m = Math.max(0, this.hill.fbm2(x / 300, z / 300, 3) - 0.12);
    const d = this.detail.fbm2(x / 70, z / 70, 4);
    const h = SEA + 5 + c * 26 + d * (4 + m * 30) + m * m * 150;
    return Math.max(8, Math.min(H - 12, h)) | 0;
  }

  biome(x, z, h) {
    if (h > 86) return MOUNTAIN;
    const t = this.temp.fbm2(x / 520, z / 520, 3), w = this.moist.fbm2(x / 420, z / 420, 3);
    if (t > 0.2 && w < 0.12) return DESERT;
    if (t < -0.22) return TUNDRA;
    if (w > 0.06) return FOREST;
    return PLAINS;
  }

  carved(x, y, z, h) {
    if (y < 5 || y > h) return false;
    if (h <= SEA + 3 && y > h - 6) return false; // não fura o fundo de lagos e mares
    const a = this.cave1.n3(x / 46, y / 30, z / 46), b = this.cave2.n3(x / 46, y / 30, z / 46);
    return a * a + b * b < 0.0075;
  }

  /** Devolve os blocos (Uint8Array) do chunk. */
  generate(cx, cz) {
    const b = new Uint8Array(VOL), M = 3, S = 16 + 2 * M, seed = this.seed;
    const hs = new Int32Array(S * S), bs = new Int32Array(S * S), bx = cx << 4, bz = cz << 4;
    for (let z = 0; z < S; z++) for (let x = 0; x < S; x++) {
      const h = this.height(bx + x - M, bz + z - M);
      hs[z * S + x] = h; bs[z * S + x] = this.biome(bx + x - M, bz + z - M, h);
    }
    for (let z = 0; z < 16; z++) for (let x = 0; x < 16; x++) {
      const wx = bx + x, wz = bz + z, h = hs[(z + M) * S + x + M], biome = bs[(z + M) * S + x + M];
      let topB, fill;
      if (h <= SEA + 1) { topB = h < SEA - 9 ? B.GRAVEL : B.SAND; fill = B.SAND; }
      else if (biome === DESERT) { topB = B.SAND; fill = B.SANDSTONE; }
      else if (biome === TUNDRA) { topB = B.SNOW_GRASS; fill = B.DIRT; }
      else if (biome === MOUNTAIN) { topB = h > 96 ? B.SNOW : B.STONE; fill = B.STONE; }
      else { topB = B.GRASS; fill = B.DIRT; }
      for (let y = 0; y <= h; y++) {
        let id;
        if (y === 0) id = B.BEDROCK;
        else if (this.carved(wx, y, wz, h)) id = B.AIR;
        else if (y === h) id = topB;
        else if (y >= h - 3) id = fill;
        else id = B.STONE;
        b[idx(x, y, z)] = id;
      }
      for (let y = h + 1; y <= SEA; y++) b[idx(x, y, z)] = (y === SEA && biome === TUNDRA) ? B.ICE : B.WATER;
    }

    const r = rng(seed ^ Math.imul(cx, 341873129) ^ Math.imul(cz, 132897987));
    const ri = n => (r() * n) | 0;
    const veins = (ore, tries, minY, maxY, size) => {
      for (let t = 0; t < tries; t++) {
        let x = ri(16), y = minY + ri(maxY - minY), z = ri(16);
        for (let i = 0; i < size; i++) {
          if (x >= 0 && x < 16 && z >= 0 && z < 16 && y > 0 && y < H && b[idx(x, y, z)] === B.STONE) b[idx(x, y, z)] = ore;
          const k = ri(3);
          if (k === 0) x += ri(3) - 1; else if (k === 1) y += ri(3) - 1; else z += ri(3) - 1;
        }
      }
    };
    veins(B.DIRT, 5, 5, 70, 22);
    veins(B.GRAVEL, 4, 5, 70, 20);
    veins(B.COAL_ORE, 14, 5, 100, 10);
    veins(B.IRON_ORE, 10, 5, 62, 6);
    veins(B.GOLD_ORE, 3, 5, 30, 5);
    veins(B.GEM_ORE, 2, 5, 18, 4);

    const put = (x, y, z, id, trunk) => {
      if (x < 0 || x > 15 || z < 0 || z > 15 || y < 1 || y >= H) return;
      const i = idx(x, y, z), cur = b[i];
      if (cur === B.AIR || (trunk && (cur === B.LEAVES || cur === B.PINE_LEAVES || (!BLOCKS[cur].solid && cur !== B.WATER)))) b[i] = id;
    };
    // Árvores: decididas por hash da coluna, então atravessam fronteiras de chunk sem costura.
    for (let z = 0; z < S; z++) for (let x = 0; x < S; x++) {
      const wx = bx + x - M, wz = bz + z - M, h = hs[z * S + x], biome = bs[z * S + x];
      if (h <= SEA + 1 || this.carved(wx, h, wz, h)) continue;
      const k = hash(seed, wx, wz, 1);
      const chance = biome === FOREST ? 34 : biome === PLAINS ? 420 : biome === TUNDRA ? 70 : 0;
      if (chance === 0 || k % chance !== 0) continue;
      const tx = x - M, ty = h + 1, tz = z - M;
      if (biome === TUNDRA) {
        const th = 6 + (k >> 8) % 3, top = ty + th - 1;
        for (let ly = ty + 2; ly <= top; ly++) {
          const fromTop = top - ly;
          let rad = fromTop === 0 ? 0 : (fromTop % 2 === 1 ? 1 : 2);
          if (fromTop >= 5) rad = Math.min(rad + 1, 2);
          for (let dz = -rad; dz <= rad; dz++) for (let dx = -rad; dx <= rad; dx++) {
            if (rad > 0 && Math.abs(dx) === rad && Math.abs(dz) === rad) continue;
            put(tx + dx, ly, tz + dz, B.PINE_LEAVES, false);
          }
        }
        put(tx, top + 1, tz, B.PINE_LEAVES, false);
        for (let i = 0; i < th; i++) put(tx, ty + i, tz, B.PINE_LOG, true);
      } else {
        const th = 4 + (k >> 8) % 3, top = ty + th - 1;
        for (let ly = top - 2; ly <= top + 1; ly++) {
          const rad = ly >= top ? 1 : 2;
          for (let dz = -rad; dz <= rad; dz++) for (let dx = -rad; dx <= rad; dx++) {
            const corner = Math.abs(dx) === rad && Math.abs(dz) === rad;
            if (corner && (ly === top + 1 || hash(seed, wx + dx, wz + dz, ly) % 2 === 0)) continue;
            put(tx + dx, ly, tz + dz, B.LEAVES, false);
          }
        }
        for (let i = 0; i < th; i++) put(tx, ty + i, tz, B.LOG, true);
      }
    }
    // Plantas de superfície.
    for (let z = 0; z < 16; z++) for (let x = 0; x < 16; x++) {
      const h = hs[(z + M) * S + x + M], biome = bs[(z + M) * S + x + M];
      if (h <= SEA + 1 || h + 4 >= H || b[idx(x, h + 1, z)] !== B.AIR) continue;
      const ground = b[idx(x, h, z)], k = hash(seed, bx + x, bz + z, 2) % 1000;
      if (ground === B.GRASS) {
        const density = biome === FOREST ? 90 : 140;
        if (k < density) b[idx(x, h + 1, z)] = B.TALL_GRASS;
        else if (k < density + 8) b[idx(x, h + 1, z)] = B.FLOWER_RED;
        else if (k < density + 18) b[idx(x, h + 1, z)] = B.FLOWER_YELLOW;
      } else if (ground === B.SAND && biome === DESERT) {
        if (k < 7) for (let i = 1; i <= 1 + k % 3; i++) b[idx(x, h + i, z)] = B.CACTUS;
        else if (k < 16) b[idx(x, h + 1, z)] = B.DEAD_BUSH;
      }
    }
    return b;
  }
}
