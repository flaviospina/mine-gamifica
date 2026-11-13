package terralume;

/** Registro de itens. Ids abaixo de 256 são blocos; os demais são itens puros. */
final class Item {
    static final Item[] REG = new Item[512];

    static final int STICK = 256, COAL = 257, IRON_INGOT = 258, GOLD_INGOT = 259, GEM = 260,
            RAW_MEAT = 261, COOKED_MEAT = 262, FRUIT = 263;
    static final int PICK = 270, AXE = 274, SHOVEL = 278, SWORD = 282; // + (tier-1)

    static final String[] TIER_NAME = {"", "de madeira", "de pedra", "de ferro", "de cristal"};
    static final float[] TIER_SPEED = {1, 2, 4, 6, 9};
    static final int[] TIER_USES = {0, 60, 132, 251, 1200};

    final int id;
    final String name;
    final int tile;
    int maxStack = 64;
    int tool = Block.TOOL_NONE, tier = 0;
    int damage = 1;
    int food = 0;
    int durability = 0;

    private Item(int id, String name, int tile) {
        this.id = id; this.name = name; this.tile = tile;
        REG[id] = this;
    }

    boolean isBlock() { return id < 256; }

    static Item of(int id) { return id >= 0 && id < REG.length ? REG[id] : null; }

    static void init() { }

    static {
        for (int i = 1; i < 256; i++) {
            Block b = Block.REG[i];
            if (b != null && i != Block.FURNACE_LIT) new Item(i, b.name, b.side);
        }
        new Item(STICK, "Graveto", Atlas.I_STICK);
        new Item(COAL, "Carvão", Atlas.I_COAL);
        new Item(IRON_INGOT, "Barra de ferro", Atlas.I_IRON);
        new Item(GOLD_INGOT, "Barra de ouro", Atlas.I_GOLD);
        new Item(GEM, "Cristal", Atlas.I_GEM);
        new Item(RAW_MEAT, "Carne crua", Atlas.I_RAW_MEAT).food = 3;
        new Item(COOKED_MEAT, "Carne assada", Atlas.I_COOKED_MEAT).food = 8;
        new Item(FRUIT, "Fruta", Atlas.I_FRUIT).food = 4;
        String[] names = {"Picareta", "Machado", "Pá", "Espada"};
        int[] base = {PICK, AXE, SHOVEL, SWORD};
        int[] tools = {Block.TOOL_PICK, Block.TOOL_AXE, Block.TOOL_SHOVEL, Block.TOOL_SWORD};
        int[] dmg = {2, 3, 2, 4};
        for (int k = 0; k < 4; k++) {
            for (int tier = 1; tier <= 4; tier++) {
                Item it = new Item(base[k] + tier - 1, names[k] + " " + TIER_NAME[tier], Atlas.I_TOOLS + k * 4 + tier - 1);
                it.maxStack = 1; it.tool = tools[k]; it.tier = tier;
                it.damage = dmg[k] + tier - 1; it.durability = TIER_USES[tier];
            }
        }
    }
}

/** Uma pilha de itens (com desgaste para ferramentas). */
final class ItemStack {
    int id, count, dmg;

    ItemStack(int id, int count) { this.id = id; this.count = count; }
    ItemStack(int id, int count, int dmg) { this.id = id; this.count = count; this.dmg = dmg; }

    Item item() { return Item.REG[id]; }
    ItemStack copy() { return new ItemStack(id, count, dmg); }
    boolean stacksWith(ItemStack o) { return o != null && o.id == id && item().maxStack > 1; }
}
