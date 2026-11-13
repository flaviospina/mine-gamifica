package terralume;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import java.nio.FloatBuffer;
import org.lwjgl.system.MemoryUtil;

/** Coluna de 16x128x16 blocos com luz (céu nos 4 bits altos, blocos nos baixos) e malhas na GPU. */
final class Chunk {
    static final int W = 16, H = 128, VOL = W * W * H;

    final int cx, cz;
    final byte[] blocks = new byte[VOL];
    final byte[] light = new byte[VOL];
    boolean lit, dirty = true, modified;
    final int[] vao = new int[2], vbo = new int[2], count = new int[2];

    Chunk(int cx, int cz) { this.cx = cx; this.cz = cz; }

    static int idx(int x, int y, int z) { return (y << 8) | (z << 4) | x; }
    int get(int x, int y, int z) { return blocks[(y << 8) | (z << 4) | x] & 0xff; }

    void upload(int pass, float[] data, int n) {
        count[pass] = n / 8;
        if (n == 0) return;
        if (vao[pass] == 0) {
            vao[pass] = glGenVertexArrays(); vbo[pass] = glGenBuffers();
            glBindVertexArray(vao[pass]);
            glBindBuffer(GL_ARRAY_BUFFER, vbo[pass]);
            glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 32, 0);
            glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, false, 32, 12);
            glEnableVertexAttribArray(2); glVertexAttribPointer(2, 3, GL_FLOAT, false, 32, 20);
        }
        FloatBuffer fb = MemoryUtil.memAllocFloat(n);
        fb.put(data, 0, n).flip();
        glBindBuffer(GL_ARRAY_BUFFER, vbo[pass]);
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
        MemoryUtil.memFree(fb);
    }

    void draw(int pass) {
        if (count[pass] == 0) return;
        glBindVertexArray(vao[pass]);
        glDrawArrays(GL_TRIANGLES, 0, count[pass]);
    }

    void free() {
        for (int i = 0; i < 2; i++) {
            if (vao[i] != 0) { glDeleteVertexArrays(vao[i]); glDeleteBuffers(vbo[i]); vao[i] = 0; }
            count[i] = 0;
        }
    }
}
