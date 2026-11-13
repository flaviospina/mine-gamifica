// Interface 2D (canvas sobreposto): HUD, inventário, bancada, fornalha, pausa e tela de derrota.
import { T, TILE, GRID } from './atlas.js';
import { B, BLOCKS, ITEMS, SHAPE_CROSS, stack, stacksWith, matchRecipe, COOK_TIME } from './blocks.js';
import { BIOME_NAME, H } from './worldgen.js';
import { GRAZER } from './entity.js';

export const SCR_NONE = 0, SCR_INV = 1, SCR_BENCH = 2, SCR_FURNACE = 3, SCR_PAUSE = 4, SCR_DEAD = 5;
const K_NORMAL = 0, K_RESULT = 1, K_TAKE_ONLY = 2, K_PALETTE = 3;
const PW = 176, PH = 170;
const HELP = ['WASD mover  |  Espaço pular/nadar  |  Shift correr  |  C agachar',
  'Botão esquerdo minerar/atacar  |  Botão direito colocar/usar/comer',
  'E inventário  |  1-9 e rolagem: barra  |  Q soltar item  |  G modo criativo  |  F voar',
  'H esconder HUD  |  P informações  |  Esc pausar'];

export class Ui {
  constructor(g, canvas, atlases) {
    this.g = g; this.canvas = canvas; this.ctx = canvas.getContext('2d');
    this.atlas = atlases; // [normal, face esquerda, face direita]
    this.slots = []; this.buttons = [];
    this.cursor = null; this.craft = new Array(4).fill(null); this.craftW = 2;
    this.scale = 2; this.gw = 0; this.gh = 0; this.px = 0; this.py = 0;
    this.furnace = null; this.mx = 0; this.my = 0;
  }

  // ---------------------------------------------------------------- primitivas
  fill(x, y, w, h, color) { this.ctx.fillStyle = color; this.ctx.fillRect(x, y, w, h); }
  tile(t, x, y, w, h, atlas = 0) {
    this.ctx.drawImage(this.atlas[atlas], (t % GRID) * TILE, ((t / GRID) | 0) * TILE, TILE, TILE, x, y, w, h);
  }
  text(s, x, y, size, color = '#fff', align = 'left') {
    const c = this.ctx;
    c.font = `bold ${size}px "DejaVu Sans Mono", Consolas, Menlo, monospace`;
    c.textAlign = align; c.textBaseline = 'top';
    c.fillStyle = 'rgba(0,0,0,0.7)'; c.fillText(s, x + size * 0.09, y + size * 0.09);
    c.fillStyle = color; c.fillText(s, x, y);
  }
  width(s, size) { this.ctx.font = `bold ${size}px "DejaVu Sans Mono", Consolas, Menlo, monospace`; return this.ctx.measureText(s).width; }

  item(s, x, y) {
    if (!s) return;
    const it = ITEMS[s.id], bl = it.isBlock ? BLOCKS[it.id] : null, c = this.ctx, k = this.scale;
    if (bl && bl.shape !== SHAPE_CROSS) {
      // cubo isométrico: topo, face esquerda e face direita
      const face = (t, atlas, ox, oy, ux, uy, vx, vy) => {
        c.setTransform(k * ux / 16, k * uy / 16, k * vx / 16, k * vy / 16, k * ox, k * oy);
        c.drawImage(this.atlas[atlas], (t % GRID) * TILE, ((t / GRID) | 0) * TILE, TILE, TILE, 0, 0, 16, 16);
      };
      face(bl.top, 0, x + 8, y, 8, 4, -8, 4);
      face(bl.side, 1, x, y + 4, 8, 4, 0, 8);
      face(bl.side, 2, x + 8, y + 8, 8, -4, 0, 8);
      c.setTransform(k, 0, 0, k, 0, 0);
    } else this.tile(it.tile, x, y, 16, 16);
    if (it.durability > 0 && s.dmg > 0) {
      const f = 1 - s.dmg / it.durability;
      this.fill(x + 1, y + 14, 14, 2, '#000');
      this.fill(x + 1, y + 14, 14 * f, 1, f > 0.5 ? '#40d040' : f > 0.2 ? '#e0c030' : '#e03030');
    }
    if (s.count > 1) this.text(String(s.count), x + 17, y + 9.5, 7, '#fff', 'right');
  }
  slotBox(x, y) { this.fill(x - 1, y - 1, 18, 18, '#4a4a52'); this.fill(x, y, 17, 17, '#8e8e98'); this.fill(x, y, 16, 16, '#6c6c76'); }
  panel(x, y, w, h) {
    this.fill(x - 2, y - 2, w + 4, h + 4, '#1e1e24'); this.fill(x, y, w, h, '#b9b9c4');
    this.fill(x, y, w, 1, '#e6e6ee'); this.fill(x, y, 1, h, '#e6e6ee');
  }

  // ---------------------------------------------------------------- telas
  layout() {
    const cw = this.canvas.width, ch = this.canvas.height;
    this.scale = Math.max(1, Math.min(Math.floor(ch / 300), Math.floor(cw / 400)));
    this.gw = cw / this.scale; this.gh = ch / this.scale;
    this.px = Math.floor((this.gw - PW) / 2); this.py = Math.floor((this.gh - PH) / 2);
  }

  open(screen) {
    this.layout();
    const g = this.g, p = g.player, { px, py, gw, gh } = this, slots = this.slots = [], buttons = this.buttons = [];
    const cx = Math.floor(gw / 2), cy = Math.floor(gh / 2);
    if (screen === SCR_PAUSE) {
      buttons.push({ x: cx - 80, y: cy - 22, w: 160, h: 20, label: () => g.started ? 'Continuar' : 'Jogar', act: () => g.resume() });
      buttons.push({ x: cx - 80, y: cy + 2, w: 76, h: 20, label: () => 'Visão -', act: () => g.setRenderDist(g.renderDist - 1) });
      buttons.push({ x: cx + 4, y: cy + 2, w: 76, h: 20, label: () => 'Visão +', act: () => g.setRenderDist(g.renderDist + 1) });
      buttons.push({ x: cx - 80, y: cy + 26, w: 160, h: 20, label: () => 'Novo mundo', act: () => g.newWorld() });
      return;
    }
    if (screen === SCR_DEAD) { buttons.push({ x: cx - 75, y: cy + 6, w: 150, h: 20, label: () => 'Renascer', act: () => g.respawn() }); return; }
    for (let i = 0; i < 36; i++) {
      const sx = px + 8 + (i % 9) * 18, sy = i < 9 ? py + 146 : py + 88 + (((i / 9) | 0) - 1) * 18;
      slots.push({ get: () => p.inv[i], set: s => { p.inv[i] = s; }, x: sx, y: sy, kind: K_NORMAL });
    }
    if (screen === SCR_INV || screen === SCR_BENCH) {
      const inv = screen === SCR_INV, n = this.craftW = inv ? 2 : 3;
      this.craft = new Array(n * n).fill(null);
      const ox = inv ? px + 80 : px + 30, oy = inv ? py + 24 : py + 18;
      for (let i = 0; i < n * n; i++)
        slots.push({ get: () => this.craft[i], set: s => { this.craft[i] = s; }, x: ox + (i % n) * 18, y: oy + ((i / n) | 0) * 18, kind: K_NORMAL });
      slots.push({ get: () => matchRecipe(this.craft, n), set: () => {}, x: inv ? px + 144 : px + 126, y: inv ? py + 33 : py + 36, kind: K_RESULT });
      if (p.creative && inv) {
        let k = 0;
        for (const it of ITEMS) {
          if (!it || it.id === B.WATER) continue;
          const id = it.id;
          slots.push({ get: () => stack(id, 1), set: () => {}, x: px - 116 + (k % 6) * 18, y: py - 12 + ((k / 6) | 0) * 18, kind: K_PALETTE });
          k++;
        }
      }
    } else if (screen === SCR_FURNACE) {
      const f = this.furnace;
      slots.push({ get: () => f.input, set: s => { f.input = s; }, x: px + 52, y: py + 18, kind: K_NORMAL });
      slots.push({ get: () => f.fuel, set: s => { f.fuel = s; }, x: px + 52, y: py + 54, kind: K_NORMAL });
      slots.push({ get: () => f.output, set: s => { f.output = s; }, x: px + 112, y: py + 36, kind: K_TAKE_ONLY });
    }
  }

  /** Devolve ao jogador o que ficou na grade de montagem e no cursor. */
  closeContainers() {
    const back = s => { if (s) { const rest = this.g.player.give(s); if (rest) this.g.tossItem(rest); } };
    for (let i = 0; i < this.craft.length; i++) { back(this.craft[i]); this.craft[i] = null; }
    back(this.cursor);
    this.cursor = null;
  }

  click(button) {
    const mx = this.mx, my = this.my;
    for (const b of this.buttons) if (mx >= b.x && mx < b.x + b.w && my >= b.y && my < b.y + b.h) { b.act(); return; }
    let hitSlot = false;
    for (const s of this.slots) {
      if (mx < s.x - 1 || mx >= s.x + 17 || my < s.y - 1 || my >= s.y + 17) continue;
      hitSlot = true;
      const cur = s.get(), max = cur ? ITEMS[cur.id].maxStack : 0;
      if (s.kind === K_PALETTE) {
        this.cursor = ((this.cursor && this.cursor.id === cur.id) || button !== 0) ? null : stack(cur.id, max);
      } else if (s.kind === K_RESULT) {
        if (!cur) break;
        if (!this.cursor) this.cursor = cur;
        else if (stacksWith(this.cursor, cur) && this.cursor.count + cur.count <= max) this.cursor.count += cur.count;
        else break;
        for (let i = 0; i < this.craft.length; i++) if (this.craft[i] && --this.craft[i].count <= 0) this.craft[i] = null;
      } else if (s.kind === K_TAKE_ONLY) {
        if (!cur) break;
        if (!this.cursor) { this.cursor = cur; s.set(null); }
        else if (stacksWith(this.cursor, cur) && this.cursor.count + cur.count <= max) { this.cursor.count += cur.count; s.set(null); }
      } else if (button === 0) {
        if (!this.cursor) { this.cursor = cur; s.set(null); }
        else if (!cur) { s.set(this.cursor); this.cursor = null; }
        else if (stacksWith(cur, this.cursor)) {
          const n = Math.min(this.cursor.count, max - cur.count);
          cur.count += n; this.cursor.count -= n;
          if (this.cursor.count <= 0) this.cursor = null;
        } else { s.set(this.cursor); this.cursor = cur; }
      } else if (button === 2) {
        if (!this.cursor && cur) {
          const n = (cur.count + 1) >> 1;
          this.cursor = stack(cur.id, n, cur.dmg);
          cur.count -= n;
          if (cur.count <= 0) s.set(null);
        } else if (this.cursor && (!cur || (stacksWith(cur, this.cursor) && cur.count < max))) {
          if (!cur) s.set(stack(this.cursor.id, 1, this.cursor.dmg)); else cur.count++;
          if (--this.cursor.count <= 0) this.cursor = null;
        }
      }
      break;
    }
    // clicar fora do painel com um item no cursor joga o item no chão
    if (!hitSlot && this.cursor && this.slots.length && (mx < this.px || mx > this.px + PW || my < this.py || my > this.py + PH)) {
      this.g.tossItem(this.cursor);
      this.cursor = null;
    }
  }

  // ---------------------------------------------------------------- desenho
  render(underwater) {
    const g = this.g, p = g.player, c = this.ctx, oldW = this.gw, oldH = this.gh;
    this.layout();
    if ((oldW !== this.gw || oldH !== this.gh) && g.screen !== SCR_NONE) this.open(g.screen);
    const { gw, gh } = this, scr = g.screen;
    c.setTransform(1, 0, 0, 1, 0, 0);
    c.clearRect(0, 0, this.canvas.width, this.canvas.height);
    c.setTransform(this.scale, 0, 0, this.scale, 0, 0);
    c.imageSmoothingEnabled = false;
    if (underwater) this.fill(0, 0, gw, gh, 'rgba(32,80,192,0.25)');
    if (p.hurtTime > 0.2 && !p.creative) this.fill(0, 0, gw, gh, 'rgba(255,0,0,0.22)');
    if (!g.hideHud && scr !== SCR_PAUSE) this.hud(p);
    if (scr !== SCR_NONE) this.fill(0, 0, gw, gh, scr === SCR_DEAD ? 'rgba(112,0,0,0.56)' : 'rgba(16,16,24,0.56)');
    if (scr === SCR_INV || scr === SCR_BENCH || scr === SCR_FURNACE) this.container(scr);
    else if (scr === SCR_PAUSE) {
      this.text('Terralume', gw / 2, gh / 2 - 82, 24, '#ffe9a0', 'center');
      this.text(g.started ? 'Jogo pausado' : (g.loadingText() || 'Mundo pronto'), gw / 2, gh / 2 - 46, 9, '#fff', 'center');
      this.text('Distância de visão: ' + g.renderDist + ' chunks', gw / 2, gh / 2 + 52, 7, '#d0d0d0', 'center');
      HELP.forEach((l, i) => this.text(l, gw / 2, gh - 46 + i * 10, 6.5, '#d0d0d0', 'center'));
    } else if (scr === SCR_DEAD) this.text('Você desmaiou!', gw / 2, gh / 2 - 40, 18, '#fff', 'center');
    const mx = this.mx, my = this.my;
    for (const b of this.buttons) {
      const hover = mx >= b.x && mx < b.x + b.w && my >= b.y && my < b.y + b.h;
      this.fill(b.x - 1, b.y - 1, b.w + 2, b.h + 2, '#101014');
      this.fill(b.x, b.y, b.w, b.h, hover ? '#7a8fd0' : '#6c6c76');
      this.fill(b.x, b.y, b.w, 1, hover ? '#a8b8f0' : '#9a9aa4');
      this.text(b.label(), b.x + b.w / 2, b.y + 5.5, 9, '#fff', 'center');
    }
    if (g.showDebug) this.debug(p);
    if (g.toastTime > 0) this.text(g.toast, gw / 2, gh - 62, 9, '#fff', 'center');
  }

  hud(p) {
    const g = this.g, { gw, gh } = this, cx = gw / 2, cy = gh / 2;
    if (g.screen === SCR_NONE) { this.fill(cx - 5, cy - 0.5, 10, 1, 'rgba(255,255,255,0.82)'); this.fill(cx - 0.5, cy - 5, 1, 10, 'rgba(255,255,255,0.82)'); }
    const hx = Math.floor(cx - 91), hy = gh - 23;
    this.fill(hx - 1, hy - 1, 184, 24, 'rgba(16,16,20,0.63)');
    for (let i = 0; i < 9; i++) {
      const x = hx + 1 + i * 20;
      this.fill(x, hy + 1, 20, 20, i === p.selected ? '#fff' : '#3c3c44');
      this.fill(x + 1, hy + 2, 18, 18, i === p.selected ? '#77777f' : '#55555d');
      this.item(p.inv[i], x + 2, hy + 3);
    }
    if (!p.creative) {
      for (let i = 0; i < 10; i++) {
        const h = p.health - i * 2, f = p.hunger - i * 2;
        this.tile(h >= 2 ? T.HEART : h === 1 ? T.HEART_HALF : T.HEART_EMPTY, hx + i * 8, hy - 11, 9, 9);
        this.tile(f >= 2 ? T.FOOD : f === 1 ? T.FOOD_HALF : T.FOOD_EMPTY, hx + 173 - i * 8, hy - 11, 9, 9);
      }
      if (p.air < 10) for (let i = 0; i < p.air; i++) this.tile(T.BUBBLE, hx + 173 - i * 8, hy - 21, 9, 9);
    }
    const held = p.held();
    if (held && g.screen === SCR_NONE) this.text(ITEMS[held.id].name, cx, hy - (p.creative ? 12 : 32), 7, '#f0f0f0', 'center');
    if (g.targetMob && g.screen === SCR_NONE)
      this.text((g.targetMob.type === GRAZER ? 'Lanudo' : 'Brasal') + '  ' + Math.max(0, g.targetMob.health) + ' PV', cx, cy + 12, 7, '#fff', 'center');
    const lt = g.loadingText();
    if (lt && g.screen === SCR_NONE) this.text(lt, cx, cy - 30, 9, '#fff', 'center');
  }

  container(scr) {
    const p = this.g.player, { px, py } = this, inv = scr === SCR_INV;
    this.panel(px, py, PW, PH);
    this.text(inv ? (p.creative ? 'Inventário (criativo)' : 'Inventário') : scr === SCR_BENCH ? 'Bancada' : 'Fornalha', px + 8, py + 6, 8, '#30303a');
    if (inv) this.text('Montagem', px + 80, py + 14, 6.5, '#4a4a55');
    if (scr !== SCR_FURNACE) {
      const ax = inv ? px + 122 : px + 92, ay = inv ? py + 38 : py + 41;
      this.fill(ax, ay, 14, 4, '#6c6c76'); this.fill(ax + 12, ay - 3, 3, 10, '#6c6c76'); this.fill(ax + 15, ay - 1, 2, 6, '#6c6c76');
    } else {
      const f = this.furnace;
      this.fill(px + 53, py + 38, 14, 12, '#5a5a62');
      if (f.burn > 0 && f.burnMax > 0) { const k = f.burn / f.burnMax; this.fill(px + 54, py + 39 + 10 * (1 - k), 12, 10 * k, '#ff8a1e'); }
      this.fill(px + 76, py + 42, 28, 4, '#6c6c76');
      this.fill(px + 76, py + 42, 28 * f.cook / COOK_TIME, 4, '#fff');
    }
    let rows = 0;
    for (const s of this.slots) if (s.kind === K_PALETTE) rows = Math.max(rows, (s.y - (py - 12)) / 18 + 1);
    if (rows) { this.panel(px - 122, py - 26, 116, rows * 18 + 22); this.text('Todos os itens', px - 116, py - 23, 7, '#30303a'); }
    const mx = this.mx, my = this.my;
    let hover = null;
    for (const s of this.slots) {
      this.slotBox(s.x, s.y);
      this.item(s.get(), s.x, s.y);
      if (mx >= s.x - 1 && mx < s.x + 17 && my >= s.y - 1 && my < s.y + 17) { hover = s; this.fill(s.x, s.y, 16, 16, 'rgba(255,255,255,0.38)'); }
    }
    if (this.cursor) this.item(this.cursor, mx - 8, my - 8);
    else if (hover && hover.get()) {
      const name = ITEMS[hover.get().id].name, w = this.width(name, 7.5), tx = Math.min(mx + 8, this.gw - w - 6);
      this.fill(tx - 3, my - 13, w + 6, 12, 'rgba(24,24,34,0.94)');
      this.text(name, tx, my - 11, 7.5, '#fff');
    }
  }

  debug(p) {
    const g = this.g, w = g.world, bx = Math.floor(p.x), by = Math.floor(p.y), bz = Math.floor(p.z), h = w.gen.height(bx, bz);
    const fr = w.dayFraction(), hour = Math.floor((fr * 24 + 6) % 24), min = Math.floor((fr * 24 * 60) % 60), ly = Math.min(by + 1, H - 1);
    const pad = n => String(n).padStart(2, '0');
    const lines = [`Terralume  ${g.fps.toFixed(0)} fps`,
      `XYZ ${p.x.toFixed(1)} / ${p.y.toFixed(1)} / ${p.z.toFixed(1)}`,
      `Chunks ${w.chunks.size} (fila ${w.pending.size})  entidades ${w.entities.length}`,
      `Bioma: ${BIOME_NAME[w.gen.biome(bx, bz, h)]}  semente ${w.gen.seed}`,
      `Luz: céu ${w.sky(bx, ly, bz)} bloco ${w.blockLight(bx, ly, bz)}`,
      `Hora ${pad(hour)}:${pad(min)}  ${p.creative ? 'criativo' : 'sobrevivência'}`];
    lines.forEach((l, i) => this.text(l, 4, 4 + i * 10, 8));
  }
}
