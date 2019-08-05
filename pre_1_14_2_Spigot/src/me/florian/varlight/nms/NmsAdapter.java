package me.florian.varlight.nms;

import com.google.common.collect.BiMap;
import com.mojang.datafixers.util.Either;
import me.florian.varlight.VarLightPlugin;
import me.florian.varlight.persistence.LightSourcePersistor;
import me.florian.varlight.persistence.PersistentLightSource;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import net.minecraft.server.v1_14_R1.*;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.type.Piston;
import org.bukkit.craftbukkit.v1_14_R1.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.metadata.FixedMetadataValue;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.IntSupplier;

@ForMinecraft(version = "Spigot 1.14 - 1.14.1")
public class NmsAdapter implements INmsAdapter, Listener {

    private static final String TAG_VARLIGHT_INJECTED = "varlight:injected";

    private static final BlockFace[] CHECK_FACES = new BlockFace[]{
            BlockFace.UP,
            BlockFace.DOWN,
            BlockFace.WEST,
            BlockFace.EAST,
            BlockFace.NORTH,
            BlockFace.SOUTH
    };

    private final Field fieldLightBlocking, fieldLightEngineChunkMap, fieldLightEngineLayerILightAccess;

    private final VarLightPlugin plugin;

    public NmsAdapter(VarLightPlugin plugin) {
        this.plugin = plugin;

        if (plugin.isPaper()) {
            throw new VarLightInitializationException("Paper only supported in Minecraft versions 1.8.8 - 1.13.2 & 1.14.4!");
        }

        try {
            fieldLightBlocking = ReflectionHelper.Safe.getField(net.minecraft.server.v1_14_R1.Block.class, "v");
            fieldLightEngineChunkMap = ReflectionHelper.Safe.getField(PlayerChunkMap.class, "lightEngine");
            fieldLightEngineLayerILightAccess = ReflectionHelper.Safe.getField(LightEngineLayer.class, "a");
        } catch (NoSuchFieldException e) {
            throw new VarLightInitializationException(e);
        }
    }

    @Override
    public void onLoad() {
        injectCustomChunkStatus();
    }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);

        try {
            for (World world : Bukkit.getWorlds()) {
                injectCustomIBlockAccess(world);
            }
        } catch (IllegalAccessException e) {
            throw new VarLightInitializationException(e);
        }
    }

    @Override
    public boolean isBlockTransparent(Block block) {
        try {
            return !(boolean) ReflectionHelper.Safe.get(fieldLightBlocking, getNmsWorld(block.getWorld()).getType(toBlockPosition(block.getLocation())).getBlock());
        } catch (IllegalAccessException e) {
            throw new LightUpdateFailedException(e);
        }
    }

    @Override
    public void updateBlockLight(final Location at, final int lightLevel) {
        final WorldServer worldServer = getNmsWorld(at.getWorld());
        final BlockPosition position = toBlockPosition(at);
        final LightEngineThreaded lightEngineThreaded = worldServer.getChunkProvider().getLightEngine();
        final LightEngineBlock lightEngineBlock = (LightEngineBlock) lightEngineThreaded.a(EnumSkyBlock.BLOCK);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            net.minecraft.server.v1_14_R1.Chunk chunk = worldServer.getChunkProvider().getChunkAt(position.getX() >> 4, position.getZ() >> 4, false);

            if (chunk == null || !chunk.loaded) {
                return;
            }

            updateBlockAndChunk(worldServer, at, chunk);

            if (lightLevel > 0) {
                lightEngineBlock.a(position, lightLevel);
            }

            if (!lightEngineThreaded.a()) {
                return;
            }

            updateBlockAndChunk(worldServer, at, chunk);
        });
    }

    @Override
    public int getEmittingLightLevel(Block block) {
        return 0;
    }

    @Override
    @Deprecated
    public void sendChunkUpdates(Chunk chunk, int mask) {
        throw new UnsupportedOperationException("Not used in this version");
    }

    @Override
    public boolean isValidBlock(Block block) {
        if (!block.getType().isBlock()) {
            return false;
        }

        if (getEmittingLightLevel(block) > 0) {
            return false;
        }

        BlockData blockData = block.getType().createBlockData();

        if (blockData instanceof Powerable || blockData instanceof AnaloguePowerable || blockData instanceof Openable || blockData instanceof Piston) {
            return false;
        }

        if (block.getType() == Material.SLIME_BLOCK) {
            return false;
        }

        if (block.getType() == Material.BLUE_ICE) {
            return true; // Packed ice is solid and occluding but blue ice isn't?
        }

        return block.getType().isSolid() && block.getType().isOccluding();
    }

    @Override
    public void sendActionBarMessage(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
    }

    @Override
    public void setCooldown(Player player, Material material, int ticks) {
        player.setCooldown(material, ticks);
    }

    @Override
    public boolean hasCooldown(Player player, Material material) {
        return player.hasCooldown(material);
    }

    @Override
    public String getNumericMinecraftVersion() {
        return MinecraftServer.getServer().getVersion();
    }

    public int getCustomLuminance(WorldServer worldServer, BlockPosition pos, IntSupplier def) {
        LightSourcePersistor persistor = LightSourcePersistor.getPersistor(plugin, worldServer.getWorld()).orElse(null);

        if (persistor == null) {
            return def.getAsInt();
        }

        PersistentLightSource lightSource = persistor.getPersistentLightSource(pos.getX(), pos.getY(), pos.getZ()).orElse(null);

        if (lightSource == null) {
            return def.getAsInt();
        }

        return lightSource.getEmittingLight();
    }

    // region Util Methods

    private WorldServer getNmsWorld(World world) {
        return ((CraftWorld) world).getHandle();
    }

    private BlockPosition toBlockPosition(Location location) {
        return new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private void updateBlockAndChunk(WorldServer worldServer, Location location, net.minecraft.server.v1_14_R1.Chunk chunk) {
        final LightEngineThreaded lightEngine = worldServer.getChunkProvider().getLightEngine();

        lightEngine.a(toBlockPosition(location));

        CompletableFuture<IChunkAccess> future = lightEngine.a(new WrappedIChunkAccess(this, worldServer, chunk), true);

        Runnable updateChunkTask = () -> collectChunksToUpdate(location)
                .forEach(c -> queueChunkLightUpdate(c, location.getBlockY() / 16));

        if (worldServer.getMinecraftServer().isMainThread()) {
            worldServer.getMinecraftServer().awaitTasks(future::isDone);
            updateChunkTask.run();
        } else {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                return;
            }

            Bukkit.getScheduler().runTask(plugin, updateChunkTask);
        }
    }

    public void queueChunkLightUpdate(Chunk chunk, int sectionY) {
        WorldServer nmsWorld = getNmsWorld(chunk.getWorld());
        PlayerChunkMap playerChunkMap = nmsWorld.getChunkProvider().playerChunkMap;
        PlayerChunk playerChunk = playerChunkMap.visibleChunks.get(ChunkCoordIntPair.pair(chunk.getX(), chunk.getZ()));
        ChunkCoordIntPair chunkCoordIntPair = new ChunkCoordIntPair(chunk.getX(), chunk.getZ());

        int mask = getChunkBitMask(sectionY);

        PacketPlayOutLightUpdate packetPlayOutLightUpdate = new PacketPlayOutLightUpdate(chunkCoordIntPair, nmsWorld.getChunkProvider().getLightEngine(), 0, mask << 1); // Mask has to be shifted, because LSB is section Y -1

        playerChunk.players.a(chunkCoordIntPair, false).forEach(e -> e.playerConnection.sendPacket(packetPlayOutLightUpdate));
    }

    // endregion

    // region Injection Methods

    // region Chunk Status
    private void injectCustomChunkStatus() {
        try {
            Class chunkStatusA = Class.forName("net.minecraft.server.v1_14_R1.ChunkStatus$a");

            Field light = ReflectionHelper.Safe.getField(ChunkStatus.class, "LIGHT");
            Field biMap = ReflectionHelper.Safe.getField(RegistryMaterials.class, "c");
            Field fieldO = ReflectionHelper.Safe.getField(ChunkStatus.class, "o");
            Method register = ReflectionHelper.Safe.getMethod(ChunkStatus.class, "a", String.class, ChunkStatus.class, int.class, EnumSet.class, ChunkStatus.Type.class, chunkStatusA);

            final IRegistry chunkStatus = IRegistry.CHUNK_STATUS;

            Object aImplementation = Proxy.newProxyInstance(chunkStatusA.getClassLoader(), new Class[]{chunkStatusA},
                    (Object proxy, Method method, Object[] args) -> {
                        if (method.getName().equals("doWork")) {
                            WorldServer worldServer = (WorldServer) args[1];
                            ChunkStatus status = (ChunkStatus) args[0];
                            LightEngineThreaded lightEngineThreaded = (LightEngineThreaded) args[4];
                            IChunkAccess iChunkAccess = (IChunkAccess) args[7];

                            return doWork(status, worldServer, iChunkAccess, lightEngineThreaded);
                        }

                        return null;
                    }
            );

            ((BiMap) ReflectionHelper.get(biMap, chunkStatus)).remove(new MinecraftKey("light"));
            ChunkStatus customLight = (ChunkStatus) ReflectionHelper.Safe.invokeStatic(register, "light", ChunkStatus.FEATURES, 1, ReflectionHelper.Safe.getStatic(fieldO), ChunkStatus.Type.PROTOCHUNK, aImplementation);

            ReflectionHelper.setStatic(light, customLight);
            plugin.getLogger().info("Injected Custom Light ChunkStatus");
        } catch (NoSuchFieldException | ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new VarLightInitializationException(e);
        }
    }

    private CompletableFuture<Void> doWork(ChunkStatus status, WorldServer worldServer, IChunkAccess iChunkAccess, LightEngineThreaded lightEngineThreaded) {
        boolean useWrapped = LightSourcePersistor.hasPersistor(plugin, worldServer.getWorld());

        boolean flag = iChunkAccess.getChunkStatus().b(status) && iChunkAccess.r();
        IChunkAccess wrapped = useWrapped ? new WrappedIChunkAccess(this, worldServer, iChunkAccess) : iChunkAccess;

        if (!wrapped.getChunkStatus().b(status)) {
            ((ProtoChunk) iChunkAccess).a(status);
        }

        return lightEngineThreaded.a(wrapped, flag).thenAccept(Either::left);
    }

    // endregion

    // region Block Access

    private void injectCustomIBlockAccess(World world) throws IllegalAccessException {
        if (!LightSourcePersistor.hasPersistor(plugin, world)) {
            return;
        }

        if (world.hasMetadata(TAG_VARLIGHT_INJECTED) && world.getMetadata(TAG_VARLIGHT_INJECTED).get(0).asBoolean()) {
            return;
        }

        WorldServer worldServer = getNmsWorld(world);

        ILightAccess customLightAccess = new ILightAccess() {
            private final IBlockAccess world = new WrappedIBlockAccess(NmsAdapter.this, worldServer, worldServer);

            @Nullable
            @Override
            public IBlockAccess b(int chunkX, int chunkZ) {
                IBlockAccess toWrap = worldServer.getChunkProvider().b(chunkX, chunkZ);

                if (toWrap == null) {
                    return null;
                }

                return new WrappedIBlockAccess(NmsAdapter.this, worldServer, toWrap);
            }

            @Override
            public IBlockAccess getWorld() {
                return world;
            }
        };

        // TODO inject
        injectCustomLightAccess(ReflectionHelper.Safe.get(fieldLightEngineChunkMap, worldServer.getChunkProvider().playerChunkMap), customLightAccess, EnumSkyBlock.BLOCK);
        injectCustomLightAccess(worldServer.getChunkProvider().getLightEngine(), customLightAccess, EnumSkyBlock.BLOCK);

        world.setMetadata(TAG_VARLIGHT_INJECTED, new FixedMetadataValue(plugin, true));

        plugin.getLogger().info(String.format("Injected custom IBlockAccess into world \"%s\"", world.getName()));
    }

    private void injectCustomLightAccess(LightEngineThreaded lightEngine, ILightAccess toInject, EnumSkyBlock... engines) throws IllegalAccessException {
        for (EnumSkyBlock enumSkyBlock : engines) {
            LightEngineLayerEventListener engineLayer = lightEngine.a(enumSkyBlock);

            if (engineLayer == LightEngineLayer.Void.INSTANCE) {
                continue;
            }

            ReflectionHelper.Safe.set(engineLayer, fieldLightEngineLayerILightAccess, toInject);
        }
    }

    // endregion

    // endregion

    // region Events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent e) {
        try {
            injectCustomIBlockAccess(e.getWorld());
        } catch (IllegalAccessException ex) {
            throw new VarLightInitializationException(ex);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        handleBlockUpdate(e);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        handleBlockUpdate(e);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluid(BlockFromToEvent e) {
        handleBlockUpdate(e);
    }

    private void handleBlockUpdate(BlockEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Block theBlock = e.getBlock();
            WorldServer worldServer = getNmsWorld(theBlock.getWorld());

            for (BlockFace blockFace : CHECK_FACES) {
                BlockPosition relative = toBlockPosition(theBlock.getLocation().add(blockFace.getDirection()));

                if (getCustomLuminance(worldServer, relative, () -> worldServer.getType(relative).h()) > 0 && worldServer.getType(relative).h() == 0) {
                    int sectionY = theBlock.getY() / 16;
                    collectChunksToUpdate(theBlock.getLocation()).forEach(c -> queueChunkLightUpdate(c, sectionY));
                    return;
                }
            }
        }, 1L);
    }

    // endregion


}