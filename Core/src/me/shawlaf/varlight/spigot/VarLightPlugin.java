package me.shawlaf.varlight.spigot;

import me.shawlaf.varlight.spigot.command.VarLightCommand;
import me.shawlaf.varlight.spigot.nms.INmsAdapter;
import me.shawlaf.varlight.spigot.nms.NmsAdapter;
import me.shawlaf.varlight.spigot.nms.VarLightInitializationException;
import me.shawlaf.varlight.spigot.persistence.WorldLightSourceManager;
import me.shawlaf.varlight.spigot.persistence.migrate.LightDatabaseMigratorSpigot;
import me.shawlaf.varlight.spigot.persistence.migrate.data.JsonToNLSMigration;
import me.shawlaf.varlight.spigot.persistence.migrate.data.VLDBToNLSMigration;
import me.shawlaf.varlight.spigot.persistence.migrate.structure.MoveVarlightRootFolder;
import me.shawlaf.varlight.util.IntPosition;
import me.shawlaf.varlight.util.NumericMajorMinorVersion;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static me.shawlaf.command.result.CommandResult.failure;
import static me.shawlaf.command.result.CommandResult.info;
import static me.shawlaf.varlight.spigot.util.IntPositionExtension.toIntPosition;
import static me.shawlaf.varlight.spigot.util.LightSourceUtil.placeNewLightSource;

public class VarLightPlugin extends JavaPlugin implements Listener {

    private Tag<Material> allowedBlocks, experimentalBlocks;

    public static final long TICK_RATE = 20L;

    private final Map<UUID, Integer> stepSizes = new HashMap<>();
    private final Map<UUID, WorldLightSourceManager> managers = new HashMap<>();

    private final INmsAdapter nmsAdapter;

    private VarLightCommand command;
    private VarLightConfiguration configuration;
    private AutosaveManager autosaveManager;
    private DebugManager debugManager;
    private LightDatabaseMigratorSpigot databaseMigrator;
    private LightUpdateScheduler lightUpdateScheduler;

    private Material lightUpdateItem;
    private GameMode stepsizeGamemode;

    private boolean shouldDeflate;
    private boolean doLoad = true;

    {
        try {
            this.nmsAdapter = new NmsAdapter(this);
        } catch (Throwable e) { // Catch anything that goes wrong while initializing
            startupError(String.format("Failed to initialize VarLight for Minecraft Version \"%s\": %s", Bukkit.getVersion(), e.getMessage()));
            throw e;
        }
    }

    @Override
    public void onLoad() {
        if (!doLoad) {
            return;
        }

        getLogger().info(String.format("Loading VarLight for Minecraft version \"%s\"", nmsAdapter.getForMinecraftVersion()));

        File defaultWorldFolder = new File(Bukkit.getWorldContainer(), nmsAdapter.getDefaultLevelName());
        File dataPackRepository = new File(defaultWorldFolder, "datapacks");

        dataPackRepository.mkdirs();

        File varLightDataPack = new File(dataPackRepository, "VarLight.zip");

        if (varLightDataPack.exists()) {
            if (!varLightDataPack.delete()) {
                startupError("Failed to delete existing VarLight Datapack!");
                return;
            }
        }

        exportResource("/VarLight.zip", varLightDataPack);

        configuration = new VarLightConfiguration(this);
        debugManager = new DebugManager(this);
        databaseMigrator = new LightDatabaseMigratorSpigot(this);

        databaseMigrator.addDataMigrations(
                new JsonToNLSMigration(this),
                new VLDBToNLSMigration(this)
        );

        databaseMigrator.addStructureMigrations(
                new MoveVarlightRootFolder(this)
        );

        lightUpdateScheduler = new LightUpdateScheduler();

        this.shouldDeflate = getConfig().getBoolean(VarLightConfiguration.CONFIG_KEY_NLS_DEFLATED, true);

        allowedBlocks = Bukkit.getTag(Tag.REGISTRY_BLOCKS, new NamespacedKey(this, "allowed_blocks"), Material.class);
        experimentalBlocks = Bukkit.getTag(Tag.REGISTRY_BLOCKS, new NamespacedKey(this, "experimental_blocks"), Material.class);

        try {
            nmsAdapter.onLoad();
        } catch (VarLightInitializationException e) {
            doLoad = false;
            Bukkit.getPluginManager().disablePlugin(this);

            throw e;
        }
    }

    @Override
    public void onEnable() {
        if (!doLoad) {
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        try {
            nmsAdapter.onEnable();
        } catch (VarLightInitializationException e) {
            doLoad = false;
            Bukkit.getPluginManager().disablePlugin(this);

            throw e;
        }

        lightUpdateScheduler.start(this);

        configuration.getVarLightEnabledWorlds().forEach(this::enableInWorld);

        loadLightUpdateItem();
        loadStepsizeGamemode();

        Bukkit.getPluginManager().registerEvents(this.autosaveManager = new AutosaveManager(this), this);
        Bukkit.getPluginManager().registerEvents(this, this);

        command = new VarLightCommand(this);

        NumericMajorMinorVersion current = NumericMajorMinorVersion.tryParse(getDescription().getVersion());

        if (current != null && configuration.isCheckUpdateEnabled()) { // Development versions are not numeric
            Bukkit.getScheduler().runTaskAsynchronously(this, new UpdateCheck(getLogger(), current));
        }
    }

    @Override
    public void onDisable() {
        if (!doLoad) {
            return;
        }

        nmsAdapter.onDisable();

        // If PersistOnSave is enabled, PersistOnWorldSaveHandler.onWorldSave will automatically save the Light Sources
        if (configuration.getAutosaveInterval() >= 0) {
            for (WorldLightSourceManager l : getAllManagers()) {
                l.save(Bukkit.getConsoleSender(), configuration.isLogDebug());
            }
        }

        saveConfig();
    }

    public void enableInWorld(World world) {
        getLogger().info(String.format("Enabling in World [%s]", world.getName()));

        managers.put(
                world.getUID(),
                new WorldLightSourceManager(this, world)
        );

        nmsAdapter.enableVarLightInWorld(world);
    }

    public boolean hasManager(@NotNull World world) {
        return managers.containsKey(Objects.requireNonNull(world).getUID());
    }

    @NotNull
    public List<WorldLightSourceManager> getAllManagers() {
        return Collections.unmodifiableList(new ArrayList<>(managers.values()));
    }

    @Nullable
    public WorldLightSourceManager getManager(@NotNull World world) {
        return managers.get(Objects.requireNonNull(world).getUID());
    }

    public INmsAdapter getNmsAdapter() {
        return nmsAdapter;
    }

    public VarLightCommand getCommand() {
        return command;
    }

    public VarLightConfiguration getConfiguration() {
        return configuration;
    }

    public DebugManager getDebugManager() {
        return debugManager;
    }

    public LightDatabaseMigratorSpigot getDatabaseMigrator() {
        return databaseMigrator;
    }

    public Tag<Material> getAllowedBlocks() {
        return allowedBlocks;
    }

    public Tag<Material> getExperimentalBlocks() {
        return experimentalBlocks;
    }

    public LightUpdateScheduler getLightUpdateScheduler() {
        return lightUpdateScheduler;
    }

    public void reload() {
        loadLightUpdateItem();
        loadStepsizeGamemode();
    }

    public AutosaveManager getAutosaveManager() {
        return autosaveManager;
    }

    public Material getLightUpdateItem() {
        return lightUpdateItem;
    }

    public void setUpdateItem(Material item) {
        getConfig().set(VarLightConfiguration.CONFIG_KEY_VARLIGHT_ITEM, item.getKey().toString());
        saveConfig();

        loadLightUpdateItem();
    }

    public boolean hasValidStepsizeGamemode(Player player) {
        switch (stepsizeGamemode) {
            case CREATIVE: {
                return player.getGameMode() == GameMode.CREATIVE;
            }

            case SURVIVAL: {
                return player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SURVIVAL;
            }

            case ADVENTURE: {
                return player.getGameMode() != GameMode.SPECTATOR;
            }

            default:
            case SPECTATOR: {
                throw new IllegalStateException();
            }
        }
    }

    public void setStepSize(Player player, int stepSize) {
        if (stepSize < 1 || stepSize > 15) {
            throw new IllegalArgumentException("The Step size must be 1 <= n <= 15");
        }

        this.stepSizes.put(player.getUniqueId(), stepSize);
    }

    public boolean shouldDeflate() {
        return shouldDeflate;
    }

    // region Events

    @EventHandler
    public void playerModifyLightSource(PlayerInteractEvent e) {
        WorldLightSourceManager manager = getManager(e.getPlayer().getWorld());

        if (manager == null && configuration.getVarLightEnabledWorldNames().contains(e.getPlayer().getWorld().getName())) {
            enableInWorld(e.getPlayer().getWorld());

            manager = getManager(e.getPlayer().getWorld());
        }

        if (manager == null) {
            return;
        }

        if (e.getItem() != null && nmsAdapter.isVarLightDebugStick(e.getItem())) {
            e.setCancelled(true);

            if (!e.getPlayer().hasPermission("varlight.admin.debug")) {
                failure(command, e.getPlayer(), "You do not have permission to use the debug stick!");
                return;
            }

            if (e.getAction() != Action.RIGHT_CLICK_BLOCK) {
                return;
            }

            Block clickedBlock = e.getClickedBlock();

            int customLuminance = manager.getCustomLuminance(toIntPosition(clickedBlock), 0);

            if (customLuminance == 0) {
                info(command, e.getPlayer(),
                        String.format("No custom light source present at Position [%d, %d, %d]",
                                clickedBlock.getX(), clickedBlock.getY(), clickedBlock.getZ()),
                        ChatColor.RED
                );
            } else {
                info(command, e.getPlayer(), String.format("Custom Light Source with Light Level %d Present at Position %s", customLuminance, toIntPosition(clickedBlock).toShortString()), ChatColor.GREEN);
            }

            return;
        }

        if (e.useInteractedBlock() == Event.Result.DENY || (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_BLOCK)) {
            return;
        }

        String requiredPermission = configuration.getRequiredPermissionNode();

        if (!requiredPermission.isEmpty() && !e.getPlayer().hasPermission(requiredPermission)) {
            return;
        }

        Block clickedBlock = e.getClickedBlock();
        Player player = e.getPlayer();
        ItemStack heldItem = e.getItem();

        if (heldItem == null || heldItem.getType() != lightUpdateItem) {
            return;
        }

        int mod = 0;

        switch (e.getAction()) {
            case RIGHT_CLICK_BLOCK:
                mod = 1;
                break;
            case LEFT_CLICK_BLOCK:
                mod = -1;
                break;
        }

        if (hasValidStepsizeGamemode(player)) {
            mod *= stepSizes.getOrDefault(player.getUniqueId(), 1);
        }

        final boolean creative = player.getGameMode() == GameMode.CREATIVE;

        if (!creative) {
            if (heldItem.getAmount() < Math.abs(mod)) {
                return;
            }
        }

        LightUpdateResult result = placeNewLightSource(this, clickedBlock.getLocation(),
                manager.getCustomLuminance(toIntPosition(clickedBlock), 0) + mod);

        if (result.successful()) {
            e.setCancelled(creative && e.getAction() == Action.LEFT_CLICK_BLOCK);

            if (!creative && e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                heldItem.setAmount(heldItem.getAmount() - Math.abs(mod));
            }
        }

        int finalMod = mod;

        debugManager.logDebugAction(player,
                () -> "Edit Lightsource @ " + toShortBlockString(clickedBlock.getLocation()) + " " +
                        (finalMod < 0 ? "LC" : "RC") + " " + result.getFromLight() + " -> " + result.getToLight() + " ==> " + result.getDebugMessage().toString(),
                true
        );

        result.displayMessage(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void playerBreakLightSource(BlockBreakEvent e) {
        if (!configuration.hasReclaim()) {
            return;
        }

        Block theBlock = e.getBlock();
        World world = theBlock.getWorld();

        WorldLightSourceManager manager = getManager(world);

        if (manager == null) {
            return;
        }

        int emittingLight = manager.getCustomLuminance(toIntPosition(theBlock), -1);

        if (emittingLight <= 0) {
            return;
        }

        if (e.getPlayer().getGameMode() == GameMode.CREATIVE) {
            return;
        }

        ItemStack heldItem = e.getPlayer().getInventory().getItemInMainHand(); // You cannot break blocks with items in off hand

        int fortuneLvl = heldItem.getEnchantmentLevel(Enchantment.LOOT_BONUS_BLOCKS);

        Location dropLocation = theBlock.getLocation();

        if (heldItem.getEnchantmentLevel(Enchantment.SILK_TOUCH) != 0) {
            Collection<ItemStack> drops = theBlock.getDrops(heldItem);

            if (drops.size() != 1 || drops.stream().findFirst().get().getAmount() != 1) {
                return;
            }

            ItemStack dropStack = drops.stream().findFirst().get();
            dropStack = nmsAdapter.makeGlowingStack(dropStack, emittingLight);

            e.setDropItems(false);
            world.dropItemNaturally(dropLocation, dropStack);
        } else {
            if (theBlock.getDrops(heldItem).size() == 0) {
                return;
            }

            ItemStack lui = new ItemStack(lightUpdateItem, 1);

            if (fortuneLvl == 0) {
                world.dropItemNaturally(dropLocation, lui);
            } else {
                // f(x) = 1 - (1 - -0.5) * e^(-0.6 * x)
                double chance = 1d - (1.5) * Math.exp(-0.6 * fortuneLvl);

                for (int i = 0; i < emittingLight; i++) {
                    if (Math.random() <= chance) {
                        world.dropItemNaturally(dropLocation, lui);
                    }
                }
            }
        }
    }

    @SuppressWarnings("ConstantConditions")
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void playerPlaceLightSource(BlockPlaceEvent e) {
        if (!configuration.hasReclaim()) {
            return;
        }

        if (e.getItemInHand() == null) {
            return;
        }

        final ItemStack handCopy = e.getItemInHand().clone();
        final int emittingLight = nmsAdapter.getGlowingValue(handCopy);
        final Material before = e.getBlock().getType(); // We cannot always assume it's air, it could be water

        WorldLightSourceManager manager = getManager(e.getBlock().getWorld());

        if (emittingLight != -1) {
            if (manager == null && e.canBuild()) {
                info(command, e.getPlayer(), "VarLight is not active in your current world!");
                e.setCancelled(true);
                return;
            }

            if (!e.canBuild()) {
                return;
            }

            Bukkit.getScheduler().runTaskLater(this,
                    () -> {
                        LightUpdateResult lightUpdateResult = placeNewLightSource(this, e.getBlock().getLocation(), emittingLight);

                        if (!lightUpdateResult.successful()) {
                            e.getBlock().setType(before);

                            handCopy.setAmount(1);
                            e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(), handCopy);
                        }
                    }, 1L);
        }
    }

    @EventHandler
    public void lightSourceReceiveUpdate(BlockPhysicsEvent e) {
        WorldLightSourceManager manager = getManager(e.getBlock().getWorld());

        if (manager == null) {
            return;
        }

        IntPosition blockPos = toIntPosition(e.getBlock());
        int lum = manager.getCustomLuminance(blockPos, 0);

        if (lum > 0) {
            if (e.getBlock() == e.getSourceBlock()) {
                // The Light Source Block was changed

                // See World.notifyAndUpdatePhysics(BlockPosition, Chunk, IBlockData, IBlockData, IBlockData, int)
                if (nmsAdapter.isIllegalBlock(e.getChangedType())) {
                    manager.setCustomLuminance(blockPos, 0);
                } else {
                    lightUpdateScheduler.enqueueChunks(true, false, e.getBlock().getWorld(), blockPos.toChunkCoords()); // Probably not possible, but /shrug
//                    nmsAdapter.updateChunk(e.getBlock().getWorld(), blockPos.toChunkCoords());
                }
            } else {
                // The Light source Block received an update from another Block
                lightUpdateScheduler.enqueueChunks(true, false, e.getBlock().getWorld(), blockPos.toChunkCoords());
            }
        }
    }

    @EventHandler
    public void playerQuit(PlayerQuitEvent e) {
        stepSizes.remove(e.getPlayer().getUniqueId());
    }

    // endregion

    private void startupError(String message) {
        getLogger().severe("------------------------------------------------------");
        getLogger().severe(message);
        getLogger().severe("------------------------------------------------------");

        doLoad = false;
    }

    private void loadLightUpdateItem() {
        this.lightUpdateItem = configuration.getLightUpdateItem();
        getLogger().info(String.format("Using \"%s\" as the Light update item.", lightUpdateItem.getKey().toString()));
    }

    private void loadStepsizeGamemode() {
        this.stepsizeGamemode = configuration.getStepsizeGamemode();
        getLogger().info(String.format("Using, \"%s\" as the Stepsize Gamemode", stepsizeGamemode.name()));
    }

    private String toShortBlockString(Location location) {
        return String.format("[%d, %d, %d]", location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private void exportResource(String path, File toFile) {
        byte[] buffer = new byte[8 * 1024];
        int read;

        try (InputStream in = getClass().getResourceAsStream(path)) {
            try (FileOutputStream fos = new FileOutputStream(toFile)) {
                while ((read = in.read(buffer, 0, buffer.length)) > 0) {
                    fos.write(buffer, 0, read);
                }
            }
        } catch (IOException e) {
            throw new VarLightInitializationException(e.getMessage(), e);
        }
    }
}
