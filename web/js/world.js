// Mundo: chunks carregados, iluminação por inundação, edição de blocos, água, fornalhas e persistência.
import { B, BLOCKS, I, isLiquid, stack, Furnace } from './blocks.js';
import { WorldGen, H, VOL, SEA, idx } from './worldgen.js';
import { ItemEntity } from './entity.js';

export const DX = [1, -1, 0, 0, 0, 0], DY = [0, 0, 1, -1, 0, 0], DZ = [0, 0, 0, 0, 1, -1];
const DOWN = 3;
export const ckey = (cx, cz) => (cx + 32768) * 65536 + (cz + 32768);

export class Chunk {
  constructor(cx, cz, blocks) {
    this.cx = cx; this.cz = cz; this.blocks = blocks; this.light = new Uint8Array(VOL);
    this.lit = false; this.dirty = true; this.modified = false;
    this.vao = [null, null]; this.vbo = [null, null]; this.count = [0, 0];
  }
}

/** Fila de inteiros (x, y, z, valor) sem alocação por elemento. */
class Queue {
  constructor() { this.a = new Int32Array(1 << 16); this.head = 0; this.tail = 0; }
  add(x, y, z, v) {
    if (this.tail + 4 > this.a.length) {
      if (this.head > this.a.length / 2) { this.a.copyWithin(0, this.head, this.tail); this.tail -= this.head; this.head = 0; }
      else { const n = new Int32Array(this.a.length * 2); n.set(this.a); this.a = n; }
    }
    const a = this.a, t = this.tail;
    a[t] = x; a[t + 1] = y; a[t + 2] = z; a[t + 3] = v;
    this.tail = t + 4;
  }
  get empty() { return this.head === this.tail; }
  clear() { this.head = this.tail = 0; }
}

export class World {
  /** store: {keys:Set<string>, get(key):Promise<Uint8Array>, put(key, data)} ou null (sem persistência). */
  constructor(seed, store, makeWorker) {
    this.gen = new WorldGen(seed);
    this.store = store;
    this.chunks = new Map();
    this.furnaces = new Map(); // "x,y,z" -> Furnace
    this.entities = [];
    this.time = 1500; // ticks; um dia = 24000
    this.pending = new Set();
    this.ready = [];
    this.last = null;
    this.skyQ = new Queue(); this.blkQ = new Queue(); this.remQ = new Queue(); this.waterQ = new Queue();
    this.workers = [];
    this.nextWorker = 0;
    if (makeWorker) {
      const n = Math.max(1, Math.min(4, (navigator.hardwareConcurrency || 2) - 1));
      for (let i = 0; i < n; i++) {
        const w = makeWorker();
        w.postMessage({ seed: this.gen.seed });
        w.onmessage = e => this.ready.push(new Chunk(e.data.cx, e.data.cz, e.data.blocks));
        this.workers.push(w);
      }
    }
  }

  chunk(cx, cz) {
    const l = this.last;
    if (l !== null && l.cx === cx && l.cz === cz) return l;
    const c = this.chunks.get(ckey(cx, cz));
    if (c !== undefined) { this.last = c; return c; }
    return null;
  }
  block(x, y, z) {
    if (y < 0 || y >= H) return 0;
    const c = this.chunk(x >> 4, z >> 4);
    return c === null ? 0 : c.blocks[(y << 8) | ((z & 15) << 4) | (x & 15)];
  }
  loaded(x, z) { return this.chunk(x >> 4, z >> 4) !== null; }
  solid(x, y, z) { return BLOCKS[this.block(x, y, z)].solid; }
  sky(x, y, z) {
    if (y >= H) return 15;
    if (y < 0) return 0;
    const c = this.chunk(x >> 4, z >> 4);
    return c === null ? 15 : c.light[(y << 8) | ((z & 15) << 4) | (x & 15)] >> 4;
  }
  blockLight(x, y, z) {
    if (y < 0 || y >= H) return 0;
    const c = this.chunk(x >> 4, z >> 4);
    return c === null ? 0 : c.light[(y << 8) | ((z & 15) << 4) | (x & 15)] & 15;
  }

  /** Fração do dia em [0,1): 0 = nascer do sol, 0.25 = meio-dia, 0.5 = pôr do sol. */
  dayFraction() { return (this.time % 24000) / 24000; }
  daylight() { return Math.max(0.27, Math.min(1, Math.sin(this.dayFraction() * Math.PI * 2) * 3 + 0.55)); }
  isNight() { return Math.sin(this.dayFraction() * Math.PI * 2) < -0.1; }
  lightLevel(x, y, z) { return Math.max(Math.round(this.sky(x, y, z) * this.daylight()), this.blockLight(x, y, z)); }

  // ---------------------------------------------------------------- streaming
  request(cx, cz) {
    const k = ckey(cx, cz);
    if (this.chunks.has(k) || this.pending.has(k)) return;
    this.pending.add(k);
    const sk = cx + ',' + cz;
    if (this.store && this.store.keys.has(sk)) {
      this.store.get(sk).then(data => {
        if (data && data.length === VOL) this.ready.push(new Chunk(cx, cz, data));
        else { this.store.keys.delete(sk); this.pending.delete(k); }
      }).catch(() => { this.store.keys.delete(sk); this.pending.delete(k); });
    } else if (this.workers.length) {
      this.workers[this.nextWorker++ % this.workers.length].postMessage({ cx, cz });
    } else this.ready.push(new Chunk(cx, cz, this.gen.generate(cx, cz)));
  }

  /** Integra um chunk pronto e o ilumina. Devolve false se não havia nenhum. */
  integrate() {
    const c = this.ready.shift();
    if (!c) return false;
    const k = ckey(c.cx, c.cz);
    this.pending.delete(k);
    if (this.chunks.has(k)) return true;
    this.chunks.set(k, c);
    this.lightChunk(c);
    return true;
  }

  unloadFar(pcx, pcz, dist, freeMesh) {
    for (const [k, c] of this.chunks) {
      if (Math.abs(c.cx - pcx) > dist || Math.abs(c.cz - pcz) > dist) {
        if (c.modified) this.save(c);
        freeMesh(c);
        if (this.last === c) this.last = null;
        this.chunks.delete(k);
      }
    }
  }

  neighborsLit(c) {
    for (let dz = -1; dz <= 1; dz++) for (let dx = -1; dx <= 1; dx++) {
      const n = this.chunk(c.cx + dx, c.cz + dz);
      if (n === null || !n.lit) return false;
    }
    return true;
  }

  // ---------------------------------------------------------------- iluminação
  touch(c, lx, lz) {
    c.dirty = true;
    if (lx === 0) this.mark(c.cx - 1, c.cz); else if (lx === 15) this.mark(c.cx + 1, c.cz);
    if (lz === 0) this.mark(c.cx, c.cz - 1); else if (lz === 15) this.mark(c.cx, c.cz + 1);
  }
  mark(cx, cz) { const n = this.chunks.get(ckey(cx, cz)); if (n) n.dirty = true; }

  lightChunk(c) {
    const bx = c.cx << 4, bz = c.cz << 4, top = new Int32Array(256), bl = c.blocks, li = c.light;
    let max = 0;
    for (let z = 0; z < 16; z++) for (let x = 0; x < 16; x++) {
      let y = H - 1;
      while (y >= 0 && BLOCKS[bl[idx(x, y, z)]].opacity === 0) { li[idx(x, y, z)] |= 0xF0; y--; }
      top[z * 16 + x] = y;
      if (y > max) max = y;
    }
    for (let z = 0; z < 16; z++) for (let x = 0; x < 16; x++) {
      const border = x === 0 || x === 15 || z === 0 || z === 15;
      const hi = border ? H - 1 : Math.min(H - 1, max + 1);
      for (let y = top[z * 16 + x] + 1; y <= hi; y++) this.skyQ.add(bx + x, y, bz + z, 0);
    }
    for (let i = 0; i < VOL; i++) {
      const l = BLOCKS[bl[i]].light;
      if (l > 0) { li[i] = (li[i] & 0xF0) | l; this.blkQ.add(bx + (i & 15), i >> 8, bz + ((i >> 4) & 15), 0); }
    }
    // Luz que vem dos vizinhos já iluminados.
    for (let d = 0; d < 4; d++) {
      const ndx = d === 0 ? 1 : d === 1 ? -1 : 0, ndz = d === 2 ? 1 : d === 3 ? -1 : 0;
      const n = this.chunks.get(ckey(c.cx + ndx, c.cz + ndz));
      if (!n || !n.lit) continue;
      for (let y = 0; y < H; y++) for (let t = 0; t < 16; t++) {
        const lx = ndx === 1 ? 0 : ndx === -1 ? 15 : t, lz = ndz === 1 ? 0 : ndz === -1 ? 15 : t;
        const l = n.light[idx(lx, y, lz)];
        if (l & 0xF0) this.skyQ.add((n.cx << 4) + lx, y, (n.cz << 4) + lz, 0);
        if (l & 0x0F) this.blkQ.add((n.cx << 4) + lx, y, (n.cz << 4) + lz, 0);
      }
    }
    this.propagate(this.skyQ, true);
    this.propagate(this.blkQ, false);
    c.lit = true; c.dirty = true;
  }

  propagate(q, isSky) {
    while (q.head !== q.tail) {
      const a = q.a, h = q.head, x = a[h], y = a[h + 1], z = a[h + 2];
      q.head = h + 4;
      const L = isSky ? this.sky(x, y, z) : this.blockLight(x, y, z);
      if (L <= 1) continue;
      for (let d = 0; d < 6; d++) {
        const ny = y + DY[d];
        if (ny < 0 || ny >= H) continue;
        const nx = x + DX[d], nz = z + DZ[d], c = this.chunk(nx >> 4, nz >> 4);
        if (c === null) continue;
        const i = (ny << 8) | ((nz & 15) << 4) | (nx & 15), op = BLOCKS[c.blocks[i]].opacity;
        if (op >= 15) continue;
        const nl = (isSky && d === DOWN && L === 15 && op === 0) ? 15 : L - (op > 1 ? op : 1);
        const cur = isSky ? c.light[i] >> 4 : c.light[i] & 15;
        if (nl > cur) {
          c.light[i] = isSky ? (c.light[i] & 0x0F) | (nl << 4) : (c.light[i] & 0xF0) | nl;
          this.touch(c, nx & 15, nz & 15);
          q.add(nx, ny, nz, 0);
        }
      }
    }
    q.clear();
  }

  removeLight(x, y, z, isSky, pq) {
    const c0 = this.chunk(x >> 4, z >> 4);
    if (c0 === null) return;
    const i0 = idx(x & 15, y, z & 15), old = isSky ? c0.light[i0] >> 4 : c0.light[i0] & 15;
    if (old === 0) return;
    c0.light[i0] = isSky ? c0.light[i0] & 0x0F : c0.light[i0] & 0xF0;
    this.touch(c0, x & 15, z & 15);
    const rq = this.remQ;
    rq.add(x, y, z, old);
    while (rq.head !== rq.tail) {
      const a = rq.a, h = rq.head, qx = a[h], qy = a[h + 1], qz = a[h + 2], val = a[h + 3];
      rq.head = h + 4;
      for (let d = 0; d < 6; d++) {
        const ny = qy + DY[d];
        if (ny < 0 || ny >= H) continue;
        const nx = qx + DX[d], nz = qz + DZ[d], c = this.chunk(nx >> 4, nz >> 4);
        if (c === null) continue;
        const i = (ny << 8) | ((nz & 15) << 4) | (nx & 15), cur = isSky ? c.light[i] >> 4 : c.light[i] & 15;
        if (cur === 0) continue;
        if (cur < val || (isSky && d === DOWN && val === 15)) {
          c.light[i] = isSky ? c.light[i] & 0x0F : c.light[i] & 0xF0;
          this.touch(c, nx & 15, nz & 15);
          rq.add(nx, ny, nz, cur);
        } else pq.add(nx, ny, nz, 0);
      }
    }
    rq.clear();
  }

  // ---------------------------------------------------------------- edição
  /** Troca um bloco, atualiza a luz e dispara as reações dos vizinhos. */
  setBlock(x, y, z, id) {
    if (y < 0 || y >= H) return;
    const c = this.chunk(x >> 4, z >> 4);
    if (c === null) return;
    const i = idx(x & 15, y, z & 15), old = c.blocks[i];
    if (old === id) return;
    c.blocks[i] = id;
    c.modified = true;
    this.touch(c, x & 15, z & 15);
    const isF = v => v === B.FURNACE || v === B.FURNACE_LIT;
    if (isF(old) && !isF(id)) {
      const k = x + ',' + y + ',' + z, f = this.furnaces.get(k);
      if (f) {
        this.furnaces.delete(k);
        for (const s of [f.input, f.fuel, f.output]) if (s) this.dropItem(x + 0.5, y + 0.5, z + 0.5, s);
      }
    }
    this.removeLight(x, y, z, true, this.skyQ);
    this.removeLight(x, y, z, false, this.blkQ);
    const nb = BLOCKS[id];
    if (nb.light > 0) { c.light[i] = (c.light[i] & 0xF0) | nb.light; this.blkQ.add(x, y, z, 0); }
    if (y === H - 1 && nb.opacity === 0) { c.light[i] |= 0xF0; this.skyQ.add(x, y, z, 0); }
    for (let d = 0; d < 6; d++) {
      const ny = y + DY[d];
      if (ny < 0 || ny >= H) continue;
      this.skyQ.add(x + DX[d], ny, z + DZ[d], 0); this.blkQ.add(x + DX[d], ny, z + DZ[d], 0);
    }
    this.propagate(this.skyQ, true);
    this.propagate(this.blkQ, false);

    // Reações em cadeia.
    const above = BLOCKS[this.block(x, y + 1, z)];
    if (!nb.solid && above.needsGround) this.breakBlock(x, y + 1, z, true);
    else if (!nb.solid && above.falls) this.fall(x, y + 1, z);
    if (nb.falls) this.fall(x, y, z);
    if (id === B.AIR && y <= SEA && this.waterNear(x, y, z)) this.waterQ.add(x, y, z, 0);
  }

  fall(x, y, z) {
    const id = this.block(x, y, z);
    let ny = y;
    while (ny > 1 && !this.solid(x, ny - 1, z)) ny--;
    if (ny === y) return;
    this.setBlock(x, y, z, B.AIR);
    const there = this.block(x, ny, z);
    if (there !== B.AIR && !isLiquid(there)) this.breakBlock(x, ny, z, true);
    this.setBlock(x, ny, z, id);
  }

  waterNear(x, y, z) {
    return this.block(x, y + 1, z) === B.WATER || this.block(x + 1, y, z) === B.WATER || this.block(x - 1, y, z) === B.WATER
      || this.block(x, y, z + 1) === B.WATER || this.block(x, y, z - 1) === B.WATER;
  }

  /** Água abaixo do nível do mar inunda cavidades abertas, alguns blocos por tick. */
  tickWater() {
    const q = this.waterQ;
    for (let n = 0; n < 48 && q.head !== q.tail; n++) {
      const x = q.a[q.head], y = q.a[q.head + 1], z = q.a[q.head + 2];
      q.head += 4;
      const cur = this.block(x, y, z);
      if (!this.loaded(x, z) || cur === B.WATER || BLOCKS[cur].solid || !this.waterNear(x, y, z)) continue;
      if (cur !== B.AIR) this.breakBlock(x, y, z, true);
      this.setBlock(x, y, z, B.WATER);
      for (let d = 0; d < 6; d++) {
        if (d === 2) continue;
        const nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d], nid = this.block(nx, ny, nz);
        if (ny > 0 && !BLOCKS[nid].solid && nid !== B.WATER) q.add(nx, ny, nz, 0);
      }
    }
    if (q.head === q.tail) q.clear();
  }

  /** Remove o bloco e (opcionalmente) solta o item correspondente. */
  breakBlock(x, y, z, drop) {
    const id = this.block(x, y, z);
    if (id === B.AIR) return;
    const b = BLOCKS[id];
    this.setBlock(x, y, z, B.AIR);
    if (!drop) return;
    if (b.drop >= 0 && b.dropCount > 0) this.dropItem(x + 0.5, y + 0.3, z + 0.5, stack(b.drop, b.dropCount));
    if (id === B.LEAVES && Math.random() < 0.1) this.dropItem(x + 0.5, y + 0.3, z + 0.5, stack(I.FRUIT, 1));
    if ((id === B.LEAVES || id === B.PINE_LEAVES) && Math.random() < 0.125) this.dropItem(x + 0.5, y + 0.3, z + 0.5, stack(I.STICK, 1));
  }

  dropItem(x, y, z, s) {
    const e = new ItemEntity(s);
    e.x = x; e.y = y; e.z = z;
    e.vx = (Math.random() - 0.5) * 2; e.vy = 3; e.vz = (Math.random() - 0.5) * 2;
    this.entities.push(e);
  }

  furnaceAt(x, y, z) {
    const k = x + ',' + y + ',' + z;
    let f = this.furnaces.get(k);
    if (!f) { f = new Furnace(); this.furnaces.set(k, f); }
    return f;
  }

  tickFurnaces() {
    for (const [k, f] of this.furnaces) {
      const [x, y, z] = k.split(',').map(Number), lit = f.tick(), cur = this.block(x, y, z);
      if (cur === B.FURNACE && lit) this.setBlock(x, y, z, B.FURNACE_LIT);
      else if (cur === B.FURNACE_LIT && !lit) this.setBlock(x, y, z, B.FURNACE);
    }
  }

  /** DDA voxel: devolve {x,y,z,nx,ny,nz,t} do primeiro bloco selecionável, ou null. */
  raycast(ox, oy, oz, dx, dy, dz, max) {
    let x = Math.floor(ox), y = Math.floor(oy), z = Math.floor(oz);
    const sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
    const tdx = dx === 0 ? 1e30 : Math.abs(1 / dx), tdy = dy === 0 ? 1e30 : Math.abs(1 / dy), tdz = dz === 0 ? 1e30 : Math.abs(1 / dz);
    let tx = dx === 0 ? 1e30 : (dx > 0 ? x + 1 - ox : ox - x) * tdx;
    let ty = dy === 0 ? 1e30 : (dy > 0 ? y + 1 - oy : oy - y) * tdy;
    let tz = dz === 0 ? 1e30 : (dz > 0 ? z + 1 - oz : oz - z) * tdz;
    let nx = 0, ny = 0, nz = 0, t = 0;
    while (t <= max) {
      const id = this.block(x, y, z);
      if (id !== B.AIR && !isLiquid(id)) return { x, y, z, nx, ny, nz, t };
      if (tx < ty && tx < tz) { x += sx; t = tx; tx += tdx; nx = -sx; ny = 0; nz = 0; }
      else if (ty < tz) { y += sy; t = ty; ty += tdy; nx = 0; ny = -sy; nz = 0; }
      else { z += sz; t = tz; tz += tdz; nx = 0; ny = 0; nz = -sz; }
    }
    return null;
  }

  // ---------------------------------------------------------------- persistência
  save(c) {
    if (!this.store) return;
    this.store.put(c.cx + ',' + c.cz, c.blocks);
    c.modified = false;
  }
  saveAll() { for (const c of this.chunks.values()) if (c.modified) this.save(c); }
}
