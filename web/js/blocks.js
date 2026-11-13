// Conteúdo do jogo: blocos, itens, receitas e fornalha.
import { T } from './atlas.js';

export const SHAPE_NONE = 0, SHAPE_CUBE = 1, SHAPE_CROSS = 2, SHAPE_LIQUID = 3;
export const TOOL_NONE = 0, TOOL_PICK = 1, TOOL_AXE = 2, TOOL_SHOVEL = 3, TOOL_SWORD = 4;

export const B = {
  AIR: 0, STONE: 1, GRASS: 2, DIRT: 3, COBBLE: 4, PLANKS: 5, LOG: 6, LEAVES: 7, SAND: 8, GRAVEL: 9, WATER: 10, GLASS: 11,
  COAL_ORE: 12, IRON_ORE: 13, GOLD_ORE: 14, GEM_ORE: 15, BEDROCK: 16, SNOW_GRASS: 17, SNOW: 18, ICE: 19, CACTUS: 20,
  SANDSTONE: 21, STONE_BRICKS: 22, WORKBENCH: 23, FURNACE: 24, TORCH: 25, TALL_GRASS: 26, FLOWER_RED: 27,
  FLOWER_YELLOW: 28, PINE_LOG: 29, PINE_LEAVES: 30, IRON_BLOCK: 31, GOLD_BLOCK: 32, GEM_BLOCK: 33, LAMP: 34,
  DEAD_BUSH: 35, FURNACE_LIT: 36,
};
export const I = {
  STICK: 256, COAL: 257, IRON_INGOT: 258, GOLD_INGOT: 259, GEM: 260, RAW_MEAT: 261, COOKED_MEAT: 262, FRUIT: 263,
  PICK: 270, AXE: 274, SHOVEL: 278, SWORD: 282, // + (nível - 1)
};

export const BLOCKS = new Array(256);
function def(id, name, top, side, bottom, p = {}) {
  BLOCKS[id] = Object.assign({
    id, name, top, side, bottom, shape: SHAPE_CUBE, solid: true, opaque: true, translucent: false, cullSame: false,
    replaceable: false, needsGround: false, falls: false, opacity: 15, light: 0, hardness: 1, tool: TOOL_NONE,
    minTier: 0, drop: id, dropCount: 1,
  }, p);
}
const cube = (id, name, t, p) => def(id, name, t, t, t, p);
const plant = (id, name, t, p) => def(id, name, t, t, t, Object.assign(
  { shape: SHAPE_CROSS, solid: false, opaque: false, opacity: 0, needsGround: true, hardness: 0 }, p));
const NODROP = { drop: -1, dropCount: 0 };

def(B.AIR, 'Ar', 0, 0, 0, { shape: SHAPE_NONE, solid: false, opaque: false, opacity: 0, replaceable: true, drop: -1 });
cube(B.STONE, 'Pedra', T.STONE, { hardness: 1.5, tool: TOOL_PICK, minTier: 1, drop: B.COBBLE });
def(B.GRASS, 'Grama', T.GRASS_TOP, T.GRASS_SIDE, T.DIRT, { hardness: 0.6, tool: TOOL_SHOVEL, drop: B.DIRT });
cube(B.DIRT, 'Terra', T.DIRT, { hardness: 0.5, tool: TOOL_SHOVEL });
cube(B.COBBLE, 'Pedregulho', T.COBBLE, { hardness: 2, tool: TOOL_PICK, minTier: 1 });
cube(B.PLANKS, 'Tábuas', T.PLANKS, { hardness: 2, tool: TOOL_AXE });
def(B.LOG, 'Tronco', T.LOG_TOP, T.LOG_SIDE, T.LOG_TOP, { hardness: 2, tool: TOOL_AXE });
cube(B.LEAVES, 'Folhas', T.LEAVES, { hardness: 0.2, opaque: false, opacity: 1, ...NODROP });
cube(B.SAND, 'Areia', T.SAND, { hardness: 0.5, tool: TOOL_SHOVEL, falls: true });
cube(B.GRAVEL, 'Cascalho', T.GRAVEL, { hardness: 0.6, tool: TOOL_SHOVEL, falls: true });
cube(B.WATER, 'Água', T.WATER, { shape: SHAPE_LIQUID, solid: false, opaque: false, opacity: 2, translucent: true,
  cullSame: true, replaceable: true, hardness: -1, ...NODROP });
cube(B.GLASS, 'Vidro', T.GLASS, { hardness: 0.3, opaque: false, opacity: 0, cullSame: true, ...NODROP });
cube(B.COAL_ORE, 'Minério de carvão', T.COAL_ORE, { hardness: 3, tool: TOOL_PICK, minTier: 1, drop: I.COAL });
cube(B.IRON_ORE, 'Minério de ferro', T.IRON_ORE, { hardness: 3, tool: TOOL_PICK, minTier: 2 });
cube(B.GOLD_ORE, 'Minério de ouro', T.GOLD_ORE, { hardness: 3, tool: TOOL_PICK, minTier: 3 });
cube(B.GEM_ORE, 'Minério de cristal', T.GEM_ORE, { hardness: 3.5, tool: TOOL_PICK, minTier: 3, drop: I.GEM });
cube(B.BEDROCK, 'Rocha-mãe', T.BEDROCK, { hardness: -1, ...NODROP });
def(B.SNOW_GRASS, 'Grama nevada', T.SNOW, T.SNOW_GRASS_SIDE, T.DIRT, { hardness: 0.6, tool: TOOL_SHOVEL, drop: B.DIRT });
cube(B.SNOW, 'Neve', T.SNOW, { hardness: 0.3, tool: TOOL_SHOVEL });
cube(B.ICE, 'Gelo', T.ICE, { hardness: 0.5, tool: TOOL_PICK, opaque: false, opacity: 1, translucent: true, cullSame: true, ...NODROP });
def(B.CACTUS, 'Cacto', T.CACTUS_TOP, T.CACTUS_SIDE, T.CACTUS_TOP, { hardness: 0.4, needsGround: true });
cube(B.SANDSTONE, 'Arenito', T.SANDSTONE, { hardness: 0.8, tool: TOOL_PICK, minTier: 1 });
cube(B.STONE_BRICKS, 'Tijolos de pedra', T.STONE_BRICKS, { hardness: 1.5, tool: TOOL_PICK, minTier: 1 });
def(B.WORKBENCH, 'Bancada', T.WORKBENCH_TOP, T.WORKBENCH_SIDE, T.PLANKS, { hardness: 2.5, tool: TOOL_AXE });
def(B.FURNACE, 'Fornalha', T.FURNACE_SIDE, T.FURNACE_FRONT, T.FURNACE_SIDE, { hardness: 3.5, tool: TOOL_PICK, minTier: 1 });
plant(B.TORCH, 'Tocha', T.TORCH, { light: 14 });
plant(B.TALL_GRASS, 'Capim', T.TALL_GRASS, { replaceable: true, ...NODROP });
plant(B.FLOWER_RED, 'Flor vermelha', T.FLOWER_RED);
plant(B.FLOWER_YELLOW, 'Flor amarela', T.FLOWER_YELLOW);
def(B.PINE_LOG, 'Tronco de pinheiro', T.LOG_TOP, T.PINE_LOG_SIDE, T.LOG_TOP, { hardness: 2, tool: TOOL_AXE });
cube(B.PINE_LEAVES, 'Folhas de pinheiro', T.PINE_LEAVES, { hardness: 0.2, opaque: false, opacity: 1, ...NODROP });
cube(B.IRON_BLOCK, 'Bloco de ferro', T.IRON_BLOCK, { hardness: 5, tool: TOOL_PICK, minTier: 2 });
cube(B.GOLD_BLOCK, 'Bloco de ouro', T.GOLD_BLOCK, { hardness: 3, tool: TOOL_PICK, minTier: 3 });
cube(B.GEM_BLOCK, 'Bloco de cristal', T.GEM_BLOCK, { hardness: 5, tool: TOOL_PICK, minTier: 3 });
cube(B.LAMP, 'Lampião', T.LAMP, { hardness: 0.3, light: 15 });
plant(B.DEAD_BUSH, 'Arbusto seco', T.DEAD_BUSH, { drop: I.STICK });
def(B.FURNACE_LIT, 'Fornalha acesa', T.FURNACE_SIDE, T.FURNACE_FRONT_LIT, T.FURNACE_SIDE,
  { hardness: 3.5, tool: TOOL_PICK, minTier: 1, drop: B.FURNACE, light: 13 });
for (let i = 0; i < 256; i++) if (!BLOCKS[i]) BLOCKS[i] = BLOCKS[0];

export const isLiquid = id => id === B.WATER;

// ---------------------------------------------------------------- itens
export const TIER_SPEED = [1, 2, 4, 6, 9];
const TIER_NAME = ['', 'de madeira', 'de pedra', 'de ferro', 'de cristal'], TIER_USES = [0, 60, 132, 251, 1200];
export const ITEMS = new Array(512).fill(null);
function item(id, name, tile, p = {}) {
  ITEMS[id] = Object.assign({ id, name, tile, maxStack: 64, tool: TOOL_NONE, tier: 0, damage: 1, food: 0, durability: 0, isBlock: id < 256 }, p);
}
for (let i = 1; i < 256; i++) if (BLOCKS[i].id === i && i !== B.FURNACE_LIT) item(i, BLOCKS[i].name, BLOCKS[i].side);
item(I.STICK, 'Graveto', T.I_STICK);
item(I.COAL, 'Carvão', T.I_COAL);
item(I.IRON_INGOT, 'Barra de ferro', T.I_IRON);
item(I.GOLD_INGOT, 'Barra de ouro', T.I_GOLD);
item(I.GEM, 'Cristal', T.I_GEM);
item(I.RAW_MEAT, 'Carne crua', T.I_RAW_MEAT, { food: 3 });
item(I.COOKED_MEAT, 'Carne assada', T.I_COOKED_MEAT, { food: 8 });
item(I.FRUIT, 'Fruta', T.I_FRUIT, { food: 4 });
['Picareta', 'Machado', 'Pá', 'Espada'].forEach((n, k) => {
  const base = [I.PICK, I.AXE, I.SHOVEL, I.SWORD][k], tool = [TOOL_PICK, TOOL_AXE, TOOL_SHOVEL, TOOL_SWORD][k], dmg = [2, 3, 2, 4][k];
  for (let tier = 1; tier <= 4; tier++)
    item(base + tier - 1, n + ' ' + TIER_NAME[tier], T.I_TOOLS + k * 4 + tier - 1,
      { maxStack: 1, tool, tier, damage: dmg + tier - 1, durability: TIER_USES[tier] });
});

/** Pilha de itens: {id, count, dmg}. */
export const stack = (id, count = 1, dmg = 0) => ({ id, count, dmg });
export const stacksWith = (a, b) => !!a && !!b && a.id === b.id && ITEMS[a.id].maxStack > 1;

// ---------------------------------------------------------------- receitas
export const RECIPES = [];
function recipe(out, count, rows, keys) {
  const h = rows.length, w = rows[0].length, inp = [];
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) inp.push(keys[rows[y][x]] ?? -1);
  RECIPES.push({ w, h, inp, out, count });
}
recipe(B.PLANKS, 4, ['L'], { L: B.LOG });
recipe(B.PLANKS, 4, ['L'], { L: B.PINE_LOG });
recipe(I.STICK, 4, ['P', 'P'], { P: B.PLANKS });
recipe(B.WORKBENCH, 1, ['PP', 'PP'], { P: B.PLANKS });
recipe(B.FURNACE, 1, ['CCC', 'C C', 'CCC'], { C: B.COBBLE });
recipe(B.TORCH, 4, ['C', 'S'], { C: I.COAL, S: I.STICK });
recipe(B.STONE_BRICKS, 4, ['SS', 'SS'], { S: B.STONE });
recipe(B.SANDSTONE, 1, ['SS', 'SS'], { S: B.SAND });
recipe(B.SNOW, 1, ['SS', 'SS'], { S: B.SNOW_GRASS });
recipe(B.LAMP, 1, [' G ', 'GTG', ' G '], { G: B.GLASS, T: B.TORCH });
recipe(B.IRON_BLOCK, 1, ['III', 'III', 'III'], { I: I.IRON_INGOT });
recipe(B.GOLD_BLOCK, 1, ['III', 'III', 'III'], { I: I.GOLD_INGOT });
recipe(B.GEM_BLOCK, 1, ['III', 'III', 'III'], { I: I.GEM });
recipe(I.IRON_INGOT, 9, ['B'], { B: B.IRON_BLOCK });
recipe(I.GOLD_INGOT, 9, ['B'], { B: B.GOLD_BLOCK });
recipe(I.GEM, 9, ['B'], { B: B.GEM_BLOCK });
[B.PLANKS, B.COBBLE, I.IRON_INGOT, I.GEM].forEach((m, t) => {
  recipe(I.PICK + t, 1, ['MMM', ' S ', ' S '], { M: m, S: I.STICK });
  recipe(I.AXE + t, 1, ['MM', 'MS', ' S'], { M: m, S: I.STICK });
  recipe(I.SHOVEL + t, 1, ['M', 'S', 'S'], { M: m, S: I.STICK });
  recipe(I.SWORD + t, 1, ['M', 'M', 'S'], { M: m, S: I.STICK });
});

/** Procura a receita que casa com a grade (qualquer posição, aceita espelhamento). */
export function matchRecipe(grid, gw) {
  const gh = grid.length / gw;
  let x0 = gw, y0 = gh, x1 = -1, y1 = -1;
  for (let y = 0; y < gh; y++) for (let x = 0; x < gw; x++)
    if (grid[y * gw + x]) { x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
  if (x1 < 0) return null;
  const w = x1 - x0 + 1, h = y1 - y0 + 1;
  for (const r of RECIPES) {
    if (r.w !== w || r.h !== h) continue;
    for (let mirror = 0; mirror < 2; mirror++) {
      let ok = true;
      for (let y = 0; y < h && ok; y++) for (let x = 0; x < w; x++) {
        const s = grid[(y0 + y) * gw + x0 + x], want = r.inp[y * w + (mirror === 0 ? x : w - 1 - x)];
        if ((s ? s.id : -1) !== want) { ok = false; break; }
      }
      if (ok) return stack(r.out, r.count);
    }
  }
  return null;
}

// ---------------------------------------------------------------- fornalha
export const COOK_TIME = 160;
const SMELT = { [B.IRON_ORE]: I.IRON_INGOT, [B.GOLD_ORE]: I.GOLD_INGOT, [B.SAND]: B.GLASS, [B.COBBLE]: B.STONE,
  [B.LOG]: I.COAL, [B.PINE_LOG]: I.COAL, [I.RAW_MEAT]: I.COOKED_MEAT };
const FUEL = { [I.COAL]: 1600, [B.LOG]: 300, [B.PINE_LOG]: 300, [B.PLANKS]: 300, [B.WORKBENCH]: 300, [I.STICK]: 100 };

export class Furnace {
  constructor() { this.input = null; this.fuel = null; this.output = null; this.burn = 0; this.burnMax = 0; this.cook = 0; }
  canCook() {
    if (!this.input) return false;
    const out = SMELT[this.input.id];
    if (out === undefined) return false;
    return !this.output || (this.output.id === out && this.output.count < ITEMS[out].maxStack);
  }
  /** Avança um tick; devolve true se está acesa. */
  tick() {
    if (this.burn > 0) this.burn--;
    if (this.canCook()) {
      if (this.burn === 0 && this.fuel && FUEL[this.fuel.id]) {
        this.burn = this.burnMax = FUEL[this.fuel.id];
        if (--this.fuel.count <= 0) this.fuel = null;
      }
      if (this.burn > 0) {
        if (++this.cook >= COOK_TIME) {
          this.cook = 0;
          const out = SMELT[this.input.id];
          if (!this.output) this.output = stack(out, 1); else this.output.count++;
          if (--this.input.count <= 0) this.input = null;
        }
      } else this.cook = Math.max(0, this.cook - 2);
    } else this.cook = 0;
    return this.burn > 0;
  }
}
