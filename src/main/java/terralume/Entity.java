package terralume;

import java.util.Random;

/** Corpo físico com caixa de colisão alinhada aos eixos (posição = centro dos pés). */
class Entity {
    double x, y, z, vx, vy, vz;
    float w = 0.6f, h = 1.8f, yaw;
    boolean onGround, inWater, hitWall, flying, dead;
    int health = 20;
    float hurtTime;
    double fallStart;

    boolean collides(World wd, double px, double py, double pz) {
        int x0 = (int) Math.floor(px - w / 2), x1 = (int) Math.floor(px + w / 2);
        int y0 = (int) Math.floor(py), y1 = (int) Math.floor(py + h);
        int z0 = (int) Math.floor(pz - w / 2), z1 = (int) Math.floor(pz + w / 2);
        for (int by = y0; by <= y1; by++)
            for (int bz = z0; bz <= z1; bz++)
                for (int bx = x0; bx <= x1; bx++) if (wd.solid(bx, by, bz)) return true;
        return false;
    }

    /** Integra gravidade e move com colisão eixo a eixo. Devolve a altura da queda ao pousar (ou 0). */
    double physics(World wd, double dt) {
        int feet = wd.block((int) Math.floor(x), (int) Math.floor(y + 0.3), (int) Math.floor(z));
        inWater = Block.isLiquid(feet);
        if (!flying) {
            if (inWater) { vy -= 9 * dt; vy *= Math.pow(0.12, dt); vx *= Math.pow(0.25, dt); vz *= Math.pow(0.25, dt); }
            else vy -= 28 * dt;
            if (vy < -55) vy = -55;
        }
        boolean wasGround = onGround;
        double dx = vx * dt, dy = vy * dt, dz = vz * dt;
        int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) / 0.35);
        if (steps < 1) steps = 1;
        onGround = false; hitWall = false;
        for (int s = 0; s < steps; s++) {
            double ny = y + dy / steps;
            if (collides(wd, x, ny, z)) {
                if (dy < 0) { ny = Math.floor(ny) + 1; onGround = true; } else ny = Math.floor(ny + h) - h - 1e-4;
                if (collides(wd, x, ny, z)) ny = y;
                vy = 0; dy = 0;
            }
            y = ny;
            double nx = x + dx / steps;
            if (collides(wd, nx, y, z)) {
                nx = dx > 0 ? Math.floor(nx + w / 2) - w / 2 - 1e-4 : Math.floor(nx - w / 2) + 1 + w / 2 + 1e-4;
                if (collides(wd, nx, y, z)) nx = x;
                vx = 0; dx = 0; hitWall = true;
            }
            x = nx;
            double nz = z + dz / steps;
            if (collides(wd, x, y, nz)) {
                nz = dz > 0 ? Math.floor(nz + w / 2) - w / 2 - 1e-4 : Math.floor(nz - w / 2) + 1 + w / 2 + 1e-4;
                if (collides(wd, x, y, nz)) nz = z;
                vz = 0; dz = 0; hitWall = true;
            }
            z = nz;
        }
        double fell = 0;
        if (onGround || inWater || flying) {
            if (onGround && !wasGround) fell = fallStart - y;
            fallStart = y;
        } else if (y > fallStart) fallStart = y;
        if (hurtTime > 0) hurtTime -= dt;
        return fell;
    }

    void hurt(int dmg, double fromX, double fromZ) {
        if (hurtTime > 0 || dead) return;
        health -= dmg; hurtTime = 0.45f;
        double dx = x - fromX, dz = z - fromZ, d = Math.sqrt(dx * dx + dz * dz);
        if (d > 0.01) { vx += dx / d * 6; vz += dz / d * 6; vy = 5.5; }
        if (health <= 0) dead = true;
    }

    /** Interseção raio/caixa; devolve a distância ou -1. */
    double rayHit(double ox, double oy, double oz, double dx, double dy, double dz) {
        double tmin = 0, tmax = 1e9;
        double[] o = {ox, oy, oz}, d = {dx, dy, dz}, lo = {x - w / 2, y, z - w / 2}, hi = {x + w / 2, y + h, z + w / 2};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-9) { if (o[i] < lo[i] || o[i] > hi[i]) return -1; continue; }
            double t1 = (lo[i] - o[i]) / d[i], t2 = (hi[i] - o[i]) / d[i];
            if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
            tmin = Math.max(tmin, t1); tmax = Math.min(tmax, t2);
            if (tmin > tmax) return -1;
        }
        return tmin;
    }
}

/** Item solto no chão, recolhido ao encostar. */
final class ItemEntity extends Entity {
    final ItemStack stack;
    float age;

    ItemEntity(ItemStack s) { stack = s; w = 0.25f; h = 0.25f; }
}

/** Criaturas originais: o Lanudo (pacífico, pasta de dia) e o Brasal (hostil, surge no escuro). */
final class Mob extends Entity {
    static final int GRAZER = 0, STALKER = 1;
    final int type;
    float walkAnim, thinkTimer, attackTimer, fleeTimer;
    double tx, tz;
    boolean moving;

    Mob(int type) {
        this.type = type;
        if (type == GRAZER) { w = 0.8f; h = 1.2f; health = 8; }
        else { w = 0.8f; h = 1.0f; health = 16; }
    }

    void update(World wd, Player p, double dt, Random r) {
        double dxp = p.x - x, dzp = p.z - z, dist = Math.sqrt(dxp * dxp + dzp * dzp + (p.y - y) * (p.y - y));
        double speed = 0;
        thinkTimer -= dt; attackTimer -= dt; fleeTimer -= dt;
        boolean chase = type == STALKER && !p.creative && !p.dead && dist < 18;
        if (chase) {
            tx = p.x; tz = p.z; moving = dist > 1.1; speed = 3.4;
            if (dist < 1.7 && attackTimer <= 0) { p.hurt(3, x, z); attackTimer = 1f; }
        } else if (fleeTimer > 0) {
            tx = x - dxp * 4; tz = z - dzp * 4; moving = true; speed = 4.2;
        } else {
            if (thinkTimer <= 0) {
                thinkTimer = 2 + r.nextFloat() * 5;
                moving = r.nextInt(3) != 0;
                tx = x + r.nextInt(17) - 8; tz = z + r.nextInt(17) - 8;
            }
            speed = 1.4;
        }
        double ddx = tx - x, ddz = tz - z, dl = Math.sqrt(ddx * ddx + ddz * ddz);
        if (moving && dl > 0.4) {
            float want = (float) Math.atan2(ddx, ddz);
            float diff = want - yaw;
            while (diff > Math.PI) diff -= 2 * Math.PI;
            while (diff < -Math.PI) diff += 2 * Math.PI;
            yaw += diff * Math.min(1, dt * 8);
            double k = Math.min(1, dt * 10);
            vx += (Math.sin(yaw) * speed - vx) * k; vz += (Math.cos(yaw) * speed - vz) * k;
            walkAnim += dt * speed * 3.2;
        } else {
            double k = Math.min(1, dt * 10);
            vx -= vx * k; vz -= vz * k;
            moving = chase;
        }
        if (inWater) vy = Math.max(vy, 2.5);
        else if (hitWall && onGround && moving) vy = 8.4;
        double fell = physics(wd, dt);
        if (fell > 4) hurt((int) (fell - 3), x, z);
    }

    @Override
    void hurt(int dmg, double fromX, double fromZ) {
        super.hurt(dmg, fromX, fromZ);
        if (type == GRAZER) fleeTimer = 5;
    }
}

/** O jogador: inventário, vida, fome, fôlego e modo de jogo. */
final class Player extends Entity {
    static final float EYE = 1.62f;
    float pitch;
    boolean creative;
    int hunger = 20, air = 10;
    float exhaustion, regenTimer, airTimer;
    final ItemStack[] inv = new ItemStack[36];
    int selected;

    ItemStack held() { return inv[selected]; }

    /** Tenta guardar a pilha; devolve o que não coube (ou null). */
    ItemStack give(ItemStack s) {
        Item it = s.item();
        if (it.maxStack > 1)
            for (int i = 0; i < inv.length && s.count > 0; i++)
                if (inv[i] != null && inv[i].id == s.id && inv[i].count < it.maxStack) {
                    int n = Math.min(s.count, it.maxStack - inv[i].count);
                    inv[i].count += n; s.count -= n;
                }
        for (int i = 0; i < inv.length && s.count > 0; i++)
            if (inv[i] == null) {
                int n = Math.min(s.count, it.maxStack);
                inv[i] = new ItemStack(s.id, n, s.dmg);
                s.count -= n;
            }
        return s.count > 0 ? s : null;
    }

    void damageTool() {
        ItemStack s = held();
        if (creative || s == null || s.item().durability == 0) return;
        if (++s.dmg >= s.item().durability) inv[selected] = null;
    }
}
