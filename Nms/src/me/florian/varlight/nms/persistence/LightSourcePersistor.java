package me.florian.varlight.nms.persistence;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import me.florian.varlight.IntPosition;
import me.florian.varlight.RegionCoordinates;
import me.florian.varlight.VarLightPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.metadata.FixedMetadataValue;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Stream;

public class LightSourcePersistor {

    public static final String TAG_WORLD_LIGHT_SOURCE_PERSISTOR = "varlight:persistor";

    public static Stream<LightSourcePersistor> getAllPersistors(VarLightPlugin plugin) {
        return Bukkit.getWorlds().stream().map(w -> getPersistor(plugin, w));
    }

    public static LightSourcePersistor getPersistor(VarLightPlugin plugin, World world) {
        return (LightSourcePersistor) world.getMetadata(TAG_WORLD_LIGHT_SOURCE_PERSISTOR).stream().filter(m -> m.getOwningPlugin().equals(plugin)).findFirst().orElseGet(() -> {

            FixedMetadataValue fixedMetadataValue = new FixedMetadataValue(plugin, new LightSourcePersistor(plugin, world));
            world.setMetadata(TAG_WORLD_LIGHT_SOURCE_PERSISTOR, fixedMetadataValue);

            return fixedMetadataValue;

        }).value();
    }


    private final Map<RegionCoordinates, Map<IntPosition, PersistentLightSource>> worldMap;
    private final VarLightPlugin plugin;
    private final World world;

    private LightSourcePersistor(VarLightPlugin plugin, World world) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(world);

        plugin.getLogger().info(String.format("Created a new Lightsource Persistor for world \"%s\"", world.getName()));

        this.worldMap = new HashMap<>();
        this.plugin = plugin;
        this.world = world;
    }

    public VarLightPlugin getPlugin() {
        return plugin;
    }

    public World getWorld() {
        return world;
    }

    public int getEmittingLightLevel(Location location, int def) {
        return getPersistentLightSource(location).map(PersistentLightSource::getEmittingLight).orElse(def);
    }

    public Optional<PersistentLightSource> getPersistentLightSource(Block block) {
        return getPersistentLightSource(block.getLocation());
    }

    public Optional<PersistentLightSource> getPersistentLightSource(Location location) {
        return getPersistentLightSource(new IntPosition(location));
    }

    public Optional<PersistentLightSource> getPersistentLightSource(int x, int y, int z) {
        return getPersistentLightSource(new IntPosition(x, y, z));
    }

    public Optional<PersistentLightSource> getPersistentLightSource(IntPosition intPosition) {
        Map<IntPosition, PersistentLightSource> regionMap = getRegionMap(new RegionCoordinates(intPosition));
        PersistentLightSource persistentLightSource = regionMap.get(intPosition);

        if (persistentLightSource != null && ! persistentLightSource.isValid()) {
            persistentLightSource = null;
        }

        return Optional.ofNullable(persistentLightSource);
    }

    public PersistentLightSource createPersistentLightSource(IntPosition intPosition, int emittingLight) {
        PersistentLightSource persistentLightSource = new PersistentLightSource(plugin, world, intPosition, emittingLight);
        getRegionMap(new RegionCoordinates(intPosition)).put(intPosition, persistentLightSource);

        return persistentLightSource;
    }

    public PersistentLightSource getOrCreatePersistentLightSource(IntPosition position) {
        return getPersistentLightSource(position).orElseGet(() -> createPersistentLightSource(position, 0));
    }

    public void save(CommandSender commandSender) {
        Gson gson = new Gson();

        int persistedRegions = 0;

        List<RegionCoordinates> regionsToUnload = new ArrayList<>();

        for (Map.Entry<RegionCoordinates, Map<IntPosition, PersistentLightSource>> entry : worldMap.entrySet()) {
            File saveFile = getSaveFile(entry.getKey());
            List<IntPosition> lightSourcesToUnload = new ArrayList<>();

            int written = 0, loaded = 0;

            try (JsonWriter jsonWriter = new JsonWriter(new FileWriter(saveFile))) {
                jsonWriter.beginArray();

                for (PersistentLightSource persistentLightSource : entry.getValue().values()) {
                    if (world.isChunkLoaded(persistentLightSource.getPosition().getChunkX(), persistentLightSource.getPosition().getChunkZ())) {
                        loaded++;
                    } else {
                        written++;
                        persistentLightSource.write(gson, jsonWriter);
                        lightSourcesToUnload.add(persistentLightSource.getPosition());
                        continue;
                    }

                    if (persistentLightSource.isValid()) {
                        persistentLightSource.write(gson, jsonWriter);
                        written++;
                    }
                }

                jsonWriter.endArray();
            } catch (IOException e) {
                throw new LightPersistFailedException(e);
            }

            for (IntPosition intPosition : lightSourcesToUnload) {
                entry.getValue().remove(intPosition);
            }

            if (written == 0) {
                saveFile.delete();
            } else {
                persistedRegions++;
            }

            if (loaded == 0) {
                regionsToUnload.add(entry.getKey());
            }
        }

        for (RegionCoordinates regionCoordinates : regionsToUnload) {
            unloadRegion(regionCoordinates);
        }

        commandSender.sendMessage(String.format("Light Sources persisted for World \"%s\", Files written to disk: %d", world.getName(), persistedRegions));
    }

    private void unloadRegion(RegionCoordinates key) {
        worldMap.remove(key);
    }

    private Map<IntPosition, PersistentLightSource> getRegionMap(RegionCoordinates regionCoordinates) {
        if (! worldMap.containsKey(regionCoordinates)) {

            File regionFile = getSaveFile(regionCoordinates);

            if (! regionFile.exists()) {
                worldMap.put(regionCoordinates, new HashMap<>());
            } else {
                worldMap.put(regionCoordinates, loadRegionMap(regionFile));
            }
        }

        return worldMap.get(regionCoordinates);
    }

    private File getSaveDirectory() {
        File varlightDir = new File(world.getWorldFolder(), "varlight");

        if (! varlightDir.exists()) {
            if (! varlightDir.mkdir()) {
                throw new LightPersistFailedException();
            }
        }

        return varlightDir;
    }

    private File getSaveFile(RegionCoordinates regionCoordinates) {
        return new File(getSaveDirectory(), String.format("r.%d.%d.json", regionCoordinates.getRegionX(), regionCoordinates.getRegionZ()));
    }

    private Map<IntPosition, PersistentLightSource> loadRegionMap(File file) {
        Map<IntPosition, PersistentLightSource> regionMap = new HashMap<>();
        Gson gson = new Gson();

        try (JsonReader jsonReader = new JsonReader(new FileReader(file))) {
            jsonReader.beginArray();

            while (true) {
                if (jsonReader.peek() != JsonToken.BEGIN_OBJECT) {
                    break;
                }

                PersistentLightSource persistentLightSource = PersistentLightSource.read(gson, jsonReader);
                persistentLightSource.initialize(world, plugin);

                plugin.getLightUpdater().setLight(persistentLightSource.getPosition().toLocation(world), persistentLightSource.getEmittingLight());

                regionMap.put(persistentLightSource.getPosition(), persistentLightSource);
            }

            jsonReader.endArray();
        } catch (IOException e) {
            throw new LightPersistFailedException(e);
        } catch (IllegalStateException e) {
            throw new LightPersistFailedException(String.format("Failed to load %s: Malformed Region File", file.getName()), e);
        }

        return regionMap;
    }
}
