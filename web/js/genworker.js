// Web Worker: gera chunks fora da thread principal para o jogo não travar.
import { WorldGen } from './worldgen.js';

let gen = null;
self.onmessage = e => {
  const m = e.data;
  if (m.seed !== undefined) { gen = new WorldGen(m.seed); return; }
  const blocks = gen.generate(m.cx, m.cz);
  self.postMessage({ cx: m.cx, cz: m.cz, blocks }, [blocks.buffer]);
};
