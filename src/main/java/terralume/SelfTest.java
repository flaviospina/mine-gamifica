package terralume;

import java.nio.file.Files;
import java.nio.file.Path;

/** Verificação sem janela das regras do jogo: java -cp target/terralume.jar terralume.SelfTest */
public final class SelfTest {
    private static int fails;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "  ok     " : "  FALHOU ") + name);
        if (!ok) fails++;
    }

    public static void main(String[] a) throws Exception {
        Item.init();
        Path dir = Files.createTempDirectory("terralume-teste");
        World w = new World(1234, dir);
        for (int z = -2; z <= 2; z++) for (int x = -2; x <= 2; x++) w.request(x, z);
        long t0 = System.currentTimeMillis();
        while (w.chunks.size() < 25 && System.currentTimeMillis() - t0 < 60000) { w.integrate(4); Thread.sleep(5); }
        check("25 chunks gerados e iluminados", w.chunks.size() == 25);

        // geração determinística
        Chunk g1 = new WorldGen(1234).generate(0, 0);
        check("geração determinística", java.util.Arrays.equals(g1.blocks, w.chunk(0, 0).blocks));

        // coluna de teste: topo sólido
        int x = 5, z = 7, y = Chunk.H - 1;
        while (y > 0 && w.block(x, y, z) == Block.AIR) y--;
        int top = y + 1;
        while (!Block.REG[w.block(x, top - 1, z)].solid) { top--; }
        check("luz do céu = 15 acima do terreno", w.sky(x, top + 3, z) == 15);

        Chunk c = w.chunk(0, 0);
        byte[] before = c.light.clone();
        w.setBlock(x, top + 2, z, Block.TORCH);
        check("tocha emite luz 14", w.blockLight(x, top + 2, z) == 14 && w.blockLight(x + 3, top + 2, z) == 11);
        w.setBlock(x, top + 2, z, Block.AIR);
        check("remover tocha restaura a luz", java.util.Arrays.equals(before, c.light));
        w.setBlock(x, top + 6, z, Block.STONE);
        check("bloco opaco faz sombra", w.sky(x, top + 5, z) < 15);
        w.setBlock(x, top + 6, z, Block.AIR);
        check("remover bloco restaura a luz do céu", java.util.Arrays.equals(before, c.light));

        // gravidade de blocos
        w.setBlock(x, top + 8, z, Block.SAND);
        int rest = top;
        while (!Block.REG[w.block(x, rest - 1, z)].solid) rest--;
        check("areia cai até o chão", w.block(x, top + 8, z) == Block.AIR && w.block(x, rest, z) == Block.SAND);

        // planta sem apoio quebra
        w.setBlock(x + 1, top + 10, z, Block.STONE);
        w.setBlock(x + 1, top + 11, z, Block.FLOWER_RED);
        w.setBlock(x + 1, top + 10, z, Block.AIR);
        check("flor sem apoio quebra e vira item", w.block(x + 1, top + 11, z) == Block.AIR && !w.entities.isEmpty());

        // raio
        int[] hit = w.raycast(x + 0.5, top + 20, z + 0.5, 0, -1, 0, 60);
        check("raio acerta o chão por cima", hit != null && hit[4] == 1);

        // física
        Entity e = new Entity();
        e.x = 20.5; e.z = 20.5; e.y = 120; e.fallStart = e.y;
        for (int i = 0; i < 2000 && !e.onGround && !e.inWater; i++) e.physics(w, 1 / 60.0);
        check("entidade pousa sem atravessar o chão", (e.onGround && e.y == Math.floor(e.y) && w.solid(20, (int) e.y - 1, 20)) || e.inWater);

        // receitas
        ItemStack[] g2 = new ItemStack[4];
        g2[3] = new ItemStack(Block.LOG, 1);
        ItemStack r = Recipes.match(g2, 2);
        check("tronco -> 4 tábuas", r != null && r.id == Block.PLANKS && r.count == 4);
        ItemStack[] g3 = new ItemStack[9];
        for (int i = 0; i < 3; i++) g3[i] = new ItemStack(Block.COBBLE, 1);
        g3[4] = new ItemStack(Item.STICK, 1); g3[7] = new ItemStack(Item.STICK, 1);
        r = Recipes.match(g3, 3);
        check("picareta de pedra", r != null && r.id == Item.PICK + 1);
        g3 = new ItemStack[9];
        g3[1] = new ItemStack(Item.IRON_INGOT, 1); g3[2] = new ItemStack(Item.IRON_INGOT, 1);
        g3[4] = new ItemStack(Item.STICK, 1); g3[5] = new ItemStack(Item.IRON_INGOT, 1); g3[7] = new ItemStack(Item.STICK, 1);
        r = Recipes.match(g3, 3);
        check("machado espelhado", r != null && r.id == Item.AXE + 2);
        g3[0] = new ItemStack(Block.DIRT, 1);
        check("grade inválida não gera nada", Recipes.match(g3, 3) == null);

        // fornalha
        Furnace f = new Furnace();
        f.input = new ItemStack(Block.IRON_ORE, 2); f.fuel = new ItemStack(Item.COAL, 1);
        for (int i = 0; i < Furnace.COOK_TIME * 2 + 5; i++) f.tick();
        check("fornalha funde 2 minérios com 1 carvão", f.output != null && f.output.id == Item.IRON_INGOT && f.output.count == 2 && f.input == null);

        // inventário
        Player p = new Player();
        p.give(new ItemStack(Block.DIRT, 100));
        check("inventário empilha em 64", p.inv[0].count == 64 && p.inv[1].count == 36);

        // água inunda cavidade abaixo do nível do mar
        w.setBlock(10, 20, 10, Block.WATER);
        w.setBlock(11, 20, 10, Block.STONE);
        w.setBlock(11, 20, 10, Block.AIR);
        for (int i = 0; i < 3; i++) w.tickWater();
        check("água preenche o bloco removido ao lado", w.block(11, 20, 10) == Block.WATER);

        // salvar e recarregar
        w.setBlock(3, 100, 3, Block.GOLD_BLOCK);
        w.saveAll();
        World w2 = new World(1234, dir);
        w2.request(0, 0);
        t0 = System.currentTimeMillis();
        while (w2.chunks.isEmpty() && System.currentTimeMillis() - t0 < 20000) { w2.integrate(1); Thread.sleep(5); }
        check("chunk salvo é recarregado do disco", w2.block(3, 100, 3) == Block.GOLD_BLOCK);
        w.shutdown(); w2.shutdown();

        System.out.println(fails == 0 ? "TODOS OS TESTES PASSARAM" : fails + " TESTE(S) FALHARAM");
        System.exit(fails == 0 ? 0 : 1);
    }
}
