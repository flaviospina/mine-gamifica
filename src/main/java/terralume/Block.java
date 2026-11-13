package terralume;

/** Registro de blocos: aparência, física, luz e regras de mineração. */
final class Block {
    static final Block[] REG = new Block[256];

    static final int SHAPE_NONE = 0, SHAPE_CUBE = 1, SHAPE_CROSS = 2, SHAPE_LIQUID = 3;
    static final int TOOL_NONE = 0, TOOL_PICK = 1, TOOL_AXE = 2, TOOL_SHOVEL = 3, TOOL_SWORD = 4;

    static final int AIR = 0, STONE = 1, GRASS = 2, DIRT = 3, COBBLE = 4, PLANKS = 5, LOG = 6, LEAVES = 7,
            SAND = 8, GRAVEL = 9, WATER = 10, GLASS = 11, COAL_ORE = 12, IRON_ORE = 13, GOLD_ORE = 14,
            GEM_ORE = 15, BEDROCK = 16, SNOW_GRASS = 17, SNOW = 18, ICE = 19, CACTUS = 20, SANDSTONE = 21,
            STONE_BRICKS = 22, WORKBENCH = 23, FURNACE = 24, TORCH = 25, TALL_GRASS = 26, FLOWER_RED = 27,
            FLOWER_YELLOW = 28, PINE_LOG = 29, PINE_LEAVES = 30, IRON_BLOCK = 31, GOLD_BLOCK = 32,
            GEM_BLOCK = 33, LAMP = 34, DEAD_BUSH = 35, FURNACE_LIT = 36;

    final int id;
    final String name;
    int top, side, bottom;
    int shape = SHAPE_CUBE;
    boolean solid = true;        // colide com entidades
    boolean opaque = true;       // cubo cheio que esconde faces vizinhas
    boolean translucent = false; // desenhado na passada com transparência
    boolean cullSame = false;    // não desenha faces entre blocos iguais
    boolean replaceable = false; // pode ser substituído ao colocar um bloco
    boolean needsGround = false; // quebra se o bloco de baixo sumir
    boolean falls = false;       // sofre gravidade
    int opacity = 15;            // quanto de luz absorve
    int light = 0;               // luz emitida
    float hardness = 1f;
    int tool = TOOL_NONE;
    int minTier = 0;             // nível mínimo de picareta para dropar
    int drop;                    // id do item dropado (-1 = nada)
    int dropCount = 1;

    private Block(int id, String name, int top, int side, int bottom) {
        this.id = id; this.name = name; this.top = top; this.side = side; this.bottom = bottom;
        this.drop = id;
        REG[id] = this;
    }

    private static Block cube(int id, String name, int tile) { return new Block(id, name, tile, tile, tile); }
    private static Block cube(int id, String name, int top, int side, int bottom) { return new Block(id, name, top, side, bottom); }

    private Block hard(float h, int tool, int minTier) { this.hardness = h; this.tool = tool; this.minTier = minTier; return this; }
    private Block drops(int item, int count) { this.drop = item; this.dropCount = count; return this; }
    private Block emits(int l) { this.light = l; return this; }
    private Block seeThrough(int opacity) { this.opaque = false; this.opacity = opacity; return this; }
    private Block plant(int tile) {
        this.shape = SHAPE_CROSS; this.solid = false; this.opaque = false; this.opacity = 0;
        this.needsGround = true; this.hardness = 0; this.top = this.side = this.bottom = tile;
        return this;
    }

    static boolean isLiquid(int id) { return id == WATER; }

    static {
        Block air = new Block(AIR, "Ar", 0, 0, 0);
        air.shape = SHAPE_NONE; air.solid = false; air.opaque = false; air.opacity = 0; air.replaceable = true; air.drop = -1;

        cube(STONE, "Pedra", Atlas.STONE).hard(1.5f, TOOL_PICK, 1).drops(COBBLE, 1);
        cube(GRASS, "Grama", Atlas.GRASS_TOP, Atlas.GRASS_SIDE, Atlas.DIRT).hard(0.6f, TOOL_SHOVEL, 0).drops(DIRT, 1);
        cube(DIRT, "Terra", Atlas.DIRT).hard(0.5f, TOOL_SHOVEL, 0);
        cube(COBBLE, "Pedregulho", Atlas.COBBLE).hard(2f, TOOL_PICK, 1);
        cube(PLANKS, "Tábuas", Atlas.PLANKS).hard(2f, TOOL_AXE, 0);
        cube(LOG, "Tronco", Atlas.LOG_TOP, Atlas.LOG_SIDE, Atlas.LOG_TOP).hard(2f, TOOL_AXE, 0);
        cube(LEAVES, "Folhas", Atlas.LEAVES).hard(0.2f, TOOL_NONE, 0).drops(-1, 0).seeThrough(1);
        cube(SAND, "Areia", Atlas.SAND).hard(0.5f, TOOL_SHOVEL, 0).falls = true;
        cube(GRAVEL, "Cascalho", Atlas.GRAVEL).hard(0.6f, TOOL_SHOVEL, 0).falls = true;
        Block water = cube(WATER, "Água", Atlas.WATER).seeThrough(2).drops(-1, 0);
        water.shape = SHAPE_LIQUID; water.solid = false; water.translucent = true; water.cullSame = true;
        water.replaceable = true; water.hardness = -1;
        cube(GLASS, "Vidro", Atlas.GLASS).hard(0.3f, TOOL_NONE, 0).drops(-1, 0).seeThrough(0).cullSame = true;
        cube(COAL_ORE, "Minério de carvão", Atlas.COAL_ORE).hard(3f, TOOL_PICK, 1).drops(Item.COAL, 1);
        cube(IRON_ORE, "Minério de ferro", Atlas.IRON_ORE).hard(3f, TOOL_PICK, 2);
        cube(GOLD_ORE, "Minério de ouro", Atlas.GOLD_ORE).hard(3f, TOOL_PICK, 3);
        cube(GEM_ORE, "Minério de cristal", Atlas.GEM_ORE).hard(3.5f, TOOL_PICK, 3).drops(Item.GEM, 1);
        cube(BEDROCK, "Rocha-mãe", Atlas.BEDROCK).hard(-1, TOOL_NONE, 0).drops(-1, 0);
        cube(SNOW_GRASS, "Grama nevada", Atlas.SNOW, Atlas.SNOW_GRASS_SIDE, Atlas.DIRT).hard(0.6f, TOOL_SHOVEL, 0).drops(DIRT, 1);
        cube(SNOW, "Neve", Atlas.SNOW).hard(0.3f, TOOL_SHOVEL, 0);
        Block ice = cube(ICE, "Gelo", Atlas.ICE).hard(0.5f, TOOL_PICK, 0).drops(-1, 0).seeThrough(1);
        ice.translucent = true; ice.cullSame = true;
        cube(CACTUS, "Cacto", Atlas.CACTUS_TOP, Atlas.CACTUS_SIDE, Atlas.CACTUS_TOP).hard(0.4f, TOOL_NONE, 0).needsGround = true;
        cube(SANDSTONE, "Arenito", Atlas.SANDSTONE).hard(0.8f, TOOL_PICK, 1);
        cube(STONE_BRICKS, "Tijolos de pedra", Atlas.STONE_BRICKS).hard(1.5f, TOOL_PICK, 1);
        cube(WORKBENCH, "Bancada", Atlas.WORKBENCH_TOP, Atlas.WORKBENCH_SIDE, Atlas.PLANKS).hard(2.5f, TOOL_AXE, 0);
        cube(FURNACE, "Fornalha", Atlas.FURNACE_SIDE, Atlas.FURNACE_FRONT, Atlas.FURNACE_SIDE).hard(3.5f, TOOL_PICK, 1);
        cube(TORCH, "Tocha", Atlas.TORCH).plant(Atlas.TORCH).emits(14);
        Block tg = cube(TALL_GRASS, "Capim", Atlas.TALL_GRASS).plant(Atlas.TALL_GRASS).drops(-1, 0);
        tg.replaceable = true;
        cube(FLOWER_RED, "Flor vermelha", Atlas.FLOWER_RED).plant(Atlas.FLOWER_RED);
        cube(FLOWER_YELLOW, "Flor amarela", Atlas.FLOWER_YELLOW).plant(Atlas.FLOWER_YELLOW);
        cube(PINE_LOG, "Tronco de pinheiro", Atlas.LOG_TOP, Atlas.PINE_LOG_SIDE, Atlas.LOG_TOP).hard(2f, TOOL_AXE, 0);
        cube(PINE_LEAVES, "Folhas de pinheiro", Atlas.PINE_LEAVES).hard(0.2f, TOOL_NONE, 0).drops(-1, 0).seeThrough(1);
        cube(IRON_BLOCK, "Bloco de ferro", Atlas.IRON_BLOCK).hard(5f, TOOL_PICK, 2);
        cube(GOLD_BLOCK, "Bloco de ouro", Atlas.GOLD_BLOCK).hard(3f, TOOL_PICK, 3);
        cube(GEM_BLOCK, "Bloco de cristal", Atlas.GEM_BLOCK).hard(5f, TOOL_PICK, 3);
        cube(LAMP, "Lampião", Atlas.LAMP).hard(0.3f, TOOL_NONE, 0).emits(15);
        cube(DEAD_BUSH, "Arbusto seco", Atlas.DEAD_BUSH).plant(Atlas.DEAD_BUSH).drops(Item.STICK, 1);
        cube(FURNACE_LIT, "Fornalha acesa", Atlas.FURNACE_SIDE, Atlas.FURNACE_FRONT_LIT, Atlas.FURNACE_SIDE)
                .hard(3.5f, TOOL_PICK, 1).drops(FURNACE, 1).emits(13);
    }
}
