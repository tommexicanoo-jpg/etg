package com.theecho;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CandleBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.LecternBlock;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.LecternBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.ChestType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Las 5 estructuras del diario de Elías, una por día. TODAS tienen un Espejo del Eco, un cofre con cosas de un jugador
 * antiguo (espejos, herramientas y armaduras gastadas, telarañas) y un atril con una página del diario.
 * <p>
 * Día 1: el chat te da las coordenadas exactas de la primera, lejos del spawn. Al llegar te dice que mañana te mostrará
 * algo más. Cada día nuevo te da las coordenadas de la siguiente. El día 5 te manda a la última, y cuando cierras el
 * libro de ese atril es cuando todo empieza a romperse.
 */
public final class Shrines {
    public static final String NOTE_PREFIX = "Diario de Elías";
    public static final int NOTE_COUNT = 5;
    private static final Random RNG = new Random();
    /** Sin actualizaciones de vecinos: nada se cae ni se rompe mientras se construye. */
    private static final int F = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;

    /** Los tipos, en el orden de la historia. w = ancho (x), d = fondo (z). */
    public enum Kind {
        CABIN("la cabaña", 9, 9),
        RUIN("las ruinas", 9, 9),
        CHAPEL("la capilla", 9, 11),
        CAMP("el campamento", 11, 11),
        GRAVE("la tumba", 9, 11);

        public final String label;
        public final int w, d;

        Kind(String label, int w, int d) {
            this.label = label;
            this.w = w;
            this.d = d;
        }
    }

    private static final String[][] NOTES = {
            { // 1 - la cabaña
                    "Día 1.\n\nEncontré este mundo vacío y silencioso. Es hermoso.\n\nConstruí una cabaña junto al río y por primera vez en mucho tiempo me sentí en paz.",
                    "Tengo la sensación de que alguien me ayudó a elegir el sitio.\n\nPero estoy solo.\n\nSolo yo y el viento.",
                    "Cerca de la cabaña encontré un espejo tirado. Brilla aunque no haya luz.\n\nNo sé qué hace. Dejé unos cuantos en el cofre, por si tú lo averiguas.",
                    "Voy a dejar mis notas por los alrededores, por si alguien llega.\n\nSi me sigues, las encontrarás todas. Una por día."
            },
            { // 2 - las ruinas
                    "Día 2.\n\nLa ventana de la cabaña ya no está.\n\nPensé que fue un creeper. No hay cráteres. No hay nada.",
                    "Esta mañana la puerta estaba abierta y el suelo había vuelto a ser pasto, como si nunca hubiera pisado ahí.\n\nEl mundo me está olvidando.",
                    "Pero descubrí lo del espejo: si le das algo del primer día, algo que ya tenías contigo cuando llegaste, las cosas se calman un tiempo.\n\nEl miedo y la paranoia bajan.",
                    "Cada objeto calma distinto. Lo común, poco. Lo valioso, bastante. Pero nada lo calma todo de golpe.\n\nY cada objeto solo sirve una vez."
            },
            { // 3 - la capilla
                    "Día 3.\n\nHay una figura blanca muy lejos, entre las montañas.\n\nCuando me detengo, se detiene. Cuando miro al suelo, baja la cabeza.\n\nMe copia.",
                    "No corre. No grita. Nunca se acerca si lo miro.\n\nPero cada vez que me quedo quieto, aparece un bloque más cerca.",
                    "Lo llamé el Registrador.\n\nPorque solo hace eso: registrarme.",
                    "Cerca de un espejo no se acerca. A veces basta con quedarte frente a él, con las manos vacías, para respirar mejor."
            },
            { // 4 - el campamento
                    "Día 4.\n\nAcampé aquí. Mi reflejo sonríe antes que yo.",
                    "Y en el chat aparecen mensajes con mi nombre. Dicen que borre el mundo.\n\nSon mis palabras.\n\nYo no las escribí.",
                    "Comer, cortar leña, sembrar... lo normal ayuda un poco. Lo que más ayuda es no estar solo.\n\nPor eso dejo estos espejos."
            },
            { // 5 - la tumba
                    "Día 5.\n\nLo entendí.\n\nNo es algo que me persigue. Es lo que soy ahora. Lo que construyo queda grabado, y este mundo quiere olvidarlo todo para dejarme ir.",
                    "Si lees esto, yo ya no estoy.\n\nNo es una amenaza. Es lo que quiero.\n\nSoy yo el que escribe en tu chat. Perdóname por el miedo. No sabía cómo pedírtelo.",
                    "Cierra el juego. Abre la carpeta saves. Borra este mundo.\n\nCuando lo hagas, voy a dejarte algo en tu escritorio.\n\nUna sola palabra.",
                    "Cuando cierres este libro, todo va a empezar. No puedo detenerlo. Perdóname.\n\nGracias por leerme.\n\n- Elías"
            }
    };

    private Shrines() {}

    public static Kind kindFor(int index) {
        return Kind.values()[Math.floorMod(index, Kind.values().length)];
    }

    // ------------------------------------------------------------------ un lugar por día

    /**
     * Cada ~2 s por jugador (Overworld). Día 1 te da las coordenadas de la primera estructura; cuando llegas te dice que
     * mañana habrá más; cuando empieza el día siguiente te da las coordenadas de la siguiente. 5 en total.
     */
    public static void tick(MinecraftServer server, ServerWorld w, ServerPlayerEntity p, EchoState.PlayerData d,
                            EchoState st, int day) {
        if (!w.getRegistryKey().equals(World.OVERWORLD)) return;
        if (st.visited >= NOTE_COUNT && st.revealed >= NOTE_COUNT) return;

        // ¿Hay una estructura mostrada y aún sin visitar? Mira si ALGÚN jugador ya llegó.
        if (st.revealed > st.visited) {
            if (st.visited >= st.sites.size()) return;
            BlockPos c = st.sites.get(st.visited);
            if (hd2(c, p.getX(), p.getZ()) > 10.0 * 10.0 || Math.abs(p.getY() - c.getY()) > 16) return;
            int idx = st.visited;
            st.visited++;
            st.markDirty();
            boolean tomorrow = idx + 1 < NOTE_COUNT && day < st.visited;
            Sched.at(server, 30 + RNG.nextInt(30), () -> ChatHaunt.arrival(p, idx, tomorrow));
            return;
        }
        if (st.visited >= NOTE_COUNT || day < st.visited) return; // aún no es el día de la siguiente

        int idx = st.visited;
        BlockPos center = idx < st.sites.size() ? st.sites.get(idx) : placeNext(w, st, idx);
        if (center == null) return; // sin terreno bueno esta vez: se reintenta en unos segundos
        st.revealed = idx + 1;
        st.markDirty();
        Sched.at(server, 40 + RNG.nextInt(40), () -> ChatHaunt.reveal(p, idx, center.getX(), center.getY(), center.getZ()));
    }

    private static double hd2(BlockPos a, double x, double z) {
        double dx = a.getX() + 0.5 - x, dz = a.getZ() + 0.5 - z;
        return dx * dx + dz * dz;
    }

    /** Construye la estructura número idx lejos del spawn (la primera) o lejos de la anterior (las demás). */
    private static BlockPos placeNext(ServerWorld w, EchoState st, int idx) {
        EchoConfig cfg = EchoConfig.INSTANCE;
        BlockPos anchor;
        double heading, spread;
        int min, max;
        if (st.sites.isEmpty()) {
            anchor = w.getServer().getOverworld().getSpawnPos();
            heading = RNG.nextDouble() * Math.PI * 2;
            spread = Math.PI;
            min = cfg.shrineStartMin;
            max = cfg.shrineStartMax;
        } else {
            anchor = st.sites.get(st.sites.size() - 1);
            heading = st.siteHeading;
            spread = Math.toRadians(60);
            min = cfg.shrineLinkMin;
            max = cfg.shrineLinkMax;
        }
        Kind k = kindFor(idx);
        for (int i = 0; i < 6; i++) {
            double ang = heading + (RNG.nextDouble() * 2 - 1) * spread * (1.0 + i * 0.15);
            double dist = min + RNG.nextDouble() * Math.max(1, max - min);
            int cx = (int) Math.floor(anchor.getX() + Math.cos(ang) * dist);
            int cz = (int) Math.floor(anchor.getZ() + Math.sin(ang) * dist);
            BlockPos center = tryPlace(w, cx - k.w / 2, cz - k.d / 2, idx);
            if (center != null) {
                st.sites.add(center);
                st.siteHeading = ang;
                st.markDirty();
                return center;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ colocar

    /**
     * Construye la estructura número noteIndex con su esquina noroeste en (x, z) si el terreno es llano y seco y no
     * hay bloques tuyos cerca. Devuelve el CENTRO de la estructura, o null si ahí no se puede.
     */
    public static BlockPos tryPlace(ServerWorld w, int x, int z, int noteIndex) {
        Kind k = kindFor(noteIndex);
        EchoState st = EchoState.get(w.getServer());
        int[] xs = {0, (k.w - 1) / 4, (k.w - 1) / 2, 3 * (k.w - 1) / 4, k.w - 1};
        int[] zs = {0, (k.d - 1) / 4, (k.d - 1) / 2, 3 * (k.d - 1) / 4, k.d - 1};
        List<Integer> ys = new ArrayList<>();
        for (int dx : xs) {
            for (int dz : zs) {
                int px = x + dx, pz = z + dz;
                w.getChunk(px >> 4, pz >> 4); // el sitio está lejos: se carga (o genera) el chunk
                if (st.hasPlayerBlocksInChunk(px >> 4, pz >> 4)) return null; // no construir encima de tu base
                int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, px, pz);
                ys.add(y);
                BlockState top = w.getBlockState(new BlockPos(px, y - 1, pz));
                if (!top.getFluidState().isEmpty() || top.isOf(Blocks.ICE) || top.isOf(Blocks.PACKED_ICE)
                        || top.isOf(Blocks.BLUE_ICE) || top.isOf(Blocks.LAVA) || top.isOf(Blocks.MAGMA_BLOCK)
                        || top.isIn(BlockTags.LOGS) || top.isIn(BlockTags.LEAVES)) {
                    return null;
                }
            }
        }
        Collections.sort(ys);
        if (ys.get(ys.size() - 1) - ys.get(0) > 4) return null; // demasiado irregular
        int base = ys.get(ys.size() / 2);                         // la mediana: menos relleno y menos corte
        BlockPos o = new BlockPos(x, base, z);
        build(w, o, k, noteIndex);
        return o.add(k.w / 2, 0, k.d / 2);
    }

    // ------------------------------------------------------------------ ayudante de construcción

    private static final class B {
        final ServerWorld w;
        final BlockPos o;

        B(ServerWorld w, BlockPos o) {
            this.w = w;
            this.o = o;
        }

        void set(int dx, int dy, int dz, Block b) {
            w.setBlockState(o.add(dx, dy, dz), b.getDefaultState(), F);
        }

        void set(int dx, int dy, int dz, BlockState s) {
            w.setBlockState(o.add(dx, dy, dz), s, F);
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, Supplier<BlockState> s) {
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++) set(x, y, z, s.get());
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, Block b) {
            BlockState s = b.getDefaultState();
            fill(x0, y0, z0, x1, y1, z1, () -> s);
        }

        /** Limpia el hueco (árboles, hierba alta...), pone el suelo y rellena hacia abajo hasta tocar tierra firme. */
        void ground(int sw, int sd, int clear, Supplier<BlockState> floor) {
            for (int dx = 0; dx < sw; dx++) {
                for (int dz = 0; dz < sd; dz++) {
                    for (int h = 0; h < clear; h++) {
                        BlockPos p = o.add(dx, h, dz);
                        if (!w.getBlockState(p).isAir()) w.setBlockState(p, Blocks.AIR.getDefaultState(), F);
                    }
                    BlockPos f = o.add(dx, -1, dz);
                    w.setBlockState(f, floor.get(), F);
                    for (int k = 1; k <= 8; k++) {
                        BlockPos fp = f.down(k);
                        if (w.getBlockState(fp).isSolidBlock(w, fp)) break;
                        w.setBlockState(fp, Blocks.DIRT.getDefaultState(), F);
                    }
                }
            }
        }

        /** Espejo de 2 de alto. 'facing' es hacia donde mira; el muro va en el lado contrario. Protege la zona. */
        void mirror(int dx, int dz, Direction facing) {
            BlockState m = ModBlocks.ECHO_MIRROR.getDefaultState().with(HorizontalFacingBlock.FACING, facing);
            BlockPos base = o.add(dx, 0, dz);
            w.setBlockState(base, m, F);
            w.setBlockState(base.up(), m, F);
            EchoManager.registerMirror(w, base);
        }

        /** Atril con la página del diario. 'facing' es hacia donde está el lector. */
        void lectern(int dx, int dy, int dz, Direction facing, int noteIndex) {
            BlockPos pos = o.add(dx, dy, dz);
            BlockState s = Blocks.LECTERN.getDefaultState().with(LecternBlock.FACING, facing).with(LecternBlock.HAS_BOOK, true);
            w.setBlockState(pos, s, Block.NOTIFY_ALL);
            if (w.getBlockEntity(pos) instanceof LecternBlockEntity le) le.setBook(makeNote(noteIndex));
        }

        /** Cartel pegado a un muro. 'facing' es hacia donde mira el texto (lejos del muro). */
        void sign(int dx, int dy, int dz, Direction facing, String... lines) {
            BlockPos pos = o.add(dx, dy, dz);
            w.setBlockState(pos, Blocks.DARK_OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, facing), F);
            if (w.getBlockEntity(pos) instanceof SignBlockEntity se) {
                SignText t = new SignText();
                for (int i = 0; i < 4; i++) t = t.withMessage(i, Text.literal(i < lines.length ? lines[i] : ""));
                se.setText(t, true);
            }
        }

        /** Cofre con cosas de un jugador antiguo y una telaraña encima. */
        void chest(int dx, int dy, int dz, Direction facing, int siteIdx) {
            BlockPos pos = o.add(dx, dy, dz);
            w.setBlockState(pos, Blocks.CHEST.getDefaultState().with(HorizontalFacingBlock.FACING, facing), F);
            if (w.getBlockEntity(pos) instanceof ChestBlockEntity ce) fillChest(ce, siteIdx);
            w.setBlockState(pos.up(), Blocks.COBWEB.getDefaultState(), F);
        }

        /** Cofre doble con botín: la mitad izquierda en (dx,dz) y la otra hacia facing.rotateYClockwise(). */
        void doubleChest(int dx, int dy, int dz, Direction facing, int siteIdx) {
            BlockPos a = o.add(dx, dy, dz);
            BlockPos c = a.offset(facing.rotateYClockwise());
            w.setBlockState(a, Blocks.CHEST.getDefaultState().with(HorizontalFacingBlock.FACING, facing)
                    .with(ChestBlock.CHEST_TYPE, ChestType.LEFT), F);
            w.setBlockState(c, Blocks.CHEST.getDefaultState().with(HorizontalFacingBlock.FACING, facing)
                    .with(ChestBlock.CHEST_TYPE, ChestType.RIGHT), F);
            if (w.getBlockEntity(a) instanceof ChestBlockEntity ce) fillChest(ce, siteIdx);
            if (w.getBlockEntity(c) instanceof ChestBlockEntity ce) fillChest(ce, siteIdx);
        }

        void candle(int dx, int dy, int dz, int count) {
            candle(dx, dy, dz, count, false);
        }

        void candle(int dx, int dy, int dz, int count, boolean lit) {
            set(dx, dy, dz, Blocks.CANDLE.getDefaultState().with(CandleBlock.CANDLES, count).with(CandleBlock.LIT, lit));
        }

        /** Escalera. 'facing' es el lado ALTO (hacia donde se sube). */
        void stair(int dx, int dy, int dz, Block block, Direction facing, boolean top) {
            BlockState st = block.getDefaultState().with(HorizontalFacingBlock.FACING, facing)
                    .with(Properties.BLOCK_HALF, top ? BlockHalf.TOP : BlockHalf.BOTTOM);
            w.setBlockState(o.add(dx, dy, dz), st, F);
        }

        /** Camino de grava y tierra desde (dx,dz) en la dirección (sx,sz), por encima del terreno real. */
        void path(int dx, int dz, int sx, int sz, int len) {
            for (int i = 0; i < len; i++) {
                for (int wd = -1; wd <= 1; wd++) {
                    if (wd != 0 && RNG.nextInt(2) == 0) continue;
                    int px = o.getX() + dx + sx * i + (sx == 0 ? wd : 0);
                    int pz = o.getZ() + dz + sz * i + (sz == 0 ? wd : 0);
                    int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, px, pz) - 1;
                    BlockPos pos = new BlockPos(px, y, pz);
                    BlockState bs = w.getBlockState(pos);
                    if (bs.isAir() || !bs.getFluidState().isEmpty() || bs.isIn(BlockTags.LOGS) || bs.isIn(BlockTags.LEAVES)) continue;
                    BlockState np = switch (RNG.nextInt(3)) {
                        case 0 -> Blocks.GRAVEL.getDefaultState();
                        case 1 -> Blocks.COBBLESTONE.getDefaultState();
                        default -> Blocks.COARSE_DIRT.getDefaultState();
                    };
                    w.setBlockState(pos, np, F);
                }
            }
        }
    }

    private static int netheriteLeft = 0;

    private static void build(ServerWorld w, BlockPos o, Kind k, int noteIndex) {
        netheriteLeft = k == Kind.GRAVE ? 1 : 0; // el día 5: una pieza de netherite en un cofre
        B b = new B(w, o);
        switch (k) {
            case CABIN -> cabin(b, noteIndex);
            case RUIN -> ruin(b, noteIndex);
            case CHAPEL -> chapel(b, noteIndex);
            case CAMP -> camp(b, noteIndex);
            case GRAVE -> grave(b, noteIndex);
        }
        // Multijugador: si entró otro jugador en el día 1 o 2, cada estructura nueva lleva 4 o 5 cofres en total
        if (EchoState.get(w.getServer()).multi) extraChests(b, k, noteIndex);
    }

    /** Cofres extra (2, o 3 desde el cuarto lugar) en sitios libres de cada estructura. {x, z, orientación} */
    private static void extraChests(B b, Kind k, int note) {
        Direction[] dirs = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        int[][] spots = switch (k) {
            case CABIN -> new int[][]{{1, 6, 1}, {7, 3, 3}, {1, 1, 2}};
            case RUIN -> new int[][]{{1, 3, 1}, {7, 2, 3}, {1, 5, 1}};
            case CHAPEL -> new int[][]{{1, 6, 1}, {1, 3, 1}, {7, 9, 3}};
            case CAMP -> new int[][]{{9, 2, 3}, {1, 6, 1}, {4, 9, 0}};
            case GRAVE -> new int[][]{{8, 6, 3}, {8, 8, 3}, {1, 3, 1}};
        };
        int n = noteIndex3(note);
        for (int i = 0; i < n; i++) b.chest(spots[i][0], 0, spots[i][1], dirs[spots[i][2]], note);
    }

    private static int noteIndex3(int note) {
        return note >= 3 ? 3 : 2;
    }

    private static boolean rare(int n) {
        return RNG.nextInt(n) == 0;
    }

    private static BlockState brickMix() {
        return switch (RNG.nextInt(4)) {
            case 0 -> Blocks.MOSSY_STONE_BRICKS.getDefaultState();
            case 1 -> Blocks.CRACKED_STONE_BRICKS.getDefaultState();
            case 2 -> Blocks.MOSSY_COBBLESTONE.getDefaultState();
            default -> Blocks.STONE_BRICKS.getDefaultState();
        };
    }

    private static BlockState deepMix() {
        return switch (RNG.nextInt(4)) {
            case 0 -> Blocks.DEEPSLATE_BRICKS.getDefaultState();
            case 1 -> Blocks.CRACKED_DEEPSLATE_BRICKS.getDefaultState();
            case 2 -> Blocks.DEEPSLATE_TILES.getDefaultState();
            default -> Blocks.POLISHED_DEEPSLATE.getDefaultState();
        };
    }

    private static BlockState wool() {
        return (rare(3) ? Blocks.LIGHT_GRAY_WOOL : Blocks.WHITE_WOOL).getDefaultState();
    }

    // ------------------------------------------------------------------ 1. la cabaña de Elías (9x9)

    private static void cabin(B b, int note) {
        b.ground(9, 9, 10, () -> (rare(5) ? Blocks.SPRUCE_PLANKS : Blocks.OAK_PLANKS).getDefaultState());
        for (int dx = 0; dx < 9; dx++) {
            for (int dz = 0; dz < 9; dz++) {
                if (!(dx == 0 || dz == 0 || dx == 8 || dz == 8)) continue;
                boolean corner = (dx == 0 || dx == 8) && (dz == 0 || dz == 8);
                boolean door = dx == 4 && dz == 8;
                boolean backMid = dz == 0 && dx >= 3 && dx <= 5; // detrás del espejo: siempre hay muro
                for (int y = 0; y < 3; y++) {
                    if (door && y < 2) continue;
                    boolean window = y == 1 && (((dx == 0 || dx == 8) && (dz == 3 || dz == 5))
                            || (dz == 8 && (dx == 2 || dx == 6)) || (dz == 0 && (dx == 1 || dx == 7)));
                    if (window) { // las ventanas "ya no están"
                        if (rare(2)) b.set(dx, y, dz, Blocks.COBWEB);
                        continue;
                    }
                    if (!corner && !backMid && y >= 1 && rare(9)) continue;
                    BlockState st;
                    if (corner) st = Blocks.OAK_LOG.getDefaultState();
                    else if (y == 0) st = (rare(3) ? Blocks.MOSSY_COBBLESTONE : Blocks.COBBLESTONE).getDefaultState();
                    else st = (rare(3) ? Blocks.OAK_PLANKS : Blocks.SPRUCE_PLANKS).getDefaultState();
                    b.set(dx, y, dz, st);
                }
            }
        }
        // tejado a dos aguas (con agujeros) y los frontones
        for (int dx = 0; dx < 9; dx++) {
            boolean end = dx == 0 || dx == 8;
            for (int k = 0; k < 4; k++) {
                if (end || !rare(9)) b.stair(dx, 3 + k, k, Blocks.SPRUCE_STAIRS, Direction.SOUTH, false);
                if (end || !rare(9)) b.stair(dx, 3 + k, 8 - k, Blocks.SPRUCE_STAIRS, Direction.NORTH, false);
            }
            if (end || !rare(12)) b.set(dx, 7, 4, Blocks.SPRUCE_SLAB);
        }
        for (int k = 0; k < 4; k++) {
            for (int dz = k + 1; dz <= 7 - k; dz++) {
                if (!rare(10)) b.set(0, 3 + k, dz, Blocks.SPRUCE_PLANKS);
                if (!rare(10)) b.set(8, 3 + k, dz, Blocks.SPRUCE_PLANKS);
            }
        }
        // interior
        b.fill(3, 0, 3, 5, 0, 7, () -> rare(6) ? Blocks.AIR.getDefaultState() : Blocks.RED_CARPET.getDefaultState());
        b.fill(1, 0, 2, 1, 1, 5, Blocks.BOOKSHELF);
        b.set(7, 0, 1, Blocks.CRAFTING_TABLE);
        b.set(7, 0, 2, Blocks.FURNACE);
        b.set(1, 0, 7, Blocks.BARREL);
        b.set(2, 0, 7, Blocks.OAK_FENCE);
        b.set(2, 1, 7, Blocks.OAK_PRESSURE_PLATE);
        b.set(6, 0, 7, Blocks.LANTERN);
        b.mirror(4, 1, Direction.SOUTH);
        b.set(3, 0, 1, Blocks.LANTERN);
        b.candle(5, 0, 1, 2, true);
        b.sign(2, 1, 1, Direction.SOUTH, "DÍA 1", "AQUÍ EMPEZÓ", "TODO", "");
        b.lectern(4, 0, 4, Direction.SOUTH, note);
        b.candle(3, 0, 4, 1, true);
        b.candle(5, 0, 4, 1, false);
        b.set(1, 2, 1, Blocks.COBWEB);
        b.set(7, 2, 1, Blocks.COBWEB);
        b.set(1, 2, 7, Blocks.COBWEB);
        b.set(7, 2, 7, Blocks.COBWEB);
        b.set(6, 0, 3, Blocks.MOSS_CARPET);
        b.chest(7, 0, 5, Direction.WEST, note);
        b.doubleChest(7, 0, 7, Direction.WEST, note);
        b.path(4, 9, 0, 1, 8);
    }

    // ------------------------------------------------------------------ 2. las ruinas de piedra (9x9)

    private static void ruin(B b, int note) {
        b.ground(9, 9, 9, () -> switch (RNG.nextInt(4)) {
            case 0 -> Blocks.MOSSY_COBBLESTONE.getDefaultState();
            case 1 -> Blocks.CRACKED_STONE_BRICKS.getDefaultState();
            case 2 -> Blocks.MOSSY_STONE_BRICKS.getDefaultState();
            default -> Blocks.COBBLESTONE.getDefaultState();
        });
        for (int dx = 0; dx < 9; dx++) {
            for (int dz = 0; dz < 9; dz++) {
                if (!(dx == 0 || dz == 0 || dx == 8 || dz == 8)) continue;
                boolean corner = (dx == 0 || dx == 8) && (dz == 0 || dz == 8);
                boolean back = dz == 0;
                boolean gate = dz == 8 && dx >= 3 && dx <= 5;
                int hgt;
                if (gate) hgt = 0;
                else if (back) hgt = 4 + (rare(3) ? 1 : 0); // el muro del espejo siempre es alto
                else if (corner) hgt = 3 + RNG.nextInt(3);
                else hgt = 1 + RNG.nextInt(3);
                if (!back && !corner && !gate && rare(6)) hgt = 0; // muros rotos
                for (int y = 0; y < hgt; y++) b.set(dx, y, dz, brickMix());
            }
        }
        // el arco de la entrada, roto por arriba
        for (int y = 0; y < 4; y++) {
            b.set(2, y, 8, brickMix());
            b.set(6, y, 8, brickMix());
        }
        b.set(2, 4, 8, Blocks.STONE_BRICK_SLAB);
        b.set(3, 4, 8, Blocks.STONE_BRICK_SLAB);
        b.set(6, 4, 8, Blocks.STONE_BRICK_SLAB);
        b.set(5, 4, 8, Blocks.MOSSY_STONE_BRICK_SLAB);
        // columnas interiores partidas
        int[][] cols = {{2, 2}, {6, 2}, {2, 6}, {6, 6}};
        for (int[] c : cols) {
            int h = 1 + RNG.nextInt(4);
            for (int y = 0; y < h; y++) b.set(c[0], y, c[1], y == 0 ? Blocks.CHISELED_STONE_BRICKS.getDefaultState() : brickMix());
        }
        // escombros y telarañas
        for (int i = 0; i < 16; i++) {
            int dx = 1 + RNG.nextInt(7), dz = 2 + RNG.nextInt(6);
            if (dx >= 3 && dx <= 5 && dz <= 4) continue;             // zona del espejo y el atril
            if ((dx == 7 && dz == 4) || (dx == 2 && dz == 2) || (dx == 6 && dz == 2) || (dx == 2 && dz == 6) || (dx == 6 && dz == 6)) continue;
            BlockState rub = switch (RNG.nextInt(5)) {
                case 0 -> Blocks.STONE_BRICK_SLAB.getDefaultState();
                case 1 -> Blocks.COBBLESTONE.getDefaultState();
                case 2 -> Blocks.MOSS_CARPET.getDefaultState();
                case 3 -> Blocks.COBWEB.getDefaultState();
                default -> Blocks.MOSSY_COBBLESTONE_SLAB.getDefaultState();
            };
            b.set(dx, 0, dz, rub);
        }
        // el altar del espejo
        b.mirror(4, 1, Direction.SOUTH);
        for (int y = 0; y < 2; y++) {
            b.set(3, y, 1, Blocks.CHISELED_STONE_BRICKS);
            b.set(5, y, 1, Blocks.CHISELED_STONE_BRICKS);
        }
        b.set(3, 2, 1, Blocks.SOUL_LANTERN);
        b.set(5, 2, 1, Blocks.SOUL_LANTERN);
        b.fill(3, 0, 2, 5, 0, 2, Blocks.STONE_BRICK_SLAB);
        b.lectern(4, 0, 4, Direction.SOUTH, note);
        b.candle(3, 0, 4, 1, true);
        b.candle(5, 0, 4, 1, false);
        b.sign(2, 1, 1, Direction.SOUTH, "LA PIEDRA", "TAMPOCO", "SE QUEDA", "");
        b.set(1, 0, 7, Blocks.LANTERN);
        b.chest(7, 0, 4, Direction.WEST, note);
        b.doubleChest(7, 0, 7, Direction.WEST, note);
        b.path(4, 9, 0, 1, 8);
    }

    // ------------------------------------------------------------------ 3. la capilla de deepslate (9x11)

    private static void chapel(B b, int note) {
        b.ground(9, 11, 12, () -> (rare(3) ? Blocks.DEEPSLATE_TILES : Blocks.POLISHED_DEEPSLATE).getDefaultState());
        for (int dx = 0; dx < 9; dx++) {
            for (int dz = 0; dz < 11; dz++) {
                if (!(dx == 0 || dz == 0 || dx == 8 || dz == 10)) continue;
                boolean side = dx == 0 || dx == 8;
                boolean pier = side && (dz == 0 || dz == 3 || dz == 6 || dz == 10);
                boolean door = dz == 10 && dx >= 3 && dx <= 5;
                boolean back = dz == 0;
                for (int y = 0; y < 5; y++) {
                    if (door && y < 3) continue;
                    boolean slit = side && (dz == 2 || dz == 5 || dz == 8) && y >= 1 && y <= 3;
                    if (slit) continue;
                    if (!back && !pier && y >= 2 && rare(10)) continue;
                    b.set(dx, y, dz, pier ? Blocks.CHISELED_DEEPSLATE.getDefaultState() : deepMix());
                }
            }
        }
        // tejado a dos aguas, hundido en parte
        for (int dz = 0; dz < 11; dz++) {
            boolean end = dz == 0 || dz == 10;
            boolean collapsed = dz >= 5 && dz <= 8 && rare(2);
            for (int k = 0; k < 4; k++) {
                if (end || (!collapsed && !rare(8))) {
                    b.stair(k, 5 + k, dz, Blocks.DEEPSLATE_TILE_STAIRS, Direction.EAST, false);
                    b.stair(8 - k, 5 + k, dz, Blocks.DEEPSLATE_TILE_STAIRS, Direction.WEST, false);
                }
            }
            if (end || (!collapsed && !rare(10))) b.set(4, 9, dz, Blocks.DEEPSLATE_TILE_SLAB);
        }
        for (int k = 0; k < 4; k++) {
            for (int dx = k + 1; dx <= 7 - k; dx++) {
                b.set(dx, 5 + k, 0, Blocks.DEEPSLATE_BRICKS);
                if (!(k == 1 && dx == 4)) b.set(dx, 5 + k, 10, Blocks.DEEPSLATE_BRICKS); // el rosetón vacío
            }
        }
        // nave: alfombra, bancos, faroles
        b.fill(4, 0, 4, 4, 0, 9, () -> rare(5) ? Blocks.AIR.getDefaultState() : Blocks.RED_CARPET.getDefaultState());
        for (int z = 4; z <= 8; z += 2) {
            for (int x : new int[]{2, 3, 5, 6}) {
                if (!rare(5)) b.stair(x, 0, z, Blocks.DARK_OAK_STAIRS, Direction.SOUTH, false);
            }
        }
        b.set(1, 0, 4, Blocks.SOUL_LANTERN);
        b.set(7, 0, 4, Blocks.SOUL_LANTERN);
        b.set(1, 0, 8, Blocks.SOUL_LANTERN);
        b.set(7, 0, 8, Blocks.SOUL_LANTERN);
        // el altar del espejo
        b.mirror(4, 1, Direction.SOUTH);
        for (int y = 0; y < 3; y++) {
            b.set(3, y, 1, Blocks.CHISELED_DEEPSLATE);
            b.set(5, y, 1, Blocks.CHISELED_DEEPSLATE);
        }
        b.set(3, 3, 1, Blocks.SOUL_LANTERN);
        b.set(5, 3, 1, Blocks.SOUL_LANTERN);
        b.fill(2, 0, 2, 6, 0, 2, Blocks.POLISHED_DEEPSLATE_SLAB);
        b.lectern(4, 0, 3, Direction.SOUTH, note);
        b.candle(1, 0, 1, 3, true);
        b.candle(7, 0, 1, 2, true);
        b.candle(1, 0, 2, 1, false);
        b.sign(2, 1, 1, Direction.SOUTH, "NO LO", "MIRES", "DE FRENTE", "");
        b.set(1, 4, 9, Blocks.COBWEB);
        b.set(7, 4, 9, Blocks.COBWEB);
        b.set(1, 4, 1, Blocks.COBWEB);
        b.chest(7, 0, 2, Direction.WEST, note);
        b.doubleChest(7, 0, 7, Direction.WEST, note);
        b.path(4, 11, 0, 1, 9);
    }

    // ------------------------------------------------------------------ 4. el campamento (11x11)

    private static void camp(B b, int note) {
        b.ground(11, 11, 8, () -> switch (RNG.nextInt(5)) {
            case 0 -> Blocks.COARSE_DIRT.getDefaultState();
            case 1 -> Blocks.DIRT.getDefaultState();
            default -> Blocks.GRASS_BLOCK.getDefaultState();
        });
        // la fogata apagada, el círculo de grava y los tocones donde se sentaban
        for (int dx = 4; dx <= 6; dx++)
            for (int dz = 4; dz <= 6; dz++) b.set(dx, -1, dz, rare(2) ? Blocks.GRAVEL : Blocks.COARSE_DIRT);
        b.set(5, 0, 5, Blocks.CAMPFIRE.getDefaultState().with(Properties.LIT, false));
        b.set(3, 0, 6, Blocks.STRIPPED_OAK_LOG);
        b.set(5, 0, 7, Blocks.STRIPPED_OAK_LOG);
        b.set(6, 0, 3, Blocks.STRIPPED_OAK_LOG);

        // la tienda en A, abierta por el sur
        for (int z = 1; z <= 4; z++) {
            b.set(1, 0, z, wool());
            b.set(5, 0, z, wool());
            b.set(2, 1, z, wool());
            b.set(4, 1, z, wool());
            b.set(3, 2, z, wool());
        }
        for (int x = 2; x <= 4; x++) b.set(x, 0, 1, wool());
        b.set(3, 1, 1, wool());
        b.fill(2, 0, 2, 4, 0, 4, Blocks.BROWN_CARPET);
        b.candle(4, 0, 3, 1, true);
        b.chest(3, 0, 2, Direction.SOUTH, note);

        // el muro del espejo, al este, con sus pilares
        b.fill(10, 0, 2, 10, 2, 8, () -> brickMix());
        for (int y = 0; y < 4; y++) {
            b.set(10, y, 1, brickMix());
            b.set(10, y, 9, brickMix());
        }
        b.set(10, 4, 1, Blocks.SOUL_LANTERN);
        b.set(10, 4, 9, Blocks.SOUL_LANTERN);
        b.mirror(9, 5, Direction.WEST);
        b.set(9, 0, 4, Blocks.MOSSY_STONE_BRICKS);
        b.set(9, 0, 6, Blocks.MOSSY_STONE_BRICKS);
        b.set(9, 1, 4, Blocks.LANTERN);
        b.set(9, 1, 6, Blocks.LANTERN);
        b.sign(9, 1, 3, Direction.WEST, "EL ESPEJO", "TE CALMA", "UN RATO", "");
        b.lectern(8, 0, 5, Direction.WEST, note);

        // provisiones abandonadas y farolas
        b.set(1, 0, 8, Blocks.BARREL);
        b.set(2, 0, 8, Blocks.BARREL);
        b.set(1, 0, 9, Blocks.HAY_BLOCK);
        b.set(7, 0, 8, Blocks.CAULDRON);
        b.set(8, 0, 1, Blocks.COBWEB);
        b.set(0, 0, 5, Blocks.TORCH);
        int[][] posts = {{0, 10}, {6, 10}, {0, 6}, {8, 9}};
        for (int[] ps : posts) {
            b.set(ps[0], 0, ps[1], Blocks.OAK_FENCE);
            b.set(ps[0], 1, ps[1], Blocks.LANTERN);
        }
        b.set(3, 0, 8, Blocks.DEAD_BUSH);
        b.set(7, 0, 6, Blocks.FERN);
        b.doubleChest(8, 0, 8, Direction.NORTH, note);
        b.path(5, 11, 0, 1, 8);
    }

    // ------------------------------------------------------------------ 5. la tumba de Elías (9x11)

    private static void grave(B b, int note) {
        b.ground(9, 11, 9, () -> (rare(3) ? Blocks.COARSE_DIRT : Blocks.GRASS_BLOCK).getDefaultState());
        // la fachada de la cripta, con el espejo
        b.fill(1, 0, 0, 7, 3, 0, () -> brickMix());
        b.fill(1, 4, 0, 7, 4, 0, Blocks.STONE_BRICK_SLAB);
        for (int y = 0; y < 5; y++) {
            b.set(0, y, 0, Blocks.STONE_BRICKS);
            b.set(8, y, 0, Blocks.STONE_BRICKS);
        }
        b.set(0, 5, 0, Blocks.SOUL_LANTERN);
        b.set(8, 5, 0, Blocks.SOUL_LANTERN);
        b.mirror(4, 1, Direction.SOUTH);
        for (int y = 0; y < 2; y++) {
            b.set(3, y, 1, Blocks.MOSSY_STONE_BRICKS);
            b.set(5, y, 1, Blocks.MOSSY_STONE_BRICKS);
        }
        b.candle(3, 2, 1, 3, true);
        b.candle(5, 2, 1, 3, true);
        b.fill(2, 0, 2, 6, 0, 2, Blocks.STONE_BRICK_SLAB);
        b.lectern(4, 0, 3, Direction.SOUTH, note);
        b.chest(1, 0, 2, Direction.SOUTH, note);
        b.fill(4, -1, 4, 4, -1, 6, Blocks.GRAVEL);
        b.doubleChest(7, 0, 3, Direction.SOUTH, note);

        // las tumbas de los demás
        int[][] gs = {{1, 4}, {7, 4}, {2, 6}, {6, 6}, {1, 8}, {7, 8}};
        for (int[] g : gs) {
            if (rare(6)) continue;
            int h = 1 + RNG.nextInt(2);
            Block stone = rare(2) ? Blocks.MOSSY_COBBLESTONE : Blocks.STONE_BRICKS;
            for (int y = 0; y < h; y++) b.set(g[0], y, g[1], stone);
            b.set(g[0], h, g[1], Blocks.STONE_BRICK_SLAB);
            b.set(g[0], 0, g[1] + 1, Blocks.COARSE_DIRT);
            b.set(g[0], 1, g[1] + 1, switch (RNG.nextInt(4)) {
                case 0 -> Blocks.POPPY.getDefaultState();
                case 1 -> Blocks.DEAD_BUSH.getDefaultState();
                case 2 -> Blocks.AZURE_BLUET.getDefaultState();
                default -> Blocks.AIR.getDefaultState();
            });
        }
        // la de Elías, en el centro
        b.set(4, 0, 7, Blocks.STONE_BRICKS);
        b.set(4, 1, 7, Blocks.STONE_BRICKS);
        b.set(4, 2, 7, Blocks.STONE_BRICK_SLAB);
        b.sign(4, 1, 8, Direction.SOUTH, "ELÍAS", "AQUÍ DESCANSA", "LO QUE QUEDÓ", "DE ÉL");
        b.set(4, 0, 8, Blocks.COARSE_DIRT);
        b.set(4, 0, 9, Blocks.COARSE_DIRT);
        b.set(4, 1, 9, Blocks.POPPY);
        b.candle(3, 0, 8, 2, true);
        b.candle(5, 0, 8, 2, true);

        // el árbol seco
        for (int y = 0; y < 5; y++) b.set(7, y, 2, Blocks.OAK_LOG);
        b.set(6, 3, 2, Blocks.OAK_LOG);
        b.set(8, 3, 2, Blocks.OAK_LOG);
        b.set(8, 4, 2, Blocks.OAK_LOG);
        b.set(6, 4, 2, Blocks.COBWEB);
        b.set(7, 5, 2, Blocks.COBWEB);

        // el borde bajo del cementerio y su portón
        for (int dx = 0; dx < 9; dx++) {
            if (dx < 3 || dx > 5) if (!rare(5)) b.set(dx, 0, 10, Blocks.MOSSY_COBBLESTONE);
        }
        for (int dz = 4; dz < 10; dz++) {
            if (RNG.nextInt(100) < 55) b.set(0, 0, dz, Blocks.MOSSY_COBBLESTONE);
            if (RNG.nextInt(100) < 55) b.set(8, 0, dz, Blocks.MOSSY_COBBLESTONE);
        }
        int[][] posts = {{0, 10}, {8, 10}, {2, 10}, {6, 10}};
        for (int[] ps : posts) {
            b.set(ps[0], 0, ps[1], Blocks.OAK_FENCE);
            b.set(ps[0], 1, ps[1], Blocks.SOUL_LANTERN);
        }
        b.path(4, 11, 0, 1, 8);
    }

    // ------------------------------------------------------------------ el cofre del jugador antiguo

    private static final Item[] OLD_TOOLS = {Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL, Items.STONE_SWORD,
            Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SWORD, Items.IRON_SHOVEL};
    private static final Item[] OLD_ARMOR = {Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.CHAINMAIL_BOOTS,
            Items.CHAINMAIL_LEGGINGS, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};

    /** Casi rota: queda muy poca durabilidad. */
    private static ItemStack worn(Item item) {
        ItemStack s = new ItemStack(item);
        int max = s.getMaxDamage();
        if (max > 1) s.setDamage(max - 1 - RNG.nextInt(Math.max(1, max / 8)));
        return s;
    }

    /** Usado pero no roto: conserva entre el 50 % y el 85 % de su durabilidad. */
    private static ItemStack used(Item item) {
        ItemStack s = new ItemStack(item);
        int max = s.getMaxDamage();
        if (max > 1) s.setDamage((int) (max * (0.15 + RNG.nextDouble() * 0.35)));
        return s;
    }

    /** Cada lugar tiene mejor botín que el anterior; el último guarda lo mejor. */
    private static void fillChest(ChestBlockEntity ce, int siteIdx) {
        List<ItemStack> items = new ArrayList<>();
        int idx = Math.max(0, Math.min(4, siteIdx));
        items.add(new ItemStack(ModBlocks.ECHO_MIRROR_ITEM, 1 + RNG.nextInt(2) + (idx >= 2 ? 1 : 0)));
        // lo gastado de siempre
        int reach = Math.min(8, 4 + idx);
        int tools = 2 + RNG.nextInt(2);
        for (int i = 0; i < tools; i++) items.add(worn(OLD_TOOLS[RNG.nextInt(reach)]));
        items.add(worn(OLD_ARMOR[RNG.nextInt(reach)]));
        items.add(new ItemStack(Items.COBWEB, 2 + RNG.nextInt(4)));
        items.add(new ItemStack(Items.TORCH, 8 + RNG.nextInt(9)));
        items.add(new ItemStack(Items.ROTTEN_FLESH, 2 + RNG.nextInt(3)));
        items.add(new ItemStack(Items.BONE, 1 + RNG.nextInt(3)));
        // lo que de verdad sirve, por lugar
        switch (idx) {
            case 0 -> {
                items.add(new ItemStack(Items.BREAD, 3 + RNG.nextInt(3)));
                items.add(new ItemStack(Items.COAL, 4 + RNG.nextInt(5)));
                items.add(new ItemStack(Items.IRON_INGOT, 2 + RNG.nextInt(3)));
            }
            case 1 -> {
                items.add(new ItemStack(Items.COOKED_BEEF, 4 + RNG.nextInt(5)));
                items.add(new ItemStack(Items.IRON_INGOT, 4 + RNG.nextInt(5)));
                items.add(new ItemStack(Items.GOLD_INGOT, 2 + RNG.nextInt(3)));
                items.add(new ItemStack(Items.ARROW, 8 + RNG.nextInt(9)));
            }
            case 2 -> {
                items.add(new ItemStack(Items.GOLDEN_CARROT, 4 + RNG.nextInt(3)));
                items.add(new ItemStack(Items.IRON_INGOT, 6 + RNG.nextInt(5)));
                items.add(new ItemStack(Items.DIAMOND, 1 + RNG.nextInt(2)));
                items.add(new ItemStack(Items.EMERALD, 2 + RNG.nextInt(3)));
                items.add(new ItemStack(Items.LAPIS_LAZULI, 6 + RNG.nextInt(7)));
                items.add(new ItemStack(Items.ENDER_PEARL, 1));
            }
            case 3 -> {
                items.add(new ItemStack(Items.COOKED_BEEF, 8 + RNG.nextInt(5)));
                items.add(new ItemStack(Items.DIAMOND, 2 + RNG.nextInt(2)));
                items.add(new ItemStack(Items.EMERALD, 3 + RNG.nextInt(4)));
                items.add(new ItemStack(Items.GOLDEN_APPLE, 1));
                items.add(new ItemStack(Items.ENDER_PEARL, 1 + RNG.nextInt(2)));
                items.add(new ItemStack(Items.EXPERIENCE_BOTTLE, 4 + RNG.nextInt(5)));
                items.add(used(RNG.nextBoolean() ? Items.DIAMOND_SWORD : Items.DIAMOND_PICKAXE));
            }
            default -> {
                items.add(new ItemStack(Items.COOKED_BEEF, 16));
                items.add(new ItemStack(Items.DIAMOND, 3 + RNG.nextInt(3)));
                items.add(new ItemStack(Items.EMERALD, 6 + RNG.nextInt(5)));
                items.add(new ItemStack(Items.GOLDEN_APPLE, 2));
                items.add(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1));
                items.add(new ItemStack(Items.TOTEM_OF_UNDYING, 1));
                items.add(new ItemStack(Items.ENDER_PEARL, 3));
                if (netheriteLeft > 0) {
                    netheriteLeft--;
                    Item[] nether = {Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS,
                            Items.NETHERITE_BOOTS, Items.NETHERITE_SWORD, Items.NETHERITE_PICKAXE};
                    items.add(new ItemStack(nether[RNG.nextInt(nether.length)]));
                }
                items.add(new ItemStack(Items.EXPERIENCE_BOTTLE, 8 + RNG.nextInt(5)));
                items.add(used(Items.DIAMOND_SWORD));
                items.add(used(Items.DIAMOND_CHESTPLATE));
                items.add(used(RNG.nextBoolean() ? Items.DIAMOND_HELMET : Items.DIAMOND_BOOTS));
            }
        }
        if (items.size() > 27) items = new ArrayList<>(items.subList(0, 27));
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 27; i++) slots.add(i);
        Collections.shuffle(slots, RNG);
        for (int i = 0; i < items.size(); i++) ce.setStack(slots.get(i), items.get(i));
    }

    // ------------------------------------------------------------------ el diario

    public static ItemStack makeNote(int index) {
        int i = Math.floorMod(index, NOTES.length);
        List<RawFilteredPair<Text>> list = new ArrayList<>();
        for (String s : NOTES[i]) list.add(RawFilteredPair.of(Text.literal(s)));
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponentTypes.WRITTEN_BOOK_CONTENT,
                new WrittenBookContentComponent(RawFilteredPair.of(NOTE_PREFIX + " (" + (i + 1) + ")"), "Elías", 0, list, true));
        return book;
    }

    /** ¿Lleva el jugador una página del diario de Elías? */
    public static boolean hasNote(ServerPlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (!s.isOf(Items.WRITTEN_BOOK)) continue;
            WrittenBookContentComponent c = s.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
            if (c != null && c.title().raw().startsWith(NOTE_PREFIX)) return true;
        }
        return false;
    }
}
