// Laço principal: entrada, simulação, streaming de chunks, renderização WebGL2 e salvamento no navegador.
import { T, SIZE, u0, u1, v0, v1, buildAtlasPixels, atlasCanvas } from './atlas.js';
import { B, I, BLOCKS, ITEMS, TOOL_NONE, TIER_SPEED, SHAPE_CROSS, isLiquid, stack, Furnace } from './blocks.js';
import { Noise, H } from './worldgen.js';
import { World } from './world.js';
import { buildMesh, out as meshOut, CORN, FACE_SHADE } from './mesher.js';
import { Player, Mob, ItemEntity, GRAZER, STALKER, EYE } from './entity.js';
import { Ui, SCR_NONE, SCR_INV, SCR_BENCH, SCR_FURNACE, SCR_PAUSE, SCR_DEAD } from './ui.js';
import * as G from './gfx.js';

const LEVEL_KEY = 'terralume.level', DIST_KEY = 'terralume.dist';
const params = new URLSearchParams(location.search);

function fail(msg) {
  const e = document.getElementById('erro');
  e.style.display = 'flex'; e.textContent = msg;
  throw new Error(msg);
}

/** Armazenamento dos chunks alterados em IndexedDB (ou null se indisponível). */
async function openStore() {
  try {
    const db = await new Promise((res, rej) => {
      const r = indexedDB.open('terralume', 1);
      r.onupgradeneeded = () => r.result.createObjectStore('chunks');
      r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error);
    });
    const req = (mode, fn) => new Promise((res, rej) => {
      const r = fn(db.transaction('chunks', mode).objectStore('chunks'));
      r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error);
    });
    const keys = new Set(await req('readonly', s => s.getAllKeys()));
    return {
      keys,
      get: k => req('readonly', s => s.get(k)),
      put: (k, data) => { keys.add(k); req('readwrite', s => s.put(data, k)).catch(e => console.warn('Falha ao salvar chunk', e)); },
      clear: () => { keys.clear(); return req('readwrite', s => s.clear()); },
    };
  } catch (e) {
    console.warn('IndexedDB indisponível; o mundo não será salvo.', e);
    return null;
  }
}

class Game {
  constructor(gl, glCanvas, hudCanvas, store) {
    this.gl = gl; this.glCanvas = glCanvas; this.hud = hudCanvas; this.store = store;
    this.player = new Player();
    this.screen = SCR_PAUSE; this.started = false; this.noSave = false;
    this.noLock = params.has('nolock');
    this.keys = {}; this.lmb = false; this.rmb = false; this.lmbPressed = false; this.locked = false;
    this.showDebug = false; this.hideHud = false; this.sprinting = false;
    this.lastSpace = 0; this.target = null; this.targetMob = null;
    this.mineX = 0; this.mineY = -1; this.mineZ = 0; this.mineProgress = 0;
    this.breakCooldown = 0; this.useCooldown = 0; this.tickAcc = 0; this.fps = 0; this.daySmooth = 1;
    this.frameCount = 0; this.spawnTimer = 0; this.toast = ''; this.toastTime = 0; this.saveTimer = 0;
    this.skyColor = [0, 0, 0]; this.offsets = null; this.frustum = new G.Frustum();
    this.renderDist = Math.max(3, Math.min(14, parseInt(params.get('dist') || localStorage.getItem(DIST_KEY) || '7', 10) || 7));

    this.initGL();
    this.initWorld();
    this.resize();
    this.ui.open(SCR_PAUSE);
    this.initInput();
    let last = performance.now(), fpsT = last, fpsN = 0;
    const frame = now => {
      const dt = Math.max(0, Math.min(0.1, (now - last) / 1000));
      last = now; fpsN++;
      if (now - fpsT >= 500) { this.fps = fpsN * 1000 / (now - fpsT); fpsN = 0; fpsT = now; }
      this.resize();
      this.update(dt);
      this.render();
      this.frameCount++;
      requestAnimationFrame(frame);
    };
    requestAnimationFrame(frame);
  }

  // ---------------------------------------------------------------- inicialização
  initGL() {
    const gl = this.gl;
    this.chunkSh = G.program(gl, G.CHUNK_VS, G.CHUNK_FS);
    this.basicSh = G.program(gl, G.BASIC_VS, G.BASIC_FS);
    const px = buildAtlasPixels(), canvases = [atlasCanvas(px), atlasCanvas(px, 0.62), atlasCanvas(px, 0.82)];
    const rgba = new Uint8Array(SIZE * SIZE * 4);
    for (let i = 0; i < px.length; i++) { const p = px[i]; rgba[i * 4] = p >> 16; rgba[i * 4 + 1] = p >> 8; rgba[i * 4 + 2] = p; rgba[i * 4 + 3] = p >>> 24; }
    const tex = (w, data, wrap) => {
      const t = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, t);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, w, w, 0, gl.RGBA, gl.UNSIGNED_BYTE, data);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, wrap); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, wrap);
      return t;
    };
    this.atlasTex = tex(SIZE, rgba, gl.CLAMP_TO_EDGE);
    // nuvens: ruído periódico (toro) para a textura repetir sem costura
    const CS = 64, n = new Noise(77), cd = new Uint8Array(CS * CS * 4);
    for (let y = 0; y < CS; y++) for (let x = 0; x < CS; x++) {
      const a = x / CS * Math.PI * 2, b = y / CS * Math.PI * 2;
      const v = n.n3(Math.cos(a) * 1.6 + 5, Math.sin(a) * 1.6 + Math.cos(b) * 1.6, Math.sin(b) * 1.6 + 9)
        + 0.5 * n.n3(Math.cos(a) * 3.2, Math.sin(a) * 3.2 + Math.cos(b) * 3.2 + 3, Math.sin(b) * 3.2);
      const i = (y * CS + x) * 4;
      cd[i] = cd[i + 1] = cd[i + 2] = 255; cd[i + 3] = v > 0.16 ? 255 : 0;
    }
    this.cloudTex = tex(CS, cd, gl.REPEAT);
    this.batch = new G.Batch(gl);
    this.ui = new Ui(this, this.hud, canvases);
  }

  initWorld() {
    let level = null;
    try { level = JSON.parse(localStorage.getItem(LEVEL_KEY) || 'null'); } catch (e) { level = null; }
    let seed = level && Number.isFinite(level.seed) ? level.seed : null;
    if (seed === null) {
      const s = params.get('seed');
      seed = s === null ? (Math.random() * 0x7fffffff) | 0 : hashSeed(s);
    }
    this.world = new World(seed, this.store, () => new Worker(new URL('./genworker.js', import.meta.url), { type: 'module' }));
    const w = this.world, p = this.player;
    // ponto de nascimento: primeira coluna de terra firme perto da origem
    let sx = 0, sz = 0;
    for (let i = 0; i < 4000; i++) {
      const h = w.gen.height(sx, sz);
      if (h > 57 && h < 84 && !w.gen.carved(sx, h, sz, h)) break;
      sx += 7; if (i % 40 === 39) { sx = 0; sz += 11; }
    }
    this.spawn = [sx + 0.5, w.gen.height(sx, sz) + 1.05, sz + 0.5];
    if (level && level.player) {
      const q = level.player;
      w.time = level.time || 1500;
      Object.assign(p, { x: q.x, y: q.y, z: q.z, yaw: q.yaw, pitch: q.pitch, creative: !!q.creative, flying: !!q.flying,
        health: q.health, hunger: q.hunger, selected: q.selected || 0 });
      const fix = s => (s && ITEMS[s.id]) ? stack(s.id, s.count, s.dmg || 0) : null;
      (level.inv || []).forEach((s, i) => { if (i < 36) p.inv[i] = fix(s); });
      for (const [k, f] of Object.entries(level.furnaces || {})) {
        const fu = new Furnace();
        Object.assign(fu, { burn: f.burn, burnMax: f.burnMax, cook: f.cook, input: fix(f.input), fuel: fix(f.fuel), output: fix(f.output) });
        w.furnaces.set(k, fu);
      }
    } else {
      [p.x, p.y, p.z] = this.spawn;
    }
    if (params.has('creative')) p.creative = true;
    p.fallStart = p.y;
  }

  save() {
    if (this.noSave) return;
    const p = this.player, w = this.world, furnaces = {};
    for (const [k, f] of w.furnaces) furnaces[k] = { burn: f.burn, burnMax: f.burnMax, cook: f.cook, input: f.input, fuel: f.fuel, output: f.output };
    const level = { seed: w.gen.seed, time: w.time, inv: p.inv, furnaces,
      player: { x: p.x, y: p.y, z: p.z, yaw: p.yaw, pitch: p.pitch, creative: p.creative, flying: p.flying,
        health: p.dead ? 20 : p.health, hunger: p.hunger, selected: p.selected } };
    try { localStorage.setItem(LEVEL_KEY, JSON.stringify(level)); } catch (e) { console.warn('Falha ao salvar', e); }
    w.saveAll();
  }

  async newWorld() {
    const s = prompt('Criar um novo mundo? O mundo atual será APAGADO.\n\nSemente (deixe vazio para aleatória):', '');
    if (s === null) return;
    this.noSave = true;
    if (this.store) { try { await this.store.clear(); } catch (e) { /* segue */ } }
    const seed = s.trim() === '' ? (Math.random() * 0x7fffffff) | 0 : hashSeed(s.trim());
    localStorage.setItem(LEVEL_KEY, JSON.stringify({ seed }));
    location.href = location.pathname;
  }

  setRenderDist(d) {
    this.renderDist = Math.max(3, Math.min(14, d));
    this.offsets = null;
    localStorage.setItem(DIST_KEY, String(this.renderDist));
  }

  loadingText() {
    const c = this.world.chunk(Math.floor(this.player.x) >> 4, Math.floor(this.player.z) >> 4);
    return (c && c.lit && !c.dirty) ? '' : 'Gerando o mundo...';
  }

  // ---------------------------------------------------------------- entrada
  initInput() {
    const hud = this.hud;
    addEventListener('keydown', e => {
      if (e.ctrlKey || e.metaKey || e.altKey || /^F\d+$/.test(e.code)) return;
      e.preventDefault();
      this.keys[e.code] = true;
      if (!e.repeat) this.onKey(e.code);
    });
    addEventListener('keyup', e => { this.keys[e.code] = false; });
    addEventListener('blur', () => { this.keys = {}; this.lmb = this.rmb = false; });
    hud.addEventListener('contextmenu', e => e.preventDefault());
    hud.addEventListener('mousedown', e => {
      e.preventDefault();
      this.mouseMoved(e);
      if (this.screen !== SCR_NONE) { this.ui.click(e.button); return; }
      if (!this.locked && !this.noLock) { this.lock(); return; }
      if (e.button === 0) { this.lmb = true; this.lmbPressed = true; }
      if (e.button === 2) { this.rmb = true; this.useCooldown = 0; }
      if (e.button === 1) this.pickBlock();
    });
    addEventListener('mouseup', e => { if (e.button === 0) this.lmb = false; if (e.button === 2) this.rmb = false; });
    addEventListener('mousemove', e => {
      this.mouseMoved(e);
      if (this.screen === SCR_NONE && (this.locked || this.noLock)) {
        const p = this.player;
        p.yaw += e.movementX * 0.0026;
        p.pitch = Math.max(-1.55, Math.min(1.55, p.pitch - e.movementY * 0.0026));
      }
    });
    hud.addEventListener('wheel', e => {
      e.preventDefault();
      if (this.screen === SCR_NONE && e.deltaY !== 0) this.player.selected = (this.player.selected + Math.sign(e.deltaY) + 9) % 9;
    }, { passive: false });
    document.addEventListener('pointerlockchange', () => {
      this.locked = document.pointerLockElement === hud;
      if (!this.locked && this.screen === SCR_NONE && !this.noLock) this.setScreen(SCR_PAUSE);
    });
    document.addEventListener('pointerlockerror', () => {
      if (this.screen === SCR_NONE && !this.noLock) { this.setScreen(SCR_PAUSE); this.say('Clique em Continuar novamente'); }
    });
    addEventListener('pagehide', () => this.save());
    document.addEventListener('visibilitychange', () => { if (document.hidden) this.save(); });
  }

  mouseMoved(e) {
    const k = this.hud.width / this.hud.clientWidth / this.ui.scale;
    this.ui.mx = e.clientX * k; this.ui.my = e.clientY * k;
  }

  lock() {
    if (this.noLock) return;
    try { const r = this.hud.requestPointerLock(); if (r && r.catch) r.catch(() => {}); } catch (e) { /* tratado em pointerlockerror */ }
  }

  setScreen(s) {
    if (this.screen !== SCR_NONE && s === SCR_NONE) this.ui.closeContainers();
    this.screen = s;
    this.lmb = this.rmb = this.lmbPressed = false;
    if (s === SCR_NONE) { this.ui.slots = []; this.ui.buttons = []; this.lock(); }
    else {
      if (document.pointerLockElement) document.exitPointerLock();
      this.ui.open(s);
      if (s === SCR_PAUSE && this.started) this.save();
    }
  }

  resume() { this.started = true; this.setScreen(SCR_NONE); }
  say(s) { this.toast = s; this.toastTime = 2.5; }

  onKey(code) {
    const p = this.player;
    if (code === 'Escape') {
      if (this.screen === SCR_NONE) this.setScreen(SCR_PAUSE);
      else if (this.screen === SCR_PAUSE) { if (this.started) this.resume(); }
      else if (this.screen !== SCR_DEAD) this.setScreen(SCR_NONE);
      return;
    }
    if (code === 'KeyP') this.showDebug = !this.showDebug;
    if (code === 'KeyH') this.hideHud = !this.hideHud;
    if (this.screen === SCR_DEAD || this.screen === SCR_PAUSE) return;
    if (code === 'KeyE') { this.setScreen(this.screen === SCR_NONE ? SCR_INV : SCR_NONE); return; }
    if (this.screen !== SCR_NONE) return;
    if (/^Digit[1-9]$/.test(code)) p.selected = +code[5] - 1;
    if (code === 'KeyG') {
      p.creative = !p.creative;
      if (!p.creative) p.flying = false;
      this.say(p.creative ? 'Modo criativo' : 'Modo sobrevivência');
    }
    if (code === 'KeyF' && p.creative) p.flying = !p.flying;
    if (code === 'Space') {
      const now = performance.now();
      if (p.creative && now - this.lastSpace < 300) { p.flying = !p.flying; p.vy = 0; }
      this.lastSpace = now;
    }
    if (code === 'KeyQ') {
      const s = p.held();
      if (s) { const one = stack(s.id, 1, s.dmg); if (--s.count <= 0) p.inv[p.selected] = null; this.tossItem(one); }
    }
  }

  tossItem(s) {
    const p = this.player, e = new ItemEntity(s), sy = Math.sin(p.yaw), cy = Math.cos(p.yaw);
    e.x = p.x + sy * 0.4; e.y = p.y + 1.3; e.z = p.z - cy * 0.4;
    e.vx = sy * 5; e.vy = 2; e.vz = -cy * 5; e.age = -1.2;
    this.world.entities.push(e);
  }

  pickBlock() {
    const t = this.target, p = this.player;
    if (!t) return;
    let id = this.world.block(t.x, t.y, t.z);
    if (id === B.FURNACE_LIT) id = B.FURNACE;
    for (let i = 0; i < 9; i++) if (p.inv[i] && p.inv[i].id === id) { p.selected = i; return; }
    if (p.creative && ITEMS[id]) p.inv[p.selected] = stack(id, 1);
  }

  // ---------------------------------------------------------------- simulação
  update(dt) {
    this.streamChunks();
    if (this.toastTime > 0) this.toastTime -= dt;
    if (this.screen === SCR_PAUSE) return;
    const p = this.player, here = this.world.chunk(Math.floor(p.x) >> 4, Math.floor(p.z) >> 4), ready = !!here && here.lit;
    if (ready && !p.dead) {
      this.movePlayer(dt);
      if (this.screen === SCR_NONE) this.interact(dt); else { this.mineProgress = 0; this.target = null; this.targetMob = null; }
    }
    if (ready) this.updateEntities(dt);
    this.tickAcc += dt;
    for (let guard = 0; this.tickAcc >= 0.05 && guard < 5; guard++) { this.tickAcc -= 0.05; this.tick(ready); }
    this.daySmooth += (this.world.daylight() - this.daySmooth) * Math.min(1, dt * 2);
    this.saveTimer += dt;
    if (this.saveTimer > 15) { this.saveTimer = 0; this.save(); }
  }

  streamChunks() {
    const w = this.world, R = this.renderDist;
    if (!this.offsets) {
      const l = [];
      for (let z = -R - 1; z <= R + 1; z++) for (let x = -R - 1; x <= R + 1; x++) l.push([x, z]);
      this.offsets = l.sort((a, b) => a[0] * a[0] + a[1] * a[1] - b[0] * b[0] - b[1] * b[1]);
    }
    const pcx = Math.floor(this.player.x) >> 4, pcz = Math.floor(this.player.z) >> 4;
    let t0 = performance.now();
    while (performance.now() - t0 < 5 && w.integrate()) { /* integra enquanto houver tempo */ }
    for (const o of this.offsets) { if (w.pending.size >= 12) break; w.request(pcx + o[0], pcz + o[1]); }
    t0 = performance.now();
    for (const o of this.offsets) {
      if (Math.abs(o[0]) > R || Math.abs(o[1]) > R) continue;
      const c = w.chunk(pcx + o[0], pcz + o[1]);
      if (!c || !c.lit || !c.dirty || !w.neighborsLit(c)) continue;
      buildMesh(w, c);
      this.upload(c, 0, meshOut.opaque, meshOut.nOpaque);
      this.upload(c, 1, meshOut.trans, meshOut.nTrans);
      c.dirty = false;
      if (performance.now() - t0 > 6) break;
    }
    if (this.frameCount % 120 === 0) w.unloadFar(pcx, pcz, R + 3, c => this.freeMesh(c));
  }

  upload(c, pass, data, n) {
    const gl = this.gl;
    c.count[pass] = n / 8;
    if (n === 0) return;
    if (!c.vao[pass]) {
      c.vao[pass] = gl.createVertexArray(); c.vbo[pass] = gl.createBuffer();
      gl.bindVertexArray(c.vao[pass]);
      gl.bindBuffer(gl.ARRAY_BUFFER, c.vbo[pass]);
      gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 32, 0);
      gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 2, gl.FLOAT, false, 32, 12);
      gl.enableVertexAttribArray(2); gl.vertexAttribPointer(2, 3, gl.FLOAT, false, 32, 20);
      gl.bindVertexArray(null);
    }
    gl.bindBuffer(gl.ARRAY_BUFFER, c.vbo[pass]);
    gl.bufferData(gl.ARRAY_BUFFER, data.subarray(0, n), gl.STATIC_DRAW);
  }

  freeMesh(c) {
    for (let i = 0; i < 2; i++) if (c.vao[i]) { this.gl.deleteVertexArray(c.vao[i]); this.gl.deleteBuffer(c.vbo[i]); c.vao[i] = null; c.count[i] = 0; }
  }

  movePlayer(dt) {
    const p = this.player, k = this.keys, ctl = this.screen === SCR_NONE;
    let f = 0, s = 0;
    if (ctl) { if (k.KeyW) f += 1; if (k.KeyS) f -= 1; if (k.KeyD) s += 1; if (k.KeyA) s -= 1; }
    const sneak = ctl && !!k.KeyC, jump = ctl && !!k.Space;
    this.sprinting = ctl && !!(k.ShiftLeft || k.ShiftRight) && f > 0 && (p.creative || p.hunger > 6);
    let speed = p.flying ? (this.sprinting ? 24 : 11) : sneak ? 1.6 : this.sprinting ? 5.8 : 4.3;
    if (p.inWater && !p.flying) speed *= 0.7;
    const len = Math.sqrt(f * f + s * s);
    let tvx = 0, tvz = 0;
    if (len > 0) {
      const sy = Math.sin(p.yaw), cy = Math.cos(p.yaw);
      tvx = (sy * f + cy * s) / len * speed; tvz = (-cy * f + sy * s) / len * speed;
    }
    const a = Math.min(1, (p.onGround || p.flying ? 14 : p.inWater ? 6 : 3.5) * dt);
    p.vx += (tvx - p.vx) * a; p.vz += (tvz - p.vz) * a;
    if (p.flying) p.vy += ((jump ? 9 : 0) - (sneak ? 9 : 0) - p.vy) * Math.min(1, 12 * dt);
    else if (jump) {
      if (p.inWater) p.vy = Math.min(3.6, p.vy + 30 * dt);
      else if (p.onGround) { p.vy = 8.6; p.exhaustion += this.sprinting ? 0.12 : 0.04; }
    }
    const fell = p.physics(this.world, dt);
    if (fell > 3.5 && !p.creative) p.hurt(Math.round(fell - 3), p.x, p.z);
    if (p.y < -30) { p.health = 0; p.dead = true; }
    if (p.dead && this.screen !== SCR_DEAD) this.setScreen(SCR_DEAD);
  }

  canHarvest(b, held) {
    if (b.minTier === 0) return true;
    const it = held ? ITEMS[held.id] : null;
    return !!it && it.tool === b.tool && it.tier >= b.minTier;
  }
  breakTime(b, held) {
    if (b.hardness <= 0) return 0;
    const it = held ? ITEMS[held.id] : null;
    const speed = (it && it.tool === b.tool && b.tool !== TOOL_NONE) ? TIER_SPEED[it.tier] : 1;
    return this.canHarvest(b, held) ? b.hardness * 1.5 / speed : b.hardness * 5;
  }

  interact(dt) {
    const p = this.player, w = this.world;
    this.breakCooldown -= dt; this.useCooldown -= dt;
    const ex = p.x, ey = p.y + EYE, ez = p.z, cp = Math.cos(p.pitch);
    const dx = Math.sin(p.yaw) * cp, dy = Math.sin(p.pitch), dz = -Math.cos(p.yaw) * cp;
    const t = this.target = w.raycast(ex, ey, ez, dx, dy, dz, p.creative ? 6 : 4.8);
    let best = Math.min(3.6, t ? t.t : 1e9);
    this.targetMob = null;
    for (const e of w.entities) {
      if (!(e instanceof Mob)) continue;
      const d = e.rayHit(ex, ey, ez, dx, dy, dz);
      if (d >= 0 && d < best) { best = d; this.targetMob = e; }
    }
    if (this.lmbPressed && this.targetMob) {
      const h = p.held();
      this.targetMob.hurt(h ? ITEMS[h.id].damage : 1, p.x, p.z);
      if (h && ITEMS[h.id].tool !== TOOL_NONE) p.damageTool();
      p.exhaustion += 0.1;
      this.breakCooldown = 0.25;
    }
    this.lmbPressed = false;

    if (this.lmb && t && !this.targetMob && this.breakCooldown <= 0) {
      if (t.x !== this.mineX || t.y !== this.mineY || t.z !== this.mineZ) { this.mineX = t.x; this.mineY = t.y; this.mineZ = t.z; this.mineProgress = 0; }
      const b = BLOCKS[w.block(t.x, t.y, t.z)];
      if (b.hardness >= 0) {
        const time = p.creative ? 0 : this.breakTime(b, p.held());
        this.mineProgress = time <= 0 ? 1 : this.mineProgress + dt / time;
        if (this.mineProgress >= 1) {
          w.breakBlock(t.x, t.y, t.z, !p.creative && this.canHarvest(b, p.held()));
          if (b.hardness > 0) p.damageTool();
          p.exhaustion += 0.02;
          this.mineProgress = 0; this.mineY = -1;
          if (time <= 0.05) this.breakCooldown = 0.2;
        }
      }
    } else { this.mineProgress = 0; if (!this.lmb) this.mineY = -1; }

    if (this.rmb && this.useCooldown <= 0) {
      this.useCooldown = 0.24;
      const h = p.held(), it = h ? ITEMS[h.id] : null;
      if (t && !this.keys.KeyC) {
        const tid = w.block(t.x, t.y, t.z);
        if (tid === B.WORKBENCH) { this.setScreen(SCR_BENCH); return; }
        if (tid === B.FURNACE || tid === B.FURNACE_LIT) { this.ui.furnace = w.furnaceAt(t.x, t.y, t.z); this.setScreen(SCR_FURNACE); return; }
      }
      if (it && it.food > 0 && (p.hunger < 20 || p.creative)) {
        p.hunger = Math.min(20, p.hunger + it.food);
        if (!p.creative && --h.count <= 0) p.inv[p.selected] = null;
        this.useCooldown = 0.6;
      } else if (it && it.isBlock && t) {
        let px = t.x, py = t.y, pz = t.z;
        if (!BLOCKS[w.block(px, py, pz)].replaceable) { px += t.nx; py += t.ny; pz += t.nz; }
        const nb = BLOCKS[h.id];
        let ok = py >= 1 && py < H && BLOCKS[w.block(px, py, pz)].replaceable;
        if (ok && nb.needsGround && !w.solid(px, py - 1, pz)) ok = false;
        const hits = e => e.x + e.w / 2 > px && e.x - e.w / 2 < px + 1 && e.y + e.h > py && e.y < py + 1 && e.z + e.w / 2 > pz && e.z - e.w / 2 < pz + 1;
        if (ok && nb.solid) {
          if (hits(p)) ok = false;
          for (const e of w.entities) if (e instanceof Mob && hits(e)) ok = false;
        }
        if (ok) {
          w.setBlock(px, py, pz, h.id);
          if (!p.creative && --h.count <= 0) p.inv[p.selected] = null;
        }
      }
    }
  }

  updateEntities(dt) {
    const p = this.player, w = this.world, es = w.entities;
    for (let i = es.length - 1; i >= 0; i--) {
      const e = es[i], dx = e.x - p.x, dy = e.y - p.y, dz = e.z - p.z, d2 = dx * dx + dy * dy + dz * dz;
      if (!w.loaded(Math.floor(e.x), Math.floor(e.z)) || d2 > 110 * 110 || e.y < -20) { es.splice(i, 1); continue; }
      if (e instanceof ItemEntity) {
        e.age += dt;
        e.vx *= Math.pow(0.05, dt); e.vz *= Math.pow(0.05, dt);
        e.physics(w, dt);
        if (e.age > 0.5 && !p.dead && d2 < 2.2 * 2.2 && dy > -2.2 && p.give(e.stack) === null) { es.splice(i, 1); continue; }
        if (e.age > 300) es.splice(i, 1);
      } else if (e instanceof Mob) {
        e.update(w, p, dt);
        if (e.dead) {
          es.splice(i, 1);
          if (e.type === GRAZER) w.dropItem(e.x, e.y + 0.5, e.z, stack(I.RAW_MEAT, 1 + ((Math.random() * 2) | 0)));
          else {
            if (Math.random() < 0.5) w.dropItem(e.x, e.y + 0.5, e.z, stack(I.COAL, 1 + ((Math.random() * 2) | 0)));
            if (Math.random() < 0.17) w.dropItem(e.x, e.y + 0.5, e.z, stack(I.IRON_INGOT, 1));
          }
        }
      }
    }
  }

  /** Lógica em passo fixo de 20 ticks por segundo. */
  tick(ready) {
    const w = this.world, p = this.player;
    w.time++;
    w.tickFurnaces();
    w.tickWater();
    if (!ready) return;
    if (!p.creative && !p.dead) {
      p.exhaustion += this.sprinting ? 0.012 : 0.002;
      if (p.exhaustion >= 4) { p.exhaustion -= 4; p.hunger = Math.max(0, p.hunger - 1); }
      if (++p.regenTimer >= 80) {
        p.regenTimer = 0;
        if (p.hunger >= 18 && p.health < 20) { p.health++; p.exhaustion += 1.5; }
        else if (p.hunger === 0 && p.health > 1) { p.health--; p.hurtTime = 0.3; }
      }
      if (isLiquid(w.block(Math.floor(p.x), Math.floor(p.y + EYE), Math.floor(p.z)))) {
        if (++p.airTimer >= 20) {
          p.airTimer = 0;
          if (p.air > 0) p.air--; else { p.hurtTime = 0; p.hurt(2, p.x, p.z); }
        }
      } else { p.air = 10; p.airTimer = 0; }
      if (p.dead && this.screen !== SCR_DEAD) this.setScreen(SCR_DEAD);
    }
    if (++this.spawnTimer >= 20) { this.spawnTimer = 0; this.spawnMobs(); }
  }

  spawnMobs() {
    const w = this.world, p = this.player;
    let grazers = 0, stalkers = 0;
    for (const m of w.entities) {
      if (!(m instanceof Mob)) continue;
      if (m.type === GRAZER) grazers++; else {
        stalkers++;
        // o Brasal se desfaz sob o sol
        if (w.sky(Math.floor(m.x), Math.floor(m.y + 0.5), Math.floor(m.z)) * w.daylight() > 12) { m.hurtTime = 0; m.hurt(4, m.x, m.z); }
      }
    }
    for (let attempt = 0; attempt < 6; attempt++) {
      const ang = Math.random() * Math.PI * 2, dist = 22 + Math.random() * 34;
      const x = Math.floor(p.x + Math.cos(ang) * dist), z = Math.floor(p.z + Math.sin(ang) * dist), c = w.chunk(x >> 4, z >> 4);
      if (!c || !c.lit) continue;
      const hostile = attempt % 2 === 1;
      if (hostile ? (stalkers >= 8 || p.creative) : (grazers >= 8 || w.isNight())) continue;
      let y = hostile ? 6 + ((Math.random() * 96) | 0) : H - 2;
      while (y > 2 && !w.solid(x, y - 1, z)) y--;
      if (y <= 2 || w.solid(x, y, z) || w.solid(x, y + 1, z) || w.block(x, y, z) === B.WATER) continue;
      const ground = w.block(x, y - 1, z), light = w.lightLevel(x, y, z);
      if (hostile ? (light > 4 || !BLOCKS[ground].opaque) : ((ground !== B.GRASS && ground !== B.SNOW_GRASS) || light < 10)) continue;
      const m = new Mob(hostile ? STALKER : GRAZER);
      m.x = x + 0.5; m.y = y; m.z = z + 0.5; m.yaw = Math.random() * 6.28; m.fallStart = y;
      w.entities.push(m);
      if (hostile) stalkers++; else grazers++;
    }
  }

  respawn() {
    const p = this.player;
    Object.assign(p, { dead: false, health: 20, hunger: 20, air: 10, exhaustion: 0, vx: 0, vy: 0, vz: 0, hurtTime: 0 });
    [p.x, p.y, p.z] = this.spawn; p.fallStart = p.y;
    this.setScreen(SCR_NONE);
  }

  // ---------------------------------------------------------------- renderização
  resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const w = Math.max(1, Math.floor(this.glCanvas.clientWidth * dpr)), h = Math.max(1, Math.floor(this.glCanvas.clientHeight * dpr));
    if (this.glCanvas.width !== w || this.glCanvas.height !== h) { this.glCanvas.width = this.hud.width = w; this.glCanvas.height = this.hud.height = h; }
  }

  brightness(x, y, z) {
    const w = this.world, bx = Math.floor(x), by = Math.floor(y), bz = Math.floor(z);
    return 0.03 + 0.97 * Math.pow(Math.max(w.sky(bx, by, bz) / 15 * this.daySmooth, w.blockLight(bx, by, bz) / 15), 1.7);
  }

  box(m, x0, y0, z0, x1, y1, z1, tTop, tSide, tBottom, r, g, b) {
    const px = [0, 0, 0, 0], py = [0, 0, 0, 0], pz = [0, 0, 0, 0], bt = this.batch;
    for (let f = 0; f < 6; f++) {
      const tile = f === 2 ? tTop : f === 3 ? tBottom : tSide, a0 = u0(tile), a1 = u1(tile), b0 = v0(tile), b1 = v1(tile);
      const sh = FACE_SHADE[f], cn = CORN[f];
      for (let k = 0; k < 4; k++) {
        const x = cn[k * 3] === 1 ? x1 : x0, y = cn[k * 3 + 1] === 1 ? y1 : y0, z = cn[k * 3 + 2] === 1 ? z1 : z0;
        px[k] = m[0] * x + m[4] * y + m[8] * z + m[12]; py[k] = m[1] * x + m[5] * y + m[9] * z + m[13]; pz[k] = m[2] * x + m[6] * y + m[10] * z + m[14];
      }
      for (const k of [0, 1, 2, 0, 2, 3]) bt.v(px[k], py[k], pz[k], (k === 1 || k === 2) ? a1 : a0, k < 2 ? b1 : b0, r * sh, g * sh, b * sh, 1);
    }
  }

  render() {
    const gl = this.gl, p = this.player, w = this.world, bt = this.batch, R = this.renderDist;
    const camX = p.x, camY = p.y + EYE, camZ = p.z, day = this.daySmooth, frac = w.dayFraction();
    const sunH = Math.sin(frac * Math.PI * 2), dusk = Math.max(0, 1 - Math.abs(sunH) * 4) * 0.7, sky = this.skyColor;
    sky[0] = Math.max(0.02, (0.50 * day - 0.10) * (1 - dusk) + 1.00 * dusk * day);
    sky[1] = Math.max(0.04, (0.69 * day - 0.13) * (1 - dusk) + 0.50 * dusk * day);
    sky[2] = Math.max(0.06, (0.93 * day - 0.13) * (1 - dusk) + 0.25 * dusk * day);
    const underwater = isLiquid(w.block(Math.floor(camX), Math.floor(camY), Math.floor(camZ)));
    let fogStart = R * 16 * 0.55, fogEnd = R * 16 - 6, fog = sky;
    if (underwater) { fogStart = 0; fogEnd = 26; fog = [0.05 * day, 0.22 * day, 0.5 * day]; }

    const cw = this.glCanvas.width, ch = this.glCanvas.height;
    gl.viewport(0, 0, cw, ch);
    gl.clearColor(fog[0], fog[1], fog[2], 1);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    const proj = G.perspective((this.sprinting ? 76 : 70) * Math.PI / 180, cw / ch, 0.05, 900);
    const vp = G.mul(proj, G.mul(G.rotX(-p.pitch), G.rotY(p.yaw)));
    this.frustum.set(vp);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, this.atlasTex);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);

    // sol e lua
    const bs = this.basicSh;
    gl.useProgram(bs.id);
    gl.uniformMatrix4fv(bs.loc('uMVP'), false, vp); gl.uniform1i(bs.loc('uTex'), 0);
    gl.uniform2f(bs.loc('uFog'), 0, 0); gl.uniform3f(bs.loc('uFogColor'), fog[0], fog[1], fog[2]);
    gl.disable(gl.DEPTH_TEST); gl.disable(gl.CULL_FACE);
    if (!underwater) {
      const a = frac * Math.PI * 2;
      for (let i = 0; i < 2; i++) {
        const sg = i === 0 ? 1 : -1, dxs = Math.cos(a) * sg, dys = Math.sin(a) * sg;
        if (dys < -0.2) continue;
        const tile = i === 0 ? T.SUN : T.MOON, s = i === 0 ? 60 : 42, cx = dxs * 400, cy = dys * 400, ux = dys * s, uy = -dxs * s;
        const a0 = u0(tile), a1 = u1(tile), b0 = v0(tile), b1 = v1(tile);
        bt.v(cx - ux, cy - uy, -s, a0, b0, 1, 1, 1, 1); bt.v(cx - ux, cy - uy, s, a1, b0, 1, 1, 1, 1); bt.v(cx + ux, cy + uy, s, a1, b1, 1, 1, 1, 1);
        bt.v(cx - ux, cy - uy, -s, a0, b0, 1, 1, 1, 1); bt.v(cx + ux, cy + uy, s, a1, b1, 1, 1, 1, 1); bt.v(cx + ux, cy + uy, -s, a0, b1, 1, 1, 1, 1);
      }
      bt.draw(gl.TRIANGLES);
    }
    gl.enable(gl.DEPTH_TEST); gl.enable(gl.CULL_FACE);

    // terreno opaco
    const pcx = Math.floor(p.x) >> 4, pcz = Math.floor(p.z) >> 4, visible = [];
    for (const c of w.chunks.values()) {
      if (c.count[0] === 0 && c.count[1] === 0) continue;
      if (Math.abs(c.cx - pcx) > R || Math.abs(c.cz - pcz) > R) continue;
      const ox = c.cx * 16 - camX, oz = c.cz * 16 - camZ;
      if (this.frustum.aabb(ox, -camY, oz, ox + 16, H - camY, oz + 16)) visible.push(c);
    }
    const cs = this.chunkSh;
    gl.useProgram(cs.id);
    gl.uniformMatrix4fv(cs.loc('uVP'), false, vp); gl.uniform1i(cs.loc('uTex'), 0); gl.uniform1f(cs.loc('uDay'), day);
    gl.uniform2f(cs.loc('uFog'), fogStart, fogEnd); gl.uniform3f(cs.loc('uFogColor'), fog[0], fog[1], fog[2]);
    gl.uniform1f(cs.loc('uAlphaTest'), 0.5);
    gl.disable(gl.BLEND);
    const offLoc = cs.loc('uOffset');
    for (const c of visible) {
      if (c.count[0] === 0) continue;
      gl.uniform3f(offLoc, c.cx * 16 - camX, -camY, c.cz * 16 - camZ);
      gl.bindVertexArray(c.vao[0]); gl.drawArrays(gl.TRIANGLES, 0, c.count[0]);
    }
    gl.enable(gl.BLEND);

    // entidades, seleção e rachaduras
    gl.useProgram(bs.id);
    gl.uniform2f(bs.loc('uFog'), fogStart, fogEnd);
    for (const e of w.entities) this.drawEntity(e, camX, camY, camZ);
    const t = this.target, showT = t && this.screen === SCR_NONE && !this.hideHud;
    if (showT && this.mineProgress > 0) {
      const ct = T.CRACK + Math.min(3, (this.mineProgress * 4) | 0);
      this.box(G.translation(t.x - camX, t.y - camY, t.z - camZ), -0.003, -0.003, -0.003, 1.003, 1.003, 1.003, ct, ct, ct, 1, 1, 1);
    }
    bt.draw(gl.TRIANGLES);
    if (showT) {
      const x0 = t.x - camX - 0.004, y0 = t.y - camY - 0.004, z0 = t.z - camZ - 0.004, x1 = x0 + 1.008, y1 = y0 + 1.008, z1 = z0 + 1.008;
      const e = [x0, y0, z0, x1, y0, z0, x0, y0, z1, x1, y0, z1, x0, y1, z0, x1, y1, z0, x0, y1, z1, x1, y1, z1,
        x0, y0, z0, x0, y1, z0, x1, y0, z0, x1, y1, z0, x0, y0, z1, x0, y1, z1, x1, y0, z1, x1, y1, z1,
        x0, y0, z0, x0, y0, z1, x1, y0, z0, x1, y0, z1, x0, y1, z0, x0, y1, z1, x1, y1, z0, x1, y1, z1];
      const u = u0(T.WHITE) + 0.01, v = v0(T.WHITE) + 0.01;
      for (let i = 0; i < e.length; i += 3) bt.v(e[i], e[i + 1], e[i + 2], u, v, 0, 0, 0, 0.75);
      bt.draw(gl.LINES);
    }

    // nuvens
    gl.disable(gl.CULL_FACE);
    gl.bindTexture(gl.TEXTURE_2D, this.cloudTex);
    gl.uniform2f(bs.loc('uFog'), 180, 620);
    const cyl = 118.5 - camY, CR = 640, texel = 768, scroll = (w.time % 240000) * 0.02;
    const mod = (a, n) => ((a % n) + n) % n;
    const cu = mod(camX + scroll, texel) / texel, cv = mod(camZ, texel) / texel, cr = CR / texel, cb = 0.25 + 0.75 * day;
    bt.v(-CR, cyl, -CR, cu - cr, cv - cr, cb, cb, cb, 0.82); bt.v(CR, cyl, -CR, cu + cr, cv - cr, cb, cb, cb, 0.82); bt.v(CR, cyl, CR, cu + cr, cv + cr, cb, cb, cb, 0.82);
    bt.v(-CR, cyl, -CR, cu - cr, cv - cr, cb, cb, cb, 0.82); bt.v(CR, cyl, CR, cu + cr, cv + cr, cb, cb, cb, 0.82); bt.v(-CR, cyl, CR, cu - cr, cv + cr, cb, cb, cb, 0.82);
    bt.draw(gl.TRIANGLES);
    gl.bindTexture(gl.TEXTURE_2D, this.atlasTex);
    gl.enable(gl.CULL_FACE);

    // água e gelo, de trás para frente
    const d2 = c => (c.cx - pcx) * (c.cx - pcx) + (c.cz - pcz) * (c.cz - pcz);
    visible.sort((a, b) => d2(b) - d2(a));
    gl.useProgram(cs.id);
    gl.uniform1f(cs.loc('uAlphaTest'), 0.01);
    for (const c of visible) {
      if (c.count[1] === 0) continue;
      gl.uniform3f(offLoc, c.cx * 16 - camX, -camY, c.cz * 16 - camZ);
      gl.bindVertexArray(c.vao[1]); gl.drawArrays(gl.TRIANGLES, 0, c.count[1]);
    }
    gl.bindVertexArray(null);

    this.ui.render(underwater);
  }

  drawEntity(e, camX, camY, camZ) {
    const ex = e.x - camX, ey = e.y - camY, ez = e.z - camZ;
    if (!this.frustum.aabb(ex - 1.5, ey - 1, ez - 1.5, ex + 1.5, ey + 2.5, ez + 1.5)) return;
    const l = this.brightness(e.x, e.y + 0.5, e.z);
    if (e instanceof ItemEntity) {
      const it = ITEMS[e.stack.id], b = it.isBlock ? BLOCKS[it.id] : null;
      const m = G.mul(G.translation(ex, ey + 0.12 + 0.06 * Math.sin(e.age * 2.5), ez), G.rotY(e.age * 1.6));
      if (b && b.shape !== SHAPE_CROSS) this.box(m, -0.14, 0, -0.14, 0.14, 0.28, 0.14, b.top, b.side, b.bottom, l, l, l);
      else this.box(m, -0.17, 0, -0.012, 0.17, 0.34, 0.012, it.tile, it.tile, T.WHITE, l * 1.3, l * 1.3, l * 1.3);
      return;
    }
    const m = G.mul(G.translation(ex, ey, ez), G.rotY(e.yaw));
    const hr = e.hurtTime > 0 ? 1.6 : 1, hg = e.hurtTime > 0 ? 0.5 : 1, swing = Math.sin(e.walkAnim) * 0.6, F = T.FUR, Wt = T.WHITE, C = T.COBBLE;
    const leg = (lx, ly, lz, i) => G.mul(m, G.mul(G.translation(lx, ly, lz), G.rotX((i === 0 || i === 3) ? swing : -swing)));
    if (e.type === GRAZER) {
      // Lanudo: corpo felpudo azul-petróleo, cabeça clara e dois chifres
      const r = 0.36 * l * hr, g = 0.66 * l * hg, b = 0.68 * l * hg, hr2 = 0.93 * l * hr, hg2 = 0.86 * l * hg, hb2 = 0.72 * l * hg;
      this.box(m, -0.38, 0.42, -0.55, 0.38, 1.08, 0.5, F, F, F, r, g, b);
      this.box(m, -0.24, 0.62, 0.5, 0.24, 1.12, 0.92, F, F, F, hr2, hg2, hb2);
      this.box(m, -0.34, 1.08, 0.56, -0.22, 1.34, 0.68, F, F, F, 0.95 * l, 0.62 * l, 0.3 * l);
      this.box(m, 0.22, 1.08, 0.56, 0.34, 1.34, 0.68, F, F, F, 0.95 * l, 0.62 * l, 0.3 * l);
      this.box(m, -0.26, 0.9, 0.92, -0.14, 1.0, 0.94, Wt, Wt, Wt, 0.08, 0.08, 0.1);
      this.box(m, 0.14, 0.9, 0.92, 0.26, 1.0, 0.94, Wt, Wt, Wt, 0.08, 0.08, 0.1);
      for (let i = 0; i < 4; i++) this.box(leg(i % 2 === 0 ? -0.26 : 0.26, 0.44, i < 2 ? -0.4 : 0.34, i), -0.09, -0.44, -0.09, 0.09, 0, 0.09, F, F, F, hr2, hg2, hb2);
    } else {
      // Brasal: carapaça de rocha escura com olhos e fendas incandescentes
      const r = 0.26 * l * hr + 0.04, g = 0.24 * l * hg + 0.03, b = 0.3 * l * hg + 0.04;
      this.box(m, -0.42, 0.28, -0.42, 0.42, 0.8, 0.42, C, C, C, r * 1.6, g * 1.6, b * 1.6);
      this.box(m, -0.26, 0.8, -0.3, 0.26, 1.0, 0.2, C, C, C, r * 1.3, g * 1.3, b * 1.3);
      this.box(m, -0.3, 0.52, 0.42, -0.12, 0.66, 0.44, Wt, Wt, Wt, 1.5, 0.75, 0.1);
      this.box(m, 0.12, 0.52, 0.42, 0.3, 0.66, 0.44, Wt, Wt, Wt, 1.5, 0.75, 0.1);
      this.box(m, -0.2, 0.34, 0.42, 0.2, 0.4, 0.44, Wt, Wt, Wt, 1.5, 0.45, 0.05);
      this.box(m, -0.06, 1.0, -0.2, 0.06, 1.22, -0.08, Wt, Wt, Wt, 1.5, 0.6, 0.1);
      for (let i = 0; i < 4; i++) this.box(leg(i % 2 === 0 ? -0.36 : 0.36, 0.3, i < 2 ? -0.3 : 0.3, i), -0.1, -0.3, -0.1, 0.1, 0, 0.1, C, C, C, r, g, b);
    }
  }
}

function hashSeed(s) {
  if (/^-?\d+$/.test(s)) return parseInt(s, 10) | 0;
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0;
  return h;
}

const glCanvas = document.getElementById('gl'), hudCanvas = document.getElementById('hud');
const gl = glCanvas.getContext('webgl2', { antialias: false, alpha: false, powerPreference: 'high-performance' });
if (!gl) fail('Seu navegador não tem suporte a WebGL 2. Atualize o navegador ou ative a aceleração de hardware.');
openStore().then(store => { window.terralume = new Game(gl, glCanvas, hudCanvas, store); })
  .catch(e => { console.error(e); fail('Erro ao iniciar o jogo: ' + e.message); });
