package terralume;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

/** Laço principal: entrada, simulação, streaming de chunks e renderização do mundo. */
final class Game {
    static final int SCR_NONE = 0, SCR_INV = 1, SCR_BENCH = 2, SCR_FURNACE = 3, SCR_PAUSE = 4, SCR_DEAD = 5;

    // opções de linha de comando
    String worldName = "mundo", screenshot, openScreen;
    Long seedArg;
    boolean creativeArg;
    int renderDist = 8, frames = 300, up = 0;
    float timeArg = -1, lookYaw = Float.NaN, lookPitch;

    long win;
    int fbW = 1280, fbH = 720, winW = 1280, winH = 720;
    Shader chunkSh, basicSh;
    int atlasTex, cloudTex;
    FontTex font;
    Batch batch;
    World world;
    final Player player = new Player();
    Ui ui;
    Path saveDir;
    double spawnX, spawnY, spawnZ;

    int screen = SCR_NONE;
    final boolean[] keys = new boolean[GLFW_KEY_LAST + 1];
    boolean lmb, rmb, lmbPressed, firstMouse = true, showDebug, hideHud, sprinting;
    double mouseX, mouseY, lastMX, lastMY, lastSpace;
    int[] target;
    Mob targetMob;
    int mineX, mineY = -1, mineZ;
    float mineProgress, breakCooldown, useCooldown, tickAcc, fps, daySmooth = 1;
    int frameCount, spawnTimer;
    final float[] skyColor = new float[3];
    private int[][] offsets;
    private final Matrix4f proj = new Matrix4f(), view = new Matrix4f(), vp = new Matrix4f(), tmp = new Matrix4f(), tmp2 = new Matrix4f();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Vector3f v3 = new Vector3f();
    private final Random rnd = new Random();
    boolean pendingShot;
    String toast = "";
    float toastTime;

    void run() throws Exception {
        initWindow();
        initResources();
        initWorld();
        double last = glfwGetTime(), fpsT = last;
        int fpsN = 0;
        while (!glfwWindowShouldClose(win)) {
            double now = glfwGetTime();
            float dt = (float) Math.min(0.1, now - last);
            last = now;
            fpsN++;
            if (now - fpsT >= 0.5) { fps = (float) (fpsN / (now - fpsT)); fpsN = 0; fpsT = now; }
            glfwPollEvents();
            if (openScreen != null && frameCount == frames - 20) {
                player.inv[0] = new ItemStack(Item.PICK + 2, 1, 40); player.inv[1] = new ItemStack(Block.LOG, 12);
                player.inv[2] = new ItemStack(Block.TORCH, 30); player.inv[3] = new ItemStack(Item.FRUIT, 5);
                if (openScreen.equals("bench")) setScreen(SCR_BENCH); else if (openScreen.equals("inv")) setScreen(SCR_INV);
            }
            if ("demo".equals(openScreen) && frameCount == frames - 90) demoScene();
            update(screenshot != null ? 1 / 60f : dt);
            render();
            frameCount++;
            if (pendingShot) { pendingShot = false; saveScreenshot("captura-" + System.currentTimeMillis() + ".png"); say("Captura salva"); }
            if (screenshot != null && frameCount >= frames) { saveScreenshot(screenshot); break; }
            glfwSwapBuffers(win);
        }
        if (screenshot == null) saveGame();
        world.shutdown();
        glfwDestroyWindow(win);
        glfwTerminate();
    }

    /** Cena de teste para capturas automáticas: construções, luzes e criaturas diante do jogador. */
    private void demoScene() {
        double sy = Math.sin(player.yaw), cy = Math.cos(player.yaw);
        int bx = (int) Math.floor(player.x + sy * 6), bz = (int) Math.floor(player.z - cy * 6);
        int by = (int) Math.floor(player.y);
        int[] wall = {Block.STONE_BRICKS, Block.PLANKS, Block.GLASS, Block.LAMP, Block.WORKBENCH, Block.FURNACE_LIT, Block.GOLD_BLOCK};
        for (int i = 0; i < wall.length; i++)
            for (int h = 0; h < 2; h++) world.setBlock(bx - 3 + i, by + h, bz, h == 1 && i % 2 == 0 ? Block.COBBLE : wall[i]);
        world.setBlock(bx - 2, by + 2, bz, Block.TORCH);
        world.setBlock(bx + 2, by + 2, bz, Block.TORCH);
        for (int i = 0; i < 2; i++) {
            Mob m = new Mob(i);
            m.x = player.x + sy * 3.5 + cy * (i == 0 ? -1.6 : 1.6); m.z = player.z - cy * 3.5 + sy * (i == 0 ? -1.6 : 1.6);
            m.y = player.y + 1; m.yaw = player.yaw + 2.4f + i; m.health = 1000;
            world.entities.add(m);
        }
        world.dropItem(player.x + sy * 2.5, player.y + 1, player.z - cy * 2.5, new ItemStack(Item.GEM, 1));
        world.dropItem(player.x + sy * 2.2 + 0.5, player.y + 1, player.z - cy * 2.2, new ItemStack(Block.LOG, 1));
        player.creative = true;
    }

    // ------------------------------------------------------------------ inicialização

    private void initWindow() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("Não foi possível iniciar o GLFW");
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        win = glfwCreateWindow(winW, winH, "Terralume", NULL, NULL);
        if (win == NULL) throw new IllegalStateException("Não foi possível criar a janela (OpenGL 3.3 é necessário)");
        glfwMakeContextCurrent(win);
        GL.createCapabilities();
        glfwSwapInterval(1);
        int[] w = new int[1], h = new int[1];
        glfwGetFramebufferSize(win, w, h); fbW = w[0]; fbH = h[0];
        glfwGetWindowSize(win, w, h); winW = w[0]; winH = h[0];
        glfwSetFramebufferSizeCallback(win, (wd, nw, nh) -> { if (nw > 0 && nh > 0) { fbW = nw; fbH = nh; } });
        glfwSetWindowSizeCallback(win, (wd, nw, nh) -> { if (nw > 0 && nh > 0) { winW = nw; winH = nh; } });
        glfwSetKeyCallback(win, (wd, key, sc, action, mods) -> {
            if (key < 0 || key > GLFW_KEY_LAST) return;
            keys[key] = action != GLFW_RELEASE;
            if (action == GLFW_PRESS) onKey(key);
        });
        glfwSetMouseButtonCallback(win, (wd, button, action, mods) -> {
            boolean down = action == GLFW_PRESS;
            if (screen != SCR_NONE) { if (down) ui.click(button); return; }
            if (button == GLFW_MOUSE_BUTTON_LEFT) { lmb = down; if (down) lmbPressed = true; }
            if (button == GLFW_MOUSE_BUTTON_RIGHT) { rmb = down; if (down) useCooldown = 0; }
            if (button == GLFW_MOUSE_BUTTON_MIDDLE && down) pickBlock();
        });
        glfwSetCursorPosCallback(win, (wd, mx, my) -> {
            mouseX = mx * fbW / (double) winW; mouseY = my * fbH / (double) winH;
            if (screen == SCR_NONE) {
                if (!firstMouse) {
                    player.yaw += (float) ((mx - lastMX) * 0.0026);
                    player.pitch = (float) Math.max(-1.55, Math.min(1.55, player.pitch - (my - lastMY) * 0.0026));
                }
                firstMouse = false;
            }
            lastMX = mx; lastMY = my;
        });
        glfwSetScrollCallback(win, (wd, sx, sy) -> {
            if (screen == SCR_NONE && sy != 0) player.selected = Math.floorMod(player.selected - (int) Math.signum(sy), 9);
        });
        glfwSetInputMode(win, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
    }

    private void initResources() {
        chunkSh = new Shader(Shader.CHUNK_VS, Shader.CHUNK_FS);
        basicSh = new Shader(Shader.BASIC_VS, Shader.BASIC_FS);
        atlasTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, atlasTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, Atlas.SIZE, Atlas.SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, new Atlas().toBuffer());
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        final int CS = 64;
        Noise n = new Noise(77);
        ByteBuffer cb = BufferUtils.createByteBuffer(CS * CS * 4);
        for (int y = 0; y < CS; y++)
            for (int x = 0; x < CS; x++) {
                // ruído periódico (toro) para a textura repetir sem costura
                double a = x / (double) CS * Math.PI * 2, b = y / (double) CS * Math.PI * 2;
                double v = n.n3(Math.cos(a) * 1.6 + 5, Math.sin(a) * 1.6 + Math.cos(b) * 1.6, Math.sin(b) * 1.6 + 9)
                        + 0.5 * n.n3(Math.cos(a) * 3.2, Math.sin(a) * 3.2 + Math.cos(b) * 3.2 + 3, Math.sin(b) * 3.2);
                cb.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) (v > 0.16 ? 255 : 0));
            }
        cb.flip();
        cloudTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, cloudTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, CS, CS, 0, GL_RGBA, GL_UNSIGNED_BYTE, cb);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);

        font = new FontTex();
        batch = new Batch();
        ui = new Ui(this);
    }

    private void initWorld() throws IOException {
        saveDir = Paths.get("saves", worldName);
        long seed = seedArg != null ? seedArg : new Random().nextLong();
        Path level = saveDir.resolve("level.dat");
        boolean existing = Files.exists(level) && screenshot == null;
        DataInputStream in = null;
        if (existing) {
            in = new DataInputStream(new BufferedInputStream(Files.newInputStream(level)));
            if (in.readInt() != 0x7E88A001) { in.close(); in = null; existing = false; } else seed = in.readLong();
        }
        world = new World(seed, screenshot == null ? saveDir : saveDir.resolve("_tmp_inexistente"));
        // ponto de nascimento: primeira coluna de terra firme perto da origem
        int sx = 0, sz = 0;
        for (int i = 0; i < 4000; i++) {
            int h = world.gen.height(sx, sz);
            if (h > WorldGen.SEA + 1 && h < 84 && !world.gen.carved(sx, h, sz, h)) break;
            sx += 7; if (i % 40 == 39) { sx = 0; sz += 11; }
        }
        spawnX = sx + 0.5; spawnZ = sz + 0.5; spawnY = world.gen.height(sx, sz) + 1.05;
        if (existing) {
            world.time = in.readLong();
            player.x = in.readDouble(); player.y = in.readDouble(); player.z = in.readDouble();
            player.yaw = in.readFloat(); player.pitch = in.readFloat();
            player.creative = in.readBoolean(); player.flying = in.readBoolean();
            player.health = in.readInt(); player.hunger = in.readInt(); player.selected = in.readInt();
            for (int i = 0; i < 36; i++) player.inv[i] = readStack(in);
            int nf = in.readInt();
            for (int i = 0; i < nf; i++) {
                Furnace f = new Furnace();
                long k = in.readLong();
                f.burn = in.readInt(); f.burnMax = in.readInt(); f.cook = in.readInt();
                f.input = readStack(in); f.fuel = readStack(in); f.output = readStack(in);
                world.furnaces.put(k, f);
            }
            in.close();
        } else {
            player.x = spawnX; player.y = spawnY; player.z = spawnZ;
            player.creative = creativeArg;
        }
        if (creativeArg) player.creative = true;
        if (timeArg >= 0) world.time = (long) (timeArg * 24000);
        if (!Float.isNaN(lookYaw)) { player.yaw = (float) Math.toRadians(lookYaw); player.pitch = (float) Math.toRadians(lookPitch); }
        if (up > 0) { player.y += up; player.flying = true; player.creative = true; }
        player.fallStart = player.y;
        System.out.println("Terralume | mundo '" + worldName + "' | semente " + seed);
    }

    private static ItemStack readStack(DataInputStream in) throws IOException {
        int id = in.readShort();
        if (id < 0) return null;
        int count = in.readUnsignedByte(), dmg = in.readShort();
        return Item.of(id) == null ? null : new ItemStack(id, count, dmg);
    }

    private static void writeStack(DataOutputStream out, ItemStack s) throws IOException {
        if (s == null) { out.writeShort(-1); return; }
        out.writeShort(s.id); out.writeByte(s.count); out.writeShort(s.dmg);
    }

    void saveGame() {
        try {
            ui.closeContainers();
            world.saveAll();
            Files.createDirectories(saveDir);
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(saveDir.resolve("level.dat"))))) {
                out.writeInt(0x7E88A001); out.writeLong(world.gen.seed); out.writeLong(world.time);
                out.writeDouble(player.x); out.writeDouble(player.y); out.writeDouble(player.z);
                out.writeFloat(player.yaw); out.writeFloat(player.pitch);
                out.writeBoolean(player.creative); out.writeBoolean(player.flying);
                out.writeInt(player.health); out.writeInt(player.hunger); out.writeInt(player.selected);
                for (ItemStack s : player.inv) writeStack(out, s);
                out.writeInt(world.furnaces.size());
                for (Map.Entry<Long, Furnace> e : world.furnaces.entrySet()) {
                    Furnace f = e.getValue();
                    out.writeLong(e.getKey()); out.writeInt(f.burn); out.writeInt(f.burnMax); out.writeInt(f.cook);
                    writeStack(out, f.input); writeStack(out, f.fuel); writeStack(out, f.output);
                }
            }
        } catch (IOException e) {
            System.err.println("Falha ao salvar: " + e);
        }
    }

    // ------------------------------------------------------------------ entrada

    void setScreen(int s) {
        if (screen != SCR_NONE && s == SCR_NONE) ui.closeContainers();
        screen = s;
        glfwSetInputMode(win, GLFW_CURSOR, s == SCR_NONE ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        firstMouse = true; lmb = rmb = lmbPressed = false;
        if (s != SCR_NONE) ui.open(s);
    }

    void say(String s) { toast = s; toastTime = 2.5f; }

    private void onKey(int key) {
        if (key == GLFW_KEY_ESCAPE) {
            if (screen == SCR_NONE) setScreen(SCR_PAUSE); else if (screen != SCR_DEAD) setScreen(SCR_NONE);
            return;
        }
        if (key == GLFW_KEY_F2) pendingShot = true;
        if (key == GLFW_KEY_F3) showDebug = !showDebug;
        if (key == GLFW_KEY_F1) hideHud = !hideHud;
        if (screen == SCR_DEAD || screen == SCR_PAUSE) return;
        if (key == GLFW_KEY_E) { setScreen(screen == SCR_NONE ? SCR_INV : SCR_NONE); return; }
        if (screen != SCR_NONE) return;
        if (key >= GLFW_KEY_1 && key <= GLFW_KEY_9) player.selected = key - GLFW_KEY_1;
        if (key == GLFW_KEY_F4) {
            player.creative = !player.creative;
            if (!player.creative) player.flying = false;
            say(player.creative ? "Modo criativo" : "Modo sobrevivência");
        }
        if (key == GLFW_KEY_F && player.creative) player.flying = !player.flying;
        if (key == GLFW_KEY_SPACE) {
            double now = glfwGetTime();
            if (player.creative && now - lastSpace < 0.3) { player.flying = !player.flying; player.vy = 0; }
            lastSpace = now;
        }
        if (key == GLFW_KEY_Q) {
            ItemStack s = player.held();
            if (s != null) {
                ItemStack one = new ItemStack(s.id, 1, s.dmg);
                if (--s.count <= 0) player.inv[player.selected] = null;
                tossItem(one);
            }
        }
    }

    void tossItem(ItemStack s) {
        ItemEntity e = new ItemEntity(s);
        double sy = Math.sin(player.yaw), cy = Math.cos(player.yaw);
        e.x = player.x + sy * 0.4; e.y = player.y + 1.3; e.z = player.z - cy * 0.4;
        e.vx = sy * 5; e.vy = 2; e.vz = -cy * 5;
        e.age = -1.2f;
        world.entities.add(e);
    }

    private void pickBlock() {
        if (target == null) return;
        int id = world.block(target[0], target[1], target[2]);
        if (id == Block.FURNACE_LIT) id = Block.FURNACE;
        for (int i = 0; i < 9; i++) if (player.inv[i] != null && player.inv[i].id == id) { player.selected = i; return; }
        if (player.creative && Item.of(id) != null) player.inv[player.selected] = new ItemStack(id, 1);
    }

    // ------------------------------------------------------------------ simulação

    private void update(float dt) {
        streamChunks();
        if (toastTime > 0) toastTime -= dt;
        if (screen == SCR_PAUSE) return;
        int bx = (int) Math.floor(player.x), bz = (int) Math.floor(player.z);
        Chunk here = world.chunk(bx >> 4, bz >> 4);
        boolean ready = here != null && here.lit;
        if (ready && !player.dead) {
            movePlayer(dt);
            if (screen == SCR_NONE) interact(dt); else { mineProgress = 0; target = null; targetMob = null; }
        }
        if (ready) updateEntities(dt);
        tickAcc += dt;
        int guard = 0;
        while (tickAcc >= 0.05f && guard++ < 5) { tickAcc -= 0.05f; tick(ready); }
        daySmooth += (world.daylight() - daySmooth) * Math.min(1, dt * 2);
    }

    private void buildOffsets() {
        int r = renderDist + 1;
        List<int[]> l = new ArrayList<>();
        for (int z = -r; z <= r; z++) for (int x = -r; x <= r; x++) l.add(new int[]{x, z});
        l.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        offsets = l.toArray(new int[0][]);
    }

    private void streamChunks() {
        if (offsets == null) buildOffsets();
        int pcx = (int) Math.floor(player.x) >> 4, pcz = (int) Math.floor(player.z) >> 4;
        long t0 = System.nanoTime();
        while (System.nanoTime() - t0 < 5_000_000L && world.integrate(1) > 0) { }
        for (int[] o : offsets) {
            if (world.pendingCount() >= 12) break;
            world.request(pcx + o[0], pcz + o[1]);
        }
        t0 = System.nanoTime();
        for (int[] o : offsets) {
            if (Math.abs(o[0]) > renderDist || Math.abs(o[1]) > renderDist) continue;
            Chunk c = world.chunk(pcx + o[0], pcz + o[1]);
            if (c == null || !c.lit || !c.dirty || !world.neighborsLit(c)) continue;
            ChunkMesher.build(world, c);
            c.upload(0, ChunkMesher.opaque, ChunkMesher.nOpaque);
            c.upload(1, ChunkMesher.trans, ChunkMesher.nTrans);
            c.dirty = false;
            if (System.nanoTime() - t0 > 7_000_000L) break;
        }
        if (frameCount % 120 == 0) world.unloadFar(pcx, pcz, renderDist + 3);
    }

    private void movePlayer(float dt) {
        Player p = player;
        float f = 0, s = 0;
        boolean ctl = screen == SCR_NONE;
        if (ctl) {
            if (keys[GLFW_KEY_W]) f += 1; if (keys[GLFW_KEY_S]) f -= 1;
            if (keys[GLFW_KEY_D]) s += 1; if (keys[GLFW_KEY_A]) s -= 1;
        }
        boolean sneak = ctl && keys[GLFW_KEY_LEFT_SHIFT], jump = ctl && keys[GLFW_KEY_SPACE];
        sprinting = ctl && keys[GLFW_KEY_LEFT_CONTROL] && f > 0 && (p.creative || p.hunger > 6);
        double speed = p.flying ? (sprinting ? 24 : 11) : sneak ? 1.6 : sprinting ? 5.8 : 4.3;
        if (p.inWater && !p.flying) speed *= 0.7;
        double len = Math.sqrt(f * f + s * s);
        double tvx = 0, tvz = 0;
        if (len > 0) {
            double sy = Math.sin(p.yaw), cy = Math.cos(p.yaw);
            tvx = (sy * f + cy * s) / len * speed; tvz = (-cy * f + sy * s) / len * speed;
        }
        double k = Math.min(1, (p.onGround || p.flying ? 14 : p.inWater ? 6 : 3.5) * dt);
        p.vx += (tvx - p.vx) * k; p.vz += (tvz - p.vz) * k;
        if (p.flying) {
            double tvy = (jump ? 9 : 0) - (sneak ? 9 : 0);
            p.vy += (tvy - p.vy) * Math.min(1, 12 * dt);
        } else if (jump) {
            if (p.inWater) p.vy = Math.min(3.6, p.vy + 30 * dt);
            else if (p.onGround) { p.vy = 8.6; p.exhaustion += sprinting ? 0.12f : 0.04f; }
        }
        double fell = p.physics(world, dt);
        if (fell > 3.5 && !p.creative) p.hurt((int) Math.round(fell - 3), p.x, p.z);
        if (p.y < -30) { p.health = 0; p.dead = true; }
        if (p.dead && screen != SCR_DEAD) setScreen(SCR_DEAD);
    }

    float breakTime(Block b, ItemStack held) {
        if (b.hardness <= 0) return 0;
        Item it = held == null ? null : held.item();
        boolean match = it != null && it.tool == b.tool && b.tool != Block.TOOL_NONE;
        float speed = match ? Item.TIER_SPEED[it.tier] : 1;
        return canHarvest(b, held) ? b.hardness * 1.5f / speed : b.hardness * 5f;
    }

    boolean canHarvest(Block b, ItemStack held) {
        if (b.minTier == 0) return true;
        Item it = held == null ? null : held.item();
        return it != null && it.tool == b.tool && it.tier >= b.minTier;
    }

    private void interact(float dt) {
        Player p = player;
        breakCooldown -= dt; useCooldown -= dt;
        double ex = p.x, ey = p.y + Player.EYE, ez = p.z;
        double cp = Math.cos(p.pitch);
        double dx = Math.sin(p.yaw) * cp, dy = Math.sin(p.pitch), dz = -Math.cos(p.yaw) * cp;
        target = world.raycast(ex, ey, ez, dx, dy, dz, p.creative ? 6 : 4.8);
        double best = Math.min(3.6, target != null ? target[6] / 1000.0 : 1e9);
        targetMob = null;
        for (Entity e : world.entities) {
            if (!(e instanceof Mob m)) continue;
            double t = m.rayHit(ex, ey, ez, dx, dy, dz);
            if (t >= 0 && t < best) { best = t; targetMob = m; }
        }
        if (lmbPressed && targetMob != null) {
            ItemStack h = p.held();
            targetMob.hurt(h == null ? 1 : h.item().damage, p.x, p.z);
            if (h != null && h.item().tool != Block.TOOL_NONE) p.damageTool();
            p.exhaustion += 0.1f;
            breakCooldown = 0.25f;
        }
        lmbPressed = false;

        if (lmb && target != null && targetMob == null && breakCooldown <= 0) {
            if (target[0] != mineX || target[1] != mineY || target[2] != mineZ) {
                mineX = target[0]; mineY = target[1]; mineZ = target[2]; mineProgress = 0;
            }
            Block b = Block.REG[world.block(mineX, mineY, mineZ)];
            if (b.hardness >= 0) {
                float time = p.creative ? 0 : breakTime(b, p.held());
                mineProgress = time <= 0 ? 1 : mineProgress + dt / time;
                if (mineProgress >= 1) {
                    world.breakBlock(mineX, mineY, mineZ, !p.creative && canHarvest(b, p.held()));
                    if (b.hardness > 0) p.damageTool();
                    p.exhaustion += 0.02f;
                    mineProgress = 0; mineY = -1;
                    if (time <= 0.05f) breakCooldown = 0.2f;
                }
            }
        } else { mineProgress = 0; if (!lmb) mineY = -1; }

        if (rmb && useCooldown <= 0) {
            useCooldown = 0.24f;
            ItemStack h = p.held();
            if (target != null && !keys[GLFW_KEY_LEFT_SHIFT]) {
                int tid = world.block(target[0], target[1], target[2]);
                if (tid == Block.WORKBENCH) { setScreen(SCR_BENCH); return; }
                if (tid == Block.FURNACE || tid == Block.FURNACE_LIT) {
                    ui.furnace = world.furnaceAt(target[0], target[1], target[2]);
                    setScreen(SCR_FURNACE);
                    return;
                }
            }
            if (h != null && h.item().food > 0 && (p.hunger < 20 || p.creative)) {
                p.hunger = Math.min(20, p.hunger + h.item().food);
                if (!p.creative && --h.count <= 0) p.inv[p.selected] = null;
                useCooldown = 0.6f;
            } else if (h != null && h.item().isBlock() && target != null) {
                int px = target[0], py = target[1], pz = target[2];
                if (!Block.REG[world.block(px, py, pz)].replaceable) { px += target[3]; py += target[4]; pz += target[5]; }
                Block nb = Block.REG[h.id];
                boolean ok = py >= 1 && py < Chunk.H && Block.REG[world.block(px, py, pz)].replaceable;
                if (ok && nb.needsGround && !world.solid(px, py - 1, pz)) ok = false;
                if (ok && nb.solid) {
                    if (boxHits(p, px, py, pz)) ok = false;
                    for (Entity e : world.entities) if (e instanceof Mob && boxHits(e, px, py, pz)) ok = false;
                }
                if (ok) {
                    world.setBlock(px, py, pz, h.id);
                    if (!p.creative && --h.count <= 0) p.inv[p.selected] = null;
                }
            }
        }
    }

    private static boolean boxHits(Entity e, int bx, int by, int bz) {
        return e.x + e.w / 2 > bx && e.x - e.w / 2 < bx + 1 && e.y + e.h > by && e.y < by + 1 && e.z + e.w / 2 > bz && e.z - e.w / 2 < bz + 1;
    }

    private void updateEntities(float dt) {
        Player p = player;
        for (int i = world.entities.size() - 1; i >= 0; i--) {
            Entity e = world.entities.get(i);
            double dx = e.x - p.x, dy = e.y - p.y, dz = e.z - p.z, d2 = dx * dx + dy * dy + dz * dz;
            if (!world.loaded((int) Math.floor(e.x), (int) Math.floor(e.z)) || d2 > 110 * 110 || e.y < -20) { world.entities.remove(i); continue; }
            if (e instanceof ItemEntity it) {
                it.age += dt;
                it.vx *= Math.pow(0.05, dt); it.vz *= Math.pow(0.05, dt);
                it.physics(world, dt);
                if (it.age > 0.5f && !p.dead && d2 < 2.2 * 2.2 && dy > -2.2) {
                    if (p.give(it.stack) == null) { world.entities.remove(i); continue; }
                }
                if (it.age > 300) world.entities.remove(i);
            } else if (e instanceof Mob m) {
                m.update(world, p, dt, rnd);
                if (m.dead) {
                    if (m.type == Mob.GRAZER) world.dropItem(m.x, m.y + 0.5, m.z, new ItemStack(Item.RAW_MEAT, 1 + rnd.nextInt(2)));
                    else {
                        if (rnd.nextBoolean()) world.dropItem(m.x, m.y + 0.5, m.z, new ItemStack(Item.COAL, 1 + rnd.nextInt(2)));
                        if (rnd.nextInt(6) == 0) world.dropItem(m.x, m.y + 0.5, m.z, new ItemStack(Item.IRON_INGOT, 1));
                    }
                    world.entities.remove(i);
                }
            }
        }
    }

    /** Lógica em passo fixo de 20 ticks por segundo. */
    private void tick(boolean ready) {
        world.time++;
        world.tickFurnaces();
        world.tickWater();
        if (!ready) return;
        Player p = player;
        if (!p.creative && !p.dead) {
            p.exhaustion += sprinting ? 0.012f : 0.002f;
            if (p.exhaustion >= 4) { p.exhaustion -= 4; p.hunger = Math.max(0, p.hunger - 1); }
            if (++p.regenTimer >= 80) {
                p.regenTimer = 0;
                if (p.hunger >= 18 && p.health < 20) { p.health++; p.exhaustion += 1.5f; }
                else if (p.hunger == 0 && p.health > 1) { p.health--; p.hurtTime = 0.3f; }
            }
            boolean headWet = Block.isLiquid(world.block((int) Math.floor(p.x), (int) Math.floor(p.y + Player.EYE), (int) Math.floor(p.z)));
            if (headWet) {
                if (++p.airTimer >= 20) {
                    p.airTimer = 0;
                    if (p.air > 0) p.air--; else { p.hurtTime = 0; p.hurt(2, p.x, p.z); }
                }
            } else { p.air = 10; p.airTimer = 0; }
            if (p.dead && screen != SCR_DEAD) setScreen(SCR_DEAD);
        }
        if (++spawnTimer >= 20) { spawnTimer = 0; spawnMobs(); }
    }

    private void spawnMobs() {
        int grazers = 0, stalkers = 0;
        for (Entity e : world.entities) {
            if (!(e instanceof Mob m)) continue;
            if (m.type == Mob.GRAZER) grazers++; else {
                stalkers++;
                // o Brasal se desfaz sob o sol
                int x = (int) Math.floor(m.x), y = (int) Math.floor(m.y + 0.5), z = (int) Math.floor(m.z);
                if (world.sky(x, y, z) * world.daylight() > 12) { m.hurtTime = 0; m.hurt(4, m.x, m.z); }
            }
        }
        for (int attempt = 0; attempt < 6; attempt++) {
            double ang = rnd.nextDouble() * Math.PI * 2, dist = 22 + rnd.nextDouble() * 34;
            int x = (int) Math.floor(player.x + Math.cos(ang) * dist), z = (int) Math.floor(player.z + Math.sin(ang) * dist);
            Chunk c = world.chunk(x >> 4, z >> 4);
            if (c == null || !c.lit) continue;
            boolean hostile = attempt % 2 == 1;
            if (hostile ? (stalkers >= 8 || player.creative) : (grazers >= 8 || world.isNight())) continue;
            int y = hostile ? 6 + rnd.nextInt(96) : Chunk.H - 2;
            while (y > 2 && !world.solid(x, y - 1, z)) y--;
            if (y <= 2 || world.solid(x, y, z) || world.solid(x, y + 1, z) || world.block(x, y, z) == Block.WATER) continue;
            int ground = world.block(x, y - 1, z), light = world.lightLevel(x, y, z);
            if (hostile ? (light > 4 || !Block.REG[ground].opaque) : (ground != Block.GRASS && ground != Block.SNOW_GRASS || light < 10)) continue;
            Mob m = new Mob(hostile ? Mob.STALKER : Mob.GRAZER);
            m.x = x + 0.5; m.y = y; m.z = z + 0.5; m.yaw = rnd.nextFloat() * 6.28f; m.fallStart = y;
            world.entities.add(m);
            if (hostile) stalkers++; else grazers++;
        }
    }

    void respawn() {
        Player p = player;
        p.dead = false; p.health = 20; p.hunger = 20; p.air = 10; p.exhaustion = 0;
        p.x = spawnX; p.y = spawnY; p.z = spawnZ; p.vx = p.vy = p.vz = 0; p.fallStart = p.y; p.hurtTime = 0;
        setScreen(SCR_NONE);
    }

    // ------------------------------------------------------------------ renderização

    float brightness(double x, double y, double z) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        float l = Math.max(world.sky(bx, by, bz) / 15f * daySmooth, world.blockLight(bx, by, bz) / 15f);
        return 0.03f + 0.97f * (float) Math.pow(l, 1.7);
    }

    private void box(Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1,
                     int tTop, int tSide, int tBottom, float r, float g, float b, float a) {
        for (int f = 0; f < 6; f++) {
            int tile = f == 2 ? tTop : f == 3 ? tBottom : tSide;
            float u0 = Atlas.u0(tile), u1 = Atlas.u1(tile), v0 = Atlas.v0(tile), v1 = Atlas.v1(tile);
            float sh = ChunkMesher.FACE_SHADE[f];
            int[] cn = ChunkMesher.CORN[f];
            float[] px = new float[4], py = new float[4], pz = new float[4];
            for (int k = 0; k < 4; k++) {
                v3.set(cn[k * 3] == 1 ? x1 : x0, cn[k * 3 + 1] == 1 ? y1 : y0, cn[k * 3 + 2] == 1 ? z1 : z0);
                m.transformPosition(v3);
                px[k] = v3.x; py[k] = v3.y; pz[k] = v3.z;
            }
            int[] order = {0, 1, 2, 0, 2, 3};
            for (int k : order)
                batch.v(px[k], py[k], pz[k], (k == 1 || k == 2) ? u1 : u0, k < 2 ? v1 : v0, r * sh, g * sh, b * sh, a);
        }
    }

    private void render() {
        Player p = player;
        double camX = p.x, camY = p.y + Player.EYE, camZ = p.z;
        float day = daySmooth;
        float frac = world.dayFraction();
        float sunH = (float) Math.sin(frac * Math.PI * 2);
        float dusk = Math.max(0, 1 - Math.abs(sunH) * 4) * 0.7f;
        skyColor[0] = (0.50f * day - 0.10f) * (1 - dusk) + 1.00f * dusk * day;
        skyColor[1] = (0.69f * day - 0.13f) * (1 - dusk) + 0.50f * dusk * day;
        skyColor[2] = (0.93f * day - 0.13f) * (1 - dusk) + 0.25f * dusk * day;
        for (int i = 0; i < 3; i++) skyColor[i] = Math.max(0.02f + 0.02f * i, skyColor[i]);
        boolean underwater = Block.isLiquid(world.block((int) Math.floor(camX), (int) Math.floor(camY), (int) Math.floor(camZ)));
        float fogStart = renderDist * 16 * 0.55f, fogEnd = renderDist * 16 - 6;
        float[] fog = skyColor.clone();
        if (underwater) { fogStart = 0; fogEnd = 26; fog[0] = 0.05f * day; fog[1] = 0.22f * day; fog[2] = 0.5f * day; }

        glViewport(0, 0, fbW, fbH);
        glClearColor(fog[0], fog[1], fog[2], 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        proj.setPerspective((float) Math.toRadians(sprinting ? 76 : 70), fbW / (float) fbH, 0.05f, 900f);
        view.identity().rotateX(-p.pitch).rotateY(p.yaw);
        proj.mul(view, vp);
        frustum.set(vp);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, atlasTex);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // sol e lua
        basicSh.use();
        basicSh.set("uMVP", vp); basicSh.set("uUseTex", 1); basicSh.set("uTex", 0);
        basicSh.set("uFog", 0f, 0f); basicSh.set("uFogColor", fog[0], fog[1], fog[2]);
        glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE);
        if (!underwater) {
            double a = frac * Math.PI * 2;
            for (int i = 0; i < 2; i++) {
                float dxs = (float) Math.cos(a) * (i == 0 ? 1 : -1), dys = (float) Math.sin(a) * (i == 0 ? 1 : -1);
                if (dys < -0.2f) continue;
                int tile = i == 0 ? Atlas.SUN : Atlas.MOON;
                float s = i == 0 ? 60 : 42, cx = dxs * 400, cy = dys * 400, ux = dys * s, uy = -dxs * s;
                float u0 = Atlas.u0(tile), u1 = Atlas.u1(tile), v0 = Atlas.v0(tile), v1 = Atlas.v1(tile);
                batch.v(cx - ux, cy - uy, -s, u0, v0, 1, 1, 1, 1); batch.v(cx - ux, cy - uy, s, u1, v0, 1, 1, 1, 1);
                batch.v(cx + ux, cy + uy, s, u1, v1, 1, 1, 1, 1); batch.v(cx - ux, cy - uy, -s, u0, v0, 1, 1, 1, 1);
                batch.v(cx + ux, cy + uy, s, u1, v1, 1, 1, 1, 1); batch.v(cx + ux, cy + uy, -s, u0, v1, 1, 1, 1, 1);
            }
            batch.draw(GL_TRIANGLES);
        }
        glEnable(GL_DEPTH_TEST); glEnable(GL_CULL_FACE);

        // terreno opaco
        int pcx = (int) Math.floor(p.x) >> 4, pcz = (int) Math.floor(p.z) >> 4;
        List<Chunk> visible = new ArrayList<>();
        for (Chunk c : world.chunks.values()) {
            if (c.count[0] == 0 && c.count[1] == 0) continue;
            if (Math.abs(c.cx - pcx) > renderDist || Math.abs(c.cz - pcz) > renderDist) continue;
            float ox = (float) (c.cx * 16 - camX), oy = (float) -camY, oz = (float) (c.cz * 16 - camZ);
            if (frustum.testAab(ox, oy, oz, ox + 16, oy + Chunk.H, oz + 16)) visible.add(c);
        }
        chunkSh.use();
        chunkSh.set("uVP", vp); chunkSh.set("uTex", 0); chunkSh.set("uDay", day);
        chunkSh.set("uFog", fogStart, fogEnd); chunkSh.set("uFogColor", fog[0], fog[1], fog[2]);
        chunkSh.set("uAlphaTest", 0.5f);
        glDisable(GL_BLEND);
        for (Chunk c : visible) {
            chunkSh.set("uOffset", (float) (c.cx * 16 - camX), (float) -camY, (float) (c.cz * 16 - camZ));
            c.draw(0);
        }
        glEnable(GL_BLEND);

        // entidades, seleção e rachaduras
        basicSh.use();
        basicSh.set("uFog", fogStart, fogEnd);
        for (Entity e : world.entities) drawEntity(e, camX, camY, camZ);
        if (target != null && screen == SCR_NONE && mineProgress > 0 && !hideHud) {
            tmp.translation((float) (target[0] - camX), (float) (target[1] - camY), (float) (target[2] - camZ));
            int t = Atlas.CRACK + Math.min(3, (int) (mineProgress * 4));
            box(tmp, -0.003f, -0.003f, -0.003f, 1.003f, 1.003f, 1.003f, t, t, t, 1, 1, 1, 1);
        }
        batch.draw(GL_TRIANGLES);
        if (target != null && screen == SCR_NONE && !hideHud) {
            float x0 = (float) (target[0] - camX) - 0.004f, y0 = (float) (target[1] - camY) - 0.004f, z0 = (float) (target[2] - camZ) - 0.004f;
            float x1 = x0 + 1.008f, y1 = y0 + 1.008f, z1 = z0 + 1.008f;
            float[][] e = {{x0, y0, z0, x1, y0, z0}, {x0, y0, z1, x1, y0, z1}, {x0, y1, z0, x1, y1, z0}, {x0, y1, z1, x1, y1, z1},
                    {x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x0, y0, z1, x0, y1, z1}, {x1, y0, z1, x1, y1, z1},
                    {x0, y0, z0, x0, y0, z1}, {x1, y0, z0, x1, y0, z1}, {x0, y1, z0, x0, y1, z1}, {x1, y1, z0, x1, y1, z1}};
            float u = Atlas.u0(Atlas.WHITE) + 0.01f, v = Atlas.v0(Atlas.WHITE) + 0.01f;
            for (float[] l : e) { batch.v(l[0], l[1], l[2], u, v, 0, 0, 0, 0.75f); batch.v(l[3], l[4], l[5], u, v, 0, 0, 0, 0.75f); }
            batch.draw(GL_LINES);
        }

        // nuvens
        glDisable(GL_CULL_FACE);
        glBindTexture(GL_TEXTURE_2D, cloudTex);
        basicSh.set("uFog", 180f, 620f);
        float cyl = (float) (118.5 - camY), R = 640, texel = 12f * 64;
        float scroll = (float) ((world.time % 240000) * 0.02 + (glfwGetTime() % 1) * 0);
        float cu = (float) (((camX + scroll) % texel) / texel), cv = (float) ((camZ % texel) / texel), cr = R / texel;
        float cb = 0.25f + 0.75f * day;
        batch.v(-R, cyl, -R, cu - cr, cv - cr, cb, cb, cb, 0.82f); batch.v(R, cyl, -R, cu + cr, cv - cr, cb, cb, cb, 0.82f);
        batch.v(R, cyl, R, cu + cr, cv + cr, cb, cb, cb, 0.82f); batch.v(-R, cyl, -R, cu - cr, cv - cr, cb, cb, cb, 0.82f);
        batch.v(R, cyl, R, cu + cr, cv + cr, cb, cb, cb, 0.82f); batch.v(-R, cyl, R, cu - cr, cv + cr, cb, cb, cb, 0.82f);
        batch.draw(GL_TRIANGLES);
        glBindTexture(GL_TEXTURE_2D, atlasTex);
        glEnable(GL_CULL_FACE);

        // água e gelo, de trás para frente
        visible.sort((a, b) -> Integer.compare(dist2(b, pcx, pcz), dist2(a, pcx, pcz)));
        chunkSh.use();
        chunkSh.set("uAlphaTest", 0.01f);
        for (Chunk c : visible) {
            if (c.count[1] == 0) continue;
            chunkSh.set("uOffset", (float) (c.cx * 16 - camX), (float) -camY, (float) (c.cz * 16 - camZ));
            c.draw(1);
        }

        // interface
        glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE);
        basicSh.use();
        basicSh.set("uFog", 0f, 0f);
        ui.render(underwater);
    }

    private static int dist2(Chunk c, int pcx, int pcz) { return (c.cx - pcx) * (c.cx - pcx) + (c.cz - pcz) * (c.cz - pcz); }

    private void drawEntity(Entity e, double camX, double camY, double camZ) {
        float ex = (float) (e.x - camX), ey = (float) (e.y - camY), ez = (float) (e.z - camZ);
        if (!frustum.testSphere(ex, ey + 0.6f, ez, 1.6f)) return;
        float l = brightness(e.x, e.y + 0.5, e.z);
        if (e instanceof ItemEntity it) {
            Item item = it.stack.item();
            tmp.translation(ex, ey + 0.12f + 0.06f * (float) Math.sin(it.age * 2.5), ez).rotateY(it.age * 1.6f);
            Block b = item.isBlock() ? Block.REG[item.id] : null;
            if (b != null && b.shape != Block.SHAPE_CROSS) box(tmp, -0.14f, 0, -0.14f, 0.14f, 0.28f, 0.14f, b.top, b.side, b.bottom, l, l, l, 1);
            else box(tmp, -0.17f, 0, -0.012f, 0.17f, 0.34f, 0.012f, item.tile, item.tile, Atlas.WHITE, l * 1.3f, l * 1.3f, l * 1.3f, 1);
            return;
        }
        Mob m = (Mob) e;
        tmp.translation(ex, ey, ez).rotateY(m.yaw);
        float hr = m.hurtTime > 0 ? 1.6f : 1f, hg = m.hurtTime > 0 ? 0.5f : 1f;
        float swing = (float) Math.sin(m.walkAnim) * 0.6f;
        int F = Atlas.FUR;
        if (m.type == Mob.GRAZER) {
            // Lanudo: corpo felpudo azul-petróleo, cabeça clara e dois chifres curvos
            float r = 0.36f * l * hr, g = 0.66f * l * hg, b = 0.68f * l * hg;
            box(tmp, -0.38f, 0.42f, -0.55f, 0.38f, 1.08f, 0.5f, F, F, F, r, g, b, 1);
            box(tmp, -0.24f, 0.62f, 0.5f, 0.24f, 1.12f, 0.92f, F, F, F, 0.93f * l * hr, 0.86f * l * hg, 0.72f * l * hg, 1);
            box(tmp, -0.34f, 1.08f, 0.56f, -0.22f, 1.34f, 0.68f, F, F, F, 0.95f * l, 0.62f * l, 0.3f * l, 1);
            box(tmp, 0.22f, 1.08f, 0.56f, 0.34f, 1.34f, 0.68f, F, F, F, 0.95f * l, 0.62f * l, 0.3f * l, 1);
            box(tmp, -0.26f, 0.9f, 0.92f, -0.14f, 1.0f, 0.94f, Atlas.WHITE, Atlas.WHITE, Atlas.WHITE, 0.08f, 0.08f, 0.1f, 1);
            box(tmp, 0.14f, 0.9f, 0.92f, 0.26f, 1.0f, 0.94f, Atlas.WHITE, Atlas.WHITE, Atlas.WHITE, 0.08f, 0.08f, 0.1f, 1);
            for (int i = 0; i < 4; i++) {
                float lx = (i % 2 == 0 ? -0.26f : 0.26f), lz = i < 2 ? -0.4f : 0.34f;
                tmp2.set(tmp).translate(lx, 0.44f, lz).rotateX((i == 0 || i == 3) ? swing : -swing);
                box(tmp2, -0.09f, -0.44f, -0.09f, 0.09f, 0, 0.09f, F, F, F, 0.93f * l * hr, 0.86f * l * hg, 0.72f * l * hg, 1);
            }
        } else {
            // Brasal: carapaça de rocha escura com olhos e fendas incandescentes
            float r = 0.26f * l * hr + 0.04f, g = 0.24f * l * hg + 0.03f, b = 0.3f * l * hg + 0.04f;
            box(tmp, -0.42f, 0.28f, -0.42f, 0.42f, 0.8f, 0.42f, Atlas.COBBLE, Atlas.COBBLE, Atlas.COBBLE, r * 1.6f, g * 1.6f, b * 1.6f, 1);
            box(tmp, -0.26f, 0.8f, -0.3f, 0.26f, 1.0f, 0.2f, Atlas.COBBLE, Atlas.COBBLE, Atlas.COBBLE, r * 1.3f, g * 1.3f, b * 1.3f, 1);
            int W = Atlas.WHITE;
            box(tmp, -0.3f, 0.52f, 0.42f, -0.12f, 0.66f, 0.44f, W, W, W, 1.5f, 0.75f, 0.1f, 1);
            box(tmp, 0.12f, 0.52f, 0.42f, 0.3f, 0.66f, 0.44f, W, W, W, 1.5f, 0.75f, 0.1f, 1);
            box(tmp, -0.2f, 0.34f, 0.42f, 0.2f, 0.4f, 0.44f, W, W, W, 1.5f, 0.45f, 0.05f, 1);
            box(tmp, -0.06f, 1.0f, -0.2f, 0.06f, 1.22f, -0.08f, W, W, W, 1.5f, 0.6f, 0.1f, 1);
            for (int i = 0; i < 4; i++) {
                float lx = (i % 2 == 0 ? -0.36f : 0.36f), lz = i < 2 ? -0.3f : 0.3f;
                tmp2.set(tmp).translate(lx, 0.3f, lz).rotateX((i == 0 || i == 3) ? swing : -swing);
                box(tmp2, -0.1f, -0.3f, -0.1f, 0.1f, 0, 0.1f, Atlas.COBBLE, Atlas.COBBLE, Atlas.COBBLE, r, g, b, 1);
            }
        }
    }

    void saveScreenshot(String file) {
        try {
            ByteBuffer buf = BufferUtils.createByteBuffer(fbW * fbH * 4);
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glReadBuffer(GL_BACK);
            glReadPixels(0, 0, fbW, fbH, GL_RGBA, GL_UNSIGNED_BYTE, buf);
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(fbW, fbH, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < fbH; y++)
                for (int x = 0; x < fbW; x++) {
                    int i = (y * fbW + x) * 4;
                    img.setRGB(x, fbH - 1 - y, (buf.get(i) & 255) << 16 | (buf.get(i + 1) & 255) << 8 | (buf.get(i + 2) & 255));
                }
            javax.imageio.ImageIO.write(img, "png", new File(file));
        } catch (IOException e) {
            System.err.println("Falha na captura: " + e);
        }
    }
}
