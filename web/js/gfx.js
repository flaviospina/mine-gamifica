// Utilidades de WebGL2: shaders, lote dinâmico de vértices, matrizes 4x4 e recorte por frustum.
export function program(gl, vs, fs) {
  const mk = (type, src) => {
    const s = gl.createShader(type);
    gl.shaderSource(s, src); gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error('shader: ' + gl.getShaderInfoLog(s));
    return s;
  };
  const p = gl.createProgram();
  gl.attachShader(p, mk(gl.VERTEX_SHADER, vs)); gl.attachShader(p, mk(gl.FRAGMENT_SHADER, fs));
  gl.bindAttribLocation(p, 0, 'aPos'); gl.bindAttribLocation(p, 1, 'aUV'); gl.bindAttribLocation(p, 2, 'aExtra');
  gl.linkProgram(p);
  if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error('link: ' + gl.getProgramInfoLog(p));
  const locs = {};
  return { id: p, loc: n => (n in locs ? locs[n] : (locs[n] = gl.getUniformLocation(p, n))) };
}

export const CHUNK_VS = `#version 300 es
in vec3 aPos; in vec2 aUV; in vec3 aExtra;
uniform mat4 uVP; uniform vec3 uOffset;
out vec2 vUV; out vec3 vLight; out vec3 vPos;
void main() { vec3 p = aPos + uOffset; gl_Position = uVP * vec4(p, 1.0); vUV = aUV; vLight = aExtra; vPos = p; }`;
export const CHUNK_FS = `#version 300 es
precision highp float;
in vec2 vUV; in vec3 vLight; in vec3 vPos;
uniform sampler2D uTex; uniform float uDay; uniform float uAlphaTest; uniform vec3 uFogColor; uniform vec2 uFog;
out vec4 o;
void main() {
  vec4 t = texture(uTex, vUV);
  if (t.a < uAlphaTest) discard;
  float sky = vLight.x * uDay, blk = vLight.y;
  float b = 0.03 + 0.97 * pow(max(sky, blk), 1.7);
  vec3 tint = mix(vec3(1.0), vec3(1.0, 0.88, 0.72), clamp((blk - sky) * 1.5, 0.0, 1.0));
  vec3 c = t.rgb * tint * (vLight.z * b);
  float f = clamp((length(vPos) - uFog.x) / (uFog.y - uFog.x), 0.0, 1.0);
  o = vec4(mix(c, uFogColor, f), t.a);
}`;
export const BASIC_VS = `#version 300 es
in vec3 aPos; in vec2 aUV; in vec4 aExtra;
uniform mat4 uMVP;
out vec2 vUV; out vec4 vCol; out vec3 vPos;
void main() { gl_Position = uMVP * vec4(aPos, 1.0); vUV = aUV; vCol = aExtra; vPos = aPos; }`;
export const BASIC_FS = `#version 300 es
precision highp float;
in vec2 vUV; in vec4 vCol; in vec3 vPos;
uniform sampler2D uTex; uniform vec3 uFogColor; uniform vec2 uFog;
out vec4 o;
void main() {
  vec4 c = vCol * texture(uTex, vUV);
  if (c.a < 0.01) discard;
  if (uFog.y > 0.0) c.rgb = mix(c.rgb, uFogColor, clamp((length(vPos) - uFog.x) / (uFog.y - uFog.x), 0.0, 1.0));
  o = c;
}`;

/** Lote dinâmico de vértices (pos, uv, cor) para entidades e céu. */
export class Batch {
  constructor(gl) {
    this.gl = gl; this.data = new Float32Array(9 * 6 * 2048); this.n = 0;
    this.vao = gl.createVertexArray(); this.vbo = gl.createBuffer();
    gl.bindVertexArray(this.vao);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 36, 0);
    gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 2, gl.FLOAT, false, 36, 12);
    gl.enableVertexAttribArray(2); gl.vertexAttribPointer(2, 4, gl.FLOAT, false, 36, 20);
    gl.bindVertexArray(null);
  }
  v(x, y, z, u, v, r, g, b, a) {
    if (this.n + 9 > this.data.length) { const d = new Float32Array(this.data.length * 2); d.set(this.data); this.data = d; }
    const d = this.data, n = this.n;
    d[n] = x; d[n + 1] = y; d[n + 2] = z; d[n + 3] = u; d[n + 4] = v; d[n + 5] = r; d[n + 6] = g; d[n + 7] = b; d[n + 8] = a;
    this.n = n + 9;
  }
  draw(mode) {
    if (this.n === 0) return;
    const gl = this.gl;
    gl.bindVertexArray(this.vao);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo);
    gl.bufferData(gl.ARRAY_BUFFER, this.data.subarray(0, this.n), gl.STREAM_DRAW);
    gl.drawArrays(mode, 0, this.n / 9);
    gl.bindVertexArray(null);
    this.n = 0;
  }
}

// ---------------------------------------------------------------- matrizes (coluna-maior)
export function perspective(fovy, aspect, n, f) {
  const t = 1 / Math.tan(fovy / 2), m = new Float32Array(16);
  m[0] = t / aspect; m[5] = t; m[10] = (f + n) / (n - f); m[11] = -1; m[14] = 2 * f * n / (n - f);
  return m;
}
export function translation(x, y, z) { const m = new Float32Array(16); m[0] = m[5] = m[10] = m[15] = 1; m[12] = x; m[13] = y; m[14] = z; return m; }
export function rotX(a) { const m = translation(0, 0, 0), c = Math.cos(a), s = Math.sin(a); m[5] = c; m[6] = s; m[9] = -s; m[10] = c; return m; }
export function rotY(a) { const m = translation(0, 0, 0), c = Math.cos(a), s = Math.sin(a); m[0] = c; m[2] = -s; m[8] = s; m[10] = c; return m; }
export function mul(a, b) {
  const m = new Float32Array(16);
  for (let c = 0; c < 4; c++) for (let r = 0; r < 4; r++)
    m[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
  return m;
}

/** Planos do frustum a partir da matriz de visão-projeção. */
export class Frustum {
  constructor() { this.p = new Float32Array(24); }
  set(m) {
    const p = this.p;
    for (let i = 0; i < 6; i++) {
      const r = i >> 1, s = (i & 1) ? -1 : 1;
      p[i * 4] = m[3] + s * m[r]; p[i * 4 + 1] = m[7] + s * m[4 + r]; p[i * 4 + 2] = m[11] + s * m[8 + r]; p[i * 4 + 3] = m[15] + s * m[12 + r];
    }
  }
  aabb(x0, y0, z0, x1, y1, z1) {
    const p = this.p;
    for (let i = 0; i < 24; i += 4)
      if (p[i] * (p[i] > 0 ? x1 : x0) + p[i + 1] * (p[i + 1] > 0 ? y1 : y0) + p[i + 2] * (p[i + 2] > 0 ? z1 : z0) + p[i + 3] < 0) return false;
    return true;
  }
}
