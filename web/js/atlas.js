// Atlas de texturas 256x256 (tiles 16x16) desenhado inteiramente por código.
export const TILE = 16, GRID = 16, SIZE = 256;

const names = ['STONE', 'GRASS_TOP', 'GRASS_SIDE', 'DIRT', 'COBBLE', 'PLANKS', 'LOG_SIDE', 'LOG_TOP', 'LEAVES', 'SAND',
  'GRAVEL', 'WATER', 'GLASS', 'COAL_ORE', 'IRON_ORE', 'GOLD_ORE', 'GEM_ORE', 'BEDROCK', 'SNOW', 'SNOW_GRASS_SIDE', 'ICE',
  'CACTUS_SIDE', 'CACTUS_TOP', 'SANDSTONE', 'STONE_BRICKS', 'WORKBENCH_TOP', 'WORKBENCH_SIDE', 'FURNACE_FRONT',
  'FURNACE_SIDE', 'TORCH', 'TALL_GRASS', 'FLOWER_RED', 'FLOWER_YELLOW', 'PINE_LOG_SIDE', 'PINE_LEAVES', 'IRON_BLOCK',
  'GOLD_BLOCK', 'GEM_BLOCK', 'LAMP', 'DEAD_BUSH', 'FURNACE_FRONT_LIT', 'WHITE', 'CRACK', 'CRACK1', 'CRACK2', 'CRACK3',
  'SUN', 'MOON', 'I_STICK', 'I_COAL', 'I_IRON', 'I_GOLD', 'I_GEM', 'I_RAW_MEAT', 'I_COOKED_MEAT', 'I_FRUIT'];
export const T = {};
names.forEach((n, i) => { T[n] = i; });
T.I_TOOLS = 56; T.HEART = 72; T.HEART_HALF = 73; T.HEART_EMPTY = 74; T.FOOD = 75; T.FOOD_HALF = 76; T.FOOD_EMPTY = 77;
T.BUBBLE = 78; T.FUR = 79;

export const u0 = t => ((t % GRID) * TILE + 0.02) / SIZE;
export const u1 = t => ((t % GRID) * TILE + TILE - 0.02) / SIZE;
export const v0 = t => (((t / GRID) | 0) * TILE + 0.02) / SIZE;
export const v1 = t => (((t / GRID) | 0) * TILE + TILE - 0.02) / SIZE;

function hash(x, y, s) {
  let h = (Math.imul(x, 374761393) + Math.imul(y, 668265263) + Math.imul(s, 1274126177)) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return (h ^ (h >>> 16)) & 0x7fffffff;
}
const rnd = (x, y, s) => (hash(x, y, s) & 0xffff) / 65535;
const cl = v => v < 0 ? 0 : v > 255 ? 255 : v | 0;
const rgba = (r, g, b, a) => ((cl(a) << 24) | (cl(r) << 16) | (cl(g) << 8) | cl(b)) >>> 0;
const rgb = (r, g, b) => rgba(r, g, b, 255);
const mul = (c, f) => ((c & 0xff000000) | (cl(((c >> 16) & 255) * f) << 16) | (cl(((c >> 8) & 255) * f) << 8) | cl((c & 255) * f)) >>> 0;
const grain = (c, x, y, s, amt) => mul(c, 1 - amt + 2 * amt * rnd(x, y, s));

const stone = (x, y) => mul(rgb(128, 130, 134), 0.9 + 0.12 * rnd(x, y, 1) + (rnd((x / 3) | 0, ((y + ((x / 5) | 0)) / 2) | 0, 2) > 0.7 ? -0.1 : 0));
const dirt = (x, y) => rnd(x, y, 4) > 0.93 ? rgb(150, 140, 128) : grain(rgb(121, 85, 58), x, y, 3, 0.13);
const grass = (x, y) => grain(rgb(96, 160, 62), x, y, 5, 0.16);
const snow = (x, y) => grain(rgb(240, 246, 252), x, y, 6, 0.04);
const sand = (x, y) => grain(rgb(222, 207, 150), x, y, 7, 0.07);
const wood = (x, y, s) => mul(rgb(176, 138, 84), 0.86 + 0.1 * rnd(0, y >> 2, s) + 0.08 * rnd(x, y, s + 1));
const planks = (x, y) => (y % 4 === 3 || x === hash(y >> 2, 0, 11) % 16) ? mul(wood(x, y, 12), 0.68) : wood(x, y, 12);
function cobble(x, y) {
  const sx = (x + ((y >> 2) % 2) * 3) % 16;
  if (y % 4 === 0 || sx % 5 === 0) return grain(rgb(78, 80, 84), x, y, 13, 0.1);
  return mul(rgb(140, 141, 145), 0.8 + 0.3 * rnd((sx / 5) | 0, y >> 2, 14) + 0.08 * rnd(x, y, 15));
}
const ore = (r, g, b, s) => (x, y) => (rnd((x + 1) >> 1, (y + s) >> 1, s) > 0.74 && x > 0 && x < 15 && y > 0 && y < 15)
  ? grain(rgb(r, g, b), x, y, s + 1, 0.15) : stone(x, y);
const metal = (r, g, b) => (x, y) => mul(rgb(r, g, b), (x === 0 || y === 0) ? 1.18 : (x === 15 || y === 15) ? 0.7 : (x + y) % 7 === 0 ? 1.08 : 1);
const leaves = (r, g, b, s) => (x, y) => rnd(x, y, s) > 0.8 ? 0 : mul(rgb(r, g, b), 0.7 + 0.5 * rnd(x >> 1, y >> 1, s + 1) * rnd(x, y, s + 2) + 0.15);
const logSide = (r, g, b, s) => (x, y) => mul(rgb(r, g, b), 0.8 + 0.25 * rnd(x, 0, s) + 0.1 * rnd(x, (y / 3) | 0, s + 1));
const flower = (r, g, b) => (x, y) => {
  const dx = x - 7.5, dy = y - 5, d = dx * dx + dy * dy;
  if (d < 2) return rgb(250, 225, 90);
  if (d < 11 && Math.abs(dx) + Math.abs(dy) < 4.6) return mul(rgb(r, g, b), 0.85 + 0.3 * rnd(x, y, 40));
  if ((x === 7 || x === 8) && y > 6) return rgb(58, 130, 46);
  if ((y === 11 && (x === 6 || x === 9)) || (y === 10 && (x === 5 || x === 10))) return rgb(70, 150, 56);
  return 0;
};
const disc = (cx, cy, rx, ry, r, g, b) => (x, y) => {
  const dx = (x - cx) / rx, dy = (y - cy) / ry, d = dx * dx + dy * dy;
  if (d > 1) return 0;
  const f = 1.15 - 0.45 * d - 0.25 * (dx + dy) * 0.5;
  return mul(rgb(r, g, b), d > 0.72 ? f * 0.7 : f);
};
const ingot = (r, g, b) => (x, y) => {
  const lx = x - (((15 - y) / 3) | 0);
  if (y < 5 || y > 11 || lx < 1 || lx > 10) return 0;
  return mul(rgb(r, g, b), (y === 5 || lx === 1) ? 1.25 : (y === 11 || lx === 10) ? 0.65 : 1);
};
const heart = mode => (x, y) => {
  const u = (x - 7.5) / 6.5, v = -(y - 7) / 6.5, a = u * u + v * v - 0.55;
  if (!(a * a * a - u * u * v * v * v * 1.6 < 0)) return 0;
  if (!(mode === 0 || (mode === 1 && x < 8))) return rgb(52, 28, 34);
  return mul(rgb(226, 44, 60), (x < 6 && y < 6 && y > 3) ? 1.35 : 1 - 0.03 * y);
};
const food = mode => {
  const base = disc(7.5, 8, 6, 6, 236, 160, 52);
  return (x, y) => {
    const c = base(x, y);
    if (c === 0) return (y < 4 && y > 0 && x === 8 + (((3 - y) / 2) | 0)) ? rgb(70, 140, 50) : 0;
    return (mode === 0 || (mode === 1 && x < 8)) ? c : rgb(58, 44, 30);
  };
};

const TOOL_ART = [
  ['................', '.....HHHHHH.....', '....HhhhhhhHH...', '.....HHHHhhhH...', '.........shhH...', '........sSHhhH..',
    '.......sS..HhH..', '......sS...HhH..', '.....sS....HhH..', '....sS......H...', '...sS...........', '..sS............',
    '.sS.............', '.S..............', '................', '................'],
  ['................', '.........HH.....', '........HhhH....', '.......HhhhhH...', '......HhhhhsH...', '......HhhhsS....',
    '.......HHsS.H...', '........sS......', '.......sS.......', '......sS........', '.....sS.........', '....sS..........',
    '...sS...........', '..sS............', '..S.............', '................'],
  ['................', '...........HHH..', '..........HhhhH.', '.........HhhhhH.', '.........HhhhhH.', '.........sHhhH..',
    '........sSHHH...', '.......sS.......', '......sS........', '.....sS.........', '....sS..........', '...sS...........',
    '..sS............', '.sS.............', '.S..............', '................'],
  ['................', '.............HH.', '............HhH.', '...........HhH..', '..........HhH...', '.........HhH....',
    '........HhH.....', '..S....HhH......', '..sS..HhH.......', '...sSHhH........', '....sSH.........', '...sSsS.........',
    '..sS..sS........', '.sS....S........', '.S..............', '................']];
const STICK_ART = ['................', '................', '............sS..', '...........sS...', '..........sS....',
  '.........sS.....', '........sS......', '.......sS.......', '......sS........', '.....sS.........', '....sS..........',
  '...sS...........', '..sS............', '................', '................', '................'];
const TIER_COLOR = [rgb(176, 138, 84), rgb(150, 152, 156), rgb(226, 226, 232), rgb(86, 226, 214)];
const art = (rows, head) => (x, y) => {
  const c = rows[y][x];
  return c === 'h' ? head : c === 'H' ? mul(head, 0.62) : c === 's' ? rgb(132, 96, 56) : c === 'S' ? rgb(88, 62, 36) : 0;
};

/** Gera o atlas; devolve Uint32Array ARGB de SIZE*SIZE. */
export function buildAtlasPixels() {
  const px = new Uint32Array(SIZE * SIZE);
  const tile = (idx, g) => {
    const ox = (idx % GRID) * TILE, oy = ((idx / GRID) | 0) * TILE;
    for (let y = 0; y < TILE; y++) for (let x = 0; x < TILE; x++) px[(oy + y) * SIZE + ox + x] = g(x, y);
  };
  tile(T.STONE, stone);
  tile(T.GRASS_TOP, grass);
  tile(T.GRASS_SIDE, (x, y) => y < 3 + hash(x, 0, 20) % 3 ? grass(x, y) : dirt(x, y));
  tile(T.DIRT, dirt);
  tile(T.COBBLE, cobble);
  tile(T.PLANKS, planks);
  tile(T.LOG_SIDE, logSide(104, 80, 50, 21));
  tile(T.LOG_TOP, (x, y) => {
    const d = Math.sqrt((x - 7.5) * (x - 7.5) + (y - 7.5) * (y - 7.5));
    if (d > 6.8) return mul(rgb(104, 80, 50), 0.9 + 0.1 * rnd(x, y, 22));
    return mul(rgb(188, 152, 96), (d | 0) % 2 === 0 ? 1 : 0.82);
  });
  tile(T.LEAVES, leaves(62, 140, 48, 23));
  tile(T.SAND, sand);
  tile(T.GRAVEL, (x, y) => mul(rgb(136, 128, 124), 0.7 + 0.5 * rnd(x >> 1, (y + (x >> 1)) >> 1, 24) + 0.1 * rnd(x, y, 25)));
  tile(T.WATER, (x, y) => rgba(40 + ((20 * rnd(x >> 2, (y + x) >> 2, 26)) | 0), 104, 214, 168));
  tile(T.GLASS, (x, y) => (x === 0 || y === 0 || x === 15 || y === 15) ? rgb(214, 236, 242)
    : ((x + y === 9 || x + y === 10) && x > 2 && x < 8) ? rgb(255, 255, 255) : 0);
  tile(T.COAL_ORE, ore(36, 36, 40, 27));
  tile(T.IRON_ORE, ore(214, 164, 128, 29));
  tile(T.GOLD_ORE, ore(248, 210, 60, 31));
  tile(T.GEM_ORE, ore(86, 226, 214, 33));
  tile(T.BEDROCK, (x, y) => mul(rgb(70, 70, 76), 0.5 + 0.9 * rnd(x >> 1, y >> 1, 35) * rnd(x, y, 36)));
  tile(T.SNOW, snow);
  tile(T.SNOW_GRASS_SIDE, (x, y) => y < 3 + hash(x, 0, 37) % 3 ? snow(x, y) : dirt(x, y));
  tile(T.ICE, (x, y) => rgba(150 + ((30 * rnd(x, y, 38)) | 0), 200, 248, (x + y) % 9 === 0 ? 235 : 190));
  tile(T.CACTUS_SIDE, (x, y) => (x % 4 === 1 && y % 3 === 1) ? rgb(226, 232, 190)
    : mul(rgb(64, 142, 58), x % 4 === 3 ? 0.78 : 0.95 + 0.1 * rnd(x, y, 39)));
  tile(T.CACTUS_TOP, (x, y) => mul(rgb(84, 160, 70), (x === 0 || y === 0 || x === 15 || y === 15) ? 0.7 : 0.92 + 0.14 * rnd(x, y, 41)));
  tile(T.SANDSTONE, (x, y) => mul(sand(x, y), y % 5 === 4 ? 0.82 : y < 2 ? 1.05 : 0.94));
  tile(T.STONE_BRICKS, (x, y) => {
    const sx = (x + ((y >> 3) % 2) * 8) % 16;
    if (y % 8 === 7 || sx === 15) return rgb(86, 88, 92);
    return mul(stone(x, y), (y % 8 === 0 || sx === 0) ? 1.12 : 1);
  });
  tile(T.WORKBENCH_TOP, (x, y) => (x === 0 || y === 0 || x === 15 || y === 15) ? rgb(92, 66, 38)
    : (x === 5 || x === 10 || y === 5 || y === 10) ? mul(wood(x, y, 42), 0.7) : wood(x, y, 42));
  tile(T.WORKBENCH_SIDE, (x, y) => (x === 0 || x === 15 || y < 3) ? rgb(92, 66, 38)
    : (x > 3 && x < 12 && y > 5 && y < 13) ? ((x + y) % 2 === 0 ? rgb(126, 96, 58) : rgb(112, 84, 50)) : planks(x, y));
  const furnaceSide = (x, y) => (x === 0 || y === 0 || x === 15 || y === 15) ? rgb(74, 76, 80) : mul(cobble(x, y), 0.92);
  tile(T.FURNACE_SIDE, furnaceSide);
  tile(T.FURNACE_FRONT, (x, y) => (x > 3 && x < 12 && y > 7 && y < 14) ? rgb(26, 24, 26)
    : (x > 2 && x < 13 && y > 2 && y < 6) ? rgb(52, 52, 56) : furnaceSide(x, y));
  tile(T.FURNACE_FRONT_LIT, (x, y) => (x > 3 && x < 12 && y > 7 && y < 14)
    ? rgb(255, 150 + ((90 * rnd(x, y, 43)) | 0) - (13 - y) * 14, 30)
    : (x > 2 && x < 13 && y > 2 && y < 6) ? rgb(96, 60, 44) : furnaceSide(x, y));
  tile(T.TORCH, (x, y) => {
    if ((x === 7 || x === 8) && y >= 7) return x === 7 ? rgb(132, 96, 56) : rgb(96, 68, 40);
    const dx = x - 7.5, dy = y - 4.5;
    if (dx * dx + dy * dy * 0.6 < 3.2) return dx * dx + dy * dy < 1.2 ? rgb(255, 246, 170) : rgb(255, 168, 40);
    return 0;
  });
  tile(T.TALL_GRASS, (x, y) => {
    const h = 5 + hash(x, 0, 44) % 10;
    return (x % 2 === hash(x >> 1, 1, 45) % 2 && y >= 16 - h) ? mul(rgb(84, 156, 58), 0.75 + 0.4 * (16 - y) / 16) : 0;
  });
  tile(T.FLOWER_RED, flower(220, 50, 70));
  tile(T.FLOWER_YELLOW, flower(240, 150, 40));
  tile(T.PINE_LOG_SIDE, logSide(72, 52, 38, 46));
  tile(T.PINE_LEAVES, leaves(38, 98, 70, 47));
  tile(T.IRON_BLOCK, metal(214, 214, 220));
  tile(T.GOLD_BLOCK, metal(246, 204, 62));
  tile(T.GEM_BLOCK, metal(92, 220, 208));
  tile(T.LAMP, (x, y) => (x === 0 || y === 0 || x === 15 || y === 15 || x === 7 || x === 8 || y === 7 || y === 8)
    ? rgb(110, 78, 44) : grain(rgb(255, 226, 132), x, y, 48, 0.08));
  tile(T.DEAD_BUSH, (x, y) => (((x === 7 || x === 8) && y > 8) || (Math.abs(x - 7.5) === (12.5 - y) && y > 3 && y < 13)
    || (Math.abs(x - 7.5) + 3 === (11.5 - y) * 2 && y > 5)) ? rgb(120, 88, 50) : 0);
  tile(T.WHITE, () => 0xffffffff);
  for (let i = 0; i < 4; i++)
    tile(T.CRACK + i, (x, y) => (rnd(x >> 1, y >> 1, 50) < 0.16 * (i + 1) && rnd(x, y, 51) < 0.75) ? rgba(10, 10, 12, 170) : 0);
  tile(T.SUN, (x, y) => {
    const d = (x - 7.5) * (x - 7.5) + (y - 7.5) * (y - 7.5);
    return d < 30 ? rgb(255, 250, 215) : d < 52 ? rgb(255, 224, 120) : 0;
  });
  tile(T.MOON, (x, y) => {
    const d = (x - 7.5) * (x - 7.5) + (y - 7.5) * (y - 7.5);
    return d >= 42 ? 0 : rnd((x / 3) | 0, (y / 3) | 0, 52) > 0.72 ? rgb(176, 184, 204) : rgb(226, 232, 244);
  });
  tile(T.I_STICK, art(STICK_ART, 0));
  tile(T.I_COAL, disc(7.5, 8, 5.5, 5, 44, 44, 50));
  tile(T.I_IRON, ingot(220, 220, 226));
  tile(T.I_GOLD, ingot(248, 208, 64));
  tile(T.I_GEM, (x, y) => {
    const d = Math.abs(x - 7.5) + Math.abs(y - 7.5) * 0.8;
    return d > 6 ? 0 : mul(rgb(86, 226, 214), d > 4.8 ? 0.6 : (x < 8 && y < 8) ? 1.25 : (x + y) % 5 === 0 ? 1.1 : 0.92);
  });
  tile(T.I_RAW_MEAT, disc(7.5, 8, 6, 4.5, 226, 96, 104));
  tile(T.I_COOKED_MEAT, disc(7.5, 8, 6, 4.5, 150, 92, 52));
  const fruit = disc(7.5, 9, 5, 5, 214, 48, 92);
  tile(T.I_FRUIT, (x, y) => (y < 5 && y > 1 && x === 8) ? rgb(96, 68, 40)
    : ((y === 3 && (x === 9 || x === 10)) || (y === 2 && x === 10)) ? rgb(80, 160, 60) : fruit(x, y));
  for (let k = 0; k < 4; k++) for (let t = 0; t < 4; t++) tile(T.I_TOOLS + k * 4 + t, art(TOOL_ART[k], TIER_COLOR[t]));
  for (let m = 0; m < 3; m++) { tile(T.HEART + m, heart(m)); tile(T.FOOD + m, food(m)); }
  tile(T.BUBBLE, (x, y) => {
    const d = (x - 7.5) * (x - 7.5) + (y - 7.5) * (y - 7.5);
    return (d < 36 && d > 20) ? rgb(170, 214, 255) : (x === 5 && y === 5) ? rgb(255, 255, 255) : 0;
  });
  tile(T.FUR, (x, y) => { const v = 205 + ((50 * rnd(x, y, 53) * rnd(x >> 1, y >> 1, 54)) | 0); return rgb(v, v, v); });
  return px;
}

/** Canvas com o atlas, opcionalmente escurecido (para as faces dos ícones isométricos). */
export function atlasCanvas(px, shade = 1) {
  const c = document.createElement('canvas');
  c.width = c.height = SIZE;
  const ctx = c.getContext('2d'), img = ctx.createImageData(SIZE, SIZE), d = img.data;
  for (let i = 0; i < px.length; i++) {
    const p = px[i];
    d[i * 4] = ((p >> 16) & 255) * shade; d[i * 4 + 1] = ((p >> 8) & 255) * shade; d[i * 4 + 2] = (p & 255) * shade; d[i * 4 + 3] = p >>> 24;
  }
  ctx.putImageData(img, 0, 0);
  return c;
}
