// Física e entidades: jogador, criaturas e itens soltos.
import { BLOCKS, ITEMS, isLiquid, stack } from './blocks.js';

/** Corpo físico com caixa de colisão alinhada aos eixos (posição = centro dos pés). */
export class Entity {
  constructor() {
    this.x = 0; this.y = 0; this.z = 0; this.vx = 0; this.vy = 0; this.vz = 0;
    this.w = 0.6; this.h = 1.8; this.yaw = 0;
    this.onGround = false; this.inWater = false; this.hitWall = false; this.flying = false; this.dead = false;
    this.health = 20; this.hurtTime = 0; this.fallStart = 0;
  }

  collides(wd, px, py, pz) {
    const hw = this.w / 2;
    const x0 = Math.floor(px - hw), x1 = Math.floor(px + hw), y0 = Math.floor(py), y1 = Math.floor(py + this.h);
    const z0 = Math.floor(pz - hw), z1 = Math.floor(pz + hw);
    for (let by = y0; by <= y1; by++) for (let bz = z0; bz <= z1; bz++) for (let bx = x0; bx <= x1; bx++)
      if (wd.solid(bx, by, bz)) return true;
    return false;
  }

  /** Integra gravidade e move com colisão eixo a eixo. Devolve a altura da queda ao pousar (ou 0). */
  physics(wd, dt) {
    const hw = this.w / 2, h = this.h;
    this.inWater = isLiquid(wd.block(Math.floor(this.x), Math.floor(this.y + 0.3), Math.floor(this.z)));
    if (!this.flying) {
      if (this.inWater) { this.vy -= 9 * dt; this.vy *= Math.pow(0.12, dt); this.vx *= Math.pow(0.25, dt); this.vz *= Math.pow(0.25, dt); }
      else this.vy -= 28 * dt;
      if (this.vy < -55) this.vy = -55;
    }
    const wasGround = this.onGround;
    let dx = this.vx * dt, dy = this.vy * dt, dz = this.vz * dt;
    const steps = Math.max(1, Math.ceil(Math.max(Math.abs(dx), Math.abs(dy), Math.abs(dz)) / 0.35));
    this.onGround = false; this.hitWall = false;
    for (let s = 0; s < steps; s++) {
      let ny = this.y + dy / steps;
      if (this.collides(wd, this.x, ny, this.z)) {
        if (dy < 0) { ny = Math.floor(ny) + 1; this.onGround = true; } else ny = Math.floor(ny + h) - h - 1e-4;
        if (this.collides(wd, this.x, ny, this.z)) ny = this.y;
        this.vy = 0; dy = 0;
      }
      this.y = ny;
      let nx = this.x + dx / steps;
      if (this.collides(wd, nx, this.y, this.z)) {
        nx = dx > 0 ? Math.floor(nx + hw) - hw - 1e-4 : Math.floor(nx - hw) + 1 + hw + 1e-4;
        if (this.collides(wd, nx, this.y, this.z)) nx = this.x;
        this.vx = 0; dx = 0; this.hitWall = true;
      }
      this.x = nx;
      let nz = this.z + dz / steps;
      if (this.collides(wd, this.x, this.y, nz)) {
        nz = dz > 0 ? Math.floor(nz + hw) - hw - 1e-4 : Math.floor(nz - hw) + 1 + hw + 1e-4;
        if (this.collides(wd, this.x, this.y, nz)) nz = this.z;
        this.vz = 0; dz = 0; this.hitWall = true;
      }
      this.z = nz;
    }
    let fell = 0;
    if (this.onGround || this.inWater || this.flying) {
      if (this.onGround && !wasGround) fell = this.fallStart - this.y;
      this.fallStart = this.y;
    } else if (this.y > this.fallStart) this.fallStart = this.y;
    if (this.hurtTime > 0) this.hurtTime -= dt;
    return fell;
  }

  hurt(dmg, fromX, fromZ) {
    if (this.hurtTime > 0 || this.dead) return;
    this.health -= dmg; this.hurtTime = 0.45;
    const dx = this.x - fromX, dz = this.z - fromZ, d = Math.sqrt(dx * dx + dz * dz);
    if (d > 0.01) { this.vx += dx / d * 6; this.vz += dz / d * 6; this.vy = 5.5; }
    if (this.health <= 0) this.dead = true;
  }

  /** Interseção raio/caixa; devolve a distância ou -1. */
  rayHit(ox, oy, oz, dx, dy, dz) {
    let tmin = 0, tmax = 1e9;
    const o = [ox, oy, oz], d = [dx, dy, dz], hw = this.w / 2;
    const lo = [this.x - hw, this.y, this.z - hw], hi = [this.x + hw, this.y + this.h, this.z + hw];
    for (let i = 0; i < 3; i++) {
      if (Math.abs(d[i]) < 1e-9) { if (o[i] < lo[i] || o[i] > hi[i]) return -1; continue; }
      let t1 = (lo[i] - o[i]) / d[i], t2 = (hi[i] - o[i]) / d[i];
      if (t1 > t2) { const t = t1; t1 = t2; t2 = t; }
      tmin = Math.max(tmin, t1); tmax = Math.min(tmax, t2);
      if (tmin > tmax) return -1;
    }
    return tmin;
  }
}

/** Item solto no chão, recolhido ao encostar. */
export class ItemEntity extends Entity {
  constructor(s) { super(); this.stack = s; this.age = 0; this.w = 0.25; this.h = 0.25; }
}

export const GRAZER = 0, STALKER = 1;

/** Criaturas originais: o Lanudo (pacífico, pasta de dia) e o Brasal (hostil, surge no escuro). */
export class Mob extends Entity {
  constructor(type) {
    super();
    this.type = type; this.walkAnim = 0; this.thinkTimer = 0; this.attackTimer = 0; this.fleeTimer = 0;
    this.tx = 0; this.tz = 0; this.moving = false;
    if (type === GRAZER) { this.w = 0.8; this.h = 1.2; this.health = 8; } else { this.w = 0.8; this.h = 1.0; this.health = 16; }
  }

  update(wd, p, dt) {
    const dxp = p.x - this.x, dzp = p.z - this.z, dist = Math.sqrt(dxp * dxp + dzp * dzp + (p.y - this.y) * (p.y - this.y));
    let speed;
    this.thinkTimer -= dt; this.attackTimer -= dt; this.fleeTimer -= dt;
    const chase = this.type === STALKER && !p.creative && !p.dead && dist < 18;
    if (chase) {
      this.tx = p.x; this.tz = p.z; this.moving = dist > 1.1; speed = 3.4;
      if (dist < 1.7 && this.attackTimer <= 0) { p.hurt(3, this.x, this.z); this.attackTimer = 1; }
    } else if (this.fleeTimer > 0) {
      this.tx = this.x - dxp * 4; this.tz = this.z - dzp * 4; this.moving = true; speed = 4.2;
    } else {
      if (this.thinkTimer <= 0) {
        this.thinkTimer = 2 + Math.random() * 5;
        this.moving = Math.random() < 0.66;
        this.tx = this.x + Math.floor(Math.random() * 17) - 8; this.tz = this.z + Math.floor(Math.random() * 17) - 8;
      }
      speed = 1.4;
    }
    const ddx = this.tx - this.x, ddz = this.tz - this.z, dl = Math.sqrt(ddx * ddx + ddz * ddz), k = Math.min(1, dt * 10);
    if (this.moving && dl > 0.4) {
      let diff = Math.atan2(ddx, ddz) - this.yaw;
      while (diff > Math.PI) diff -= 2 * Math.PI;
      while (diff < -Math.PI) diff += 2 * Math.PI;
      this.yaw += diff * Math.min(1, dt * 8);
      this.vx += (Math.sin(this.yaw) * speed - this.vx) * k; this.vz += (Math.cos(this.yaw) * speed - this.vz) * k;
      this.walkAnim += dt * speed * 3.2;
    } else {
      this.vx -= this.vx * k; this.vz -= this.vz * k;
      this.moving = chase;
    }
    if (this.inWater) this.vy = Math.max(this.vy, 2.5);
    else if (this.hitWall && this.onGround && this.moving) this.vy = 8.4;
    const fell = this.physics(wd, dt);
    if (fell > 4) this.hurt(Math.floor(fell - 3), this.x, this.z);
  }

  hurt(dmg, fromX, fromZ) {
    super.hurt(dmg, fromX, fromZ);
    if (this.type === GRAZER) this.fleeTimer = 5;
  }
}

/** O jogador: inventário, vida, fome, fôlego e modo de jogo. */
export class Player extends Entity {
  constructor() {
    super();
    this.pitch = 0; this.creative = false; this.hunger = 20; this.air = 10;
    this.exhaustion = 0; this.regenTimer = 0; this.airTimer = 0;
    this.inv = new Array(36).fill(null); this.selected = 0;
  }
  held() { return this.inv[this.selected]; }

  /** Tenta guardar a pilha; devolve o que não coube (ou null). */
  give(s) {
    const max = ITEMS[s.id].maxStack, inv = this.inv;
    if (max > 1) for (let i = 0; i < 36 && s.count > 0; i++)
      if (inv[i] && inv[i].id === s.id && inv[i].count < max) { const n = Math.min(s.count, max - inv[i].count); inv[i].count += n; s.count -= n; }
    for (let i = 0; i < 36 && s.count > 0; i++)
      if (!inv[i]) { const n = Math.min(s.count, max); inv[i] = stack(s.id, n, s.dmg); s.count -= n; }
    return s.count > 0 ? s : null;
  }

  damageTool() {
    const s = this.held();
    if (this.creative || !s || ITEMS[s.id].durability === 0) return;
    if (++s.dmg >= ITEMS[s.id].durability) this.inv[this.selected] = null;
  }
}
export const EYE = 1.62;
