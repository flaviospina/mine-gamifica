package terralume;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;

/** Programa de shader com cache simples de uniforms. */
final class Shader {
    final int id;
    private final java.util.HashMap<String, Integer> locs = new java.util.HashMap<>();
    private final FloatBuffer mat = BufferUtils.createFloatBuffer(16);

    Shader(String vs, String fs) {
        int v = compile(GL_VERTEX_SHADER, vs), f = compile(GL_FRAGMENT_SHADER, fs);
        id = glCreateProgram();
        glAttachShader(id, v); glAttachShader(id, f);
        glLinkProgram(id);
        if (glGetProgrami(id, GL_LINK_STATUS) == 0) throw new RuntimeException("link: " + glGetProgramInfoLog(id));
        glDeleteShader(v); glDeleteShader(f);
    }

    private static int compile(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == 0) throw new RuntimeException("shader: " + glGetShaderInfoLog(s));
        return s;
    }

    void use() { glUseProgram(id); }
    int loc(String n) { return locs.computeIfAbsent(n, k -> glGetUniformLocation(id, k)); }
    void set(String n, float a) { glUniform1f(loc(n), a); }
    void set(String n, int a) { glUniform1i(loc(n), a); }
    void set(String n, float a, float b) { glUniform2f(loc(n), a, b); }
    void set(String n, float a, float b, float c) { glUniform3f(loc(n), a, b, c); }
    void set(String n, Matrix4f m) { m.get(mat); glUniformMatrix4fv(loc(n), false, mat); }

    static final String CHUNK_VS = """
            #version 330 core
            layout(location=0) in vec3 aPos;
            layout(location=1) in vec2 aUV;
            layout(location=2) in vec3 aLight;
            uniform mat4 uVP;
            uniform vec3 uOffset;
            out vec2 vUV; out vec3 vLight; out vec3 vPos;
            void main() {
                vec3 p = aPos + uOffset;
                gl_Position = uVP * vec4(p, 1.0);
                vUV = aUV; vLight = aLight; vPos = p;
            }""";
    static final String CHUNK_FS = """
            #version 330 core
            in vec2 vUV; in vec3 vLight; in vec3 vPos;
            uniform sampler2D uTex;
            uniform float uDay;
            uniform float uAlphaTest;
            uniform vec3 uFogColor;
            uniform vec2 uFog;
            out vec4 o;
            void main() {
                vec4 t = texture(uTex, vUV);
                if (t.a < uAlphaTest) discard;
                float sky = vLight.x * uDay, blk = vLight.y;
                float l = max(sky, blk);
                float b = 0.03 + 0.97 * pow(l, 1.7);
                vec3 tint = mix(vec3(1.0), vec3(1.0, 0.88, 0.72), clamp((blk - sky) * 1.5, 0.0, 1.0));
                vec3 c = t.rgb * tint * (vLight.z * b);
                float f = clamp((length(vPos) - uFog.x) / (uFog.y - uFog.x), 0.0, 1.0);
                o = vec4(mix(c, uFogColor, f), t.a);
            }""";
    static final String BASIC_VS = """
            #version 330 core
            layout(location=0) in vec3 aPos;
            layout(location=1) in vec2 aUV;
            layout(location=2) in vec4 aCol;
            uniform mat4 uMVP;
            out vec2 vUV; out vec4 vCol; out vec3 vPos;
            void main() {
                gl_Position = uMVP * vec4(aPos, 1.0);
                vUV = aUV; vCol = aCol; vPos = aPos;
            }""";
    static final String BASIC_FS = """
            #version 330 core
            in vec2 vUV; in vec4 vCol; in vec3 vPos;
            uniform sampler2D uTex;
            uniform int uUseTex;
            uniform vec3 uFogColor;
            uniform vec2 uFog;
            out vec4 o;
            void main() {
                vec4 c = vCol;
                if (uUseTex == 1) c *= texture(uTex, vUV);
                if (c.a < 0.01) discard;
                if (uFog.y > 0.0) c.rgb = mix(c.rgb, uFogColor, clamp((length(vPos) - uFog.x) / (uFog.y - uFog.x), 0.0, 1.0));
                o = c;
            }""";
}

/** Lote dinâmico de vértices (pos, uv, cor) para entidades, céu e interface. */
final class Batch {
    static final int STRIDE = 9;
    private float[] data = new float[STRIDE * 6 * 2048];
    private int n;
    private FloatBuffer buf = MemoryUtil.memAllocFloat(data.length);
    private final int vao, vbo;

    Batch() {
        vao = glGenVertexArrays(); vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, STRIDE * 4, 0);
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, false, STRIDE * 4, 12);
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_FLOAT, false, STRIDE * 4, 20);
        glBindVertexArray(0);
    }

    void v(float x, float y, float z, float u, float v, float r, float g, float b, float a) {
        if (n + STRIDE > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
        data[n++] = x; data[n++] = y; data[n++] = z; data[n++] = u; data[n++] = v;
        data[n++] = r; data[n++] = g; data[n++] = b; data[n++] = a;
    }

    /** Quadrilátero 2D com cantos arbitrários (ordem: 4 vértices em volta). */
    void quad2(float x0, float y0, float u0, float v0, float x1, float y1, float u1, float v1,
               float x2, float y2, float u2, float v2, float x3, float y3, float u3, float v3,
               float r, float g, float b, float a) {
        v(x0, y0, 0, u0, v0, r, g, b, a); v(x1, y1, 0, u1, v1, r, g, b, a); v(x2, y2, 0, u2, v2, r, g, b, a);
        v(x0, y0, 0, u0, v0, r, g, b, a); v(x2, y2, 0, u2, v2, r, g, b, a); v(x3, y3, 0, u3, v3, r, g, b, a);
    }

    void rect(float x, float y, float w, float h, float u0, float v0, float u1, float v1, float r, float g, float b, float a) {
        quad2(x, y, u0, v0, x + w, y, u1, v0, x + w, y + h, u1, v1, x, y + h, u0, v1, r, g, b, a);
    }

    boolean isEmpty() { return n == 0; }

    void draw(int mode) {
        if (n == 0) return;
        if (buf.capacity() < n) { MemoryUtil.memFree(buf); buf = MemoryUtil.memAllocFloat(data.length); }
        buf.clear(); buf.put(data, 0, n); buf.flip();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, buf, GL_STREAM_DRAW);
        glDrawArrays(mode, 0, n / STRIDE);
        glBindVertexArray(0);
        n = 0;
    }
}

/** Textura de fonte monoespaçada (Latin-1) rasterizada na inicialização. */
final class FontTex {
    final int tex, cellW, cellH, texW, texH;

    FontTex() {
        Font font = new Font(Font.MONOSPACED, Font.BOLD, 24);
        BufferedImage probe = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        FontMetrics fm = probe.createGraphics().getFontMetrics(font);
        cellW = fm.charWidth('M') + 2; cellH = fm.getHeight() + 2;
        texW = cellW * 16; texH = cellH * 14;
        BufferedImage img = new BufferedImage(texW, texH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(font);
        g.setColor(java.awt.Color.WHITE);
        for (int c = 32; c < 256; c++) {
            int i = c - 32;
            g.drawString(String.valueOf((char) c), (i % 16) * cellW + 1, (i / 16) * cellH + 1 + fm.getAscent());
        }
        g.dispose();
        ByteBuffer b = BufferUtils.createByteBuffer(texW * texH * 4);
        for (int y = 0; y < texH; y++)
            for (int x = 0; x < texW; x++) {
                int a = img.getRGB(x, y) >>> 24;
                b.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) a);
            }
        b.flip();
        tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, texW, texH, 0, GL_RGBA, GL_UNSIGNED_BYTE, b);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    float width(String s, float size) { return s.length() * size * cellW / cellH; }

    void draw(Batch b, String s, float x, float y, float size, float r, float g, float bl, float a) {
        float w = size * cellW / cellH;
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i);
            if (c < 32 || c > 255) c = '?';
            if (c != ' ') {
                int k = c - 32;
                float u0 = (k % 16) * cellW / (float) texW, v0 = (k / 16) * cellH / (float) texH;
                b.rect(x + i * w, y, w, size, u0, v0, u0 + cellW / (float) texW, v0 + cellH / (float) texH, r, g, bl, a);
            }
        }
    }
}
