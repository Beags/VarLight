package me.shawlaf.varlight.spigot.util;

import lombok.experimental.UtilityClass;
import me.shawlaf.varlight.spigot.LightUpdateResult;
import me.shawlaf.varlight.spigot.VarLightPlugin;
import me.shawlaf.varlight.spigot.event.LightUpdateEvent;
import me.shawlaf.varlight.spigot.persistence.WorldLightSourceManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.util.Objects;

import static me.shawlaf.varlight.spigot.LightUpdateResult.*;

@UtilityClass
public class LightSourceUtil {

    public static LightUpdateResult placeNewLightSource(VarLightPlugin plugin, Location location, int lightLevel) {
        return placeNewLightSource(plugin, location, lightLevel, true);
    }

    public static LightUpdateResult placeNewLightSource(VarLightPlugin plugin, Location location, int lightLevel, boolean doUpdate) {
        int fromLight = location.getBlock().getLightFromBlocks();

        WorldLightSourceManager manager = plugin.getManager(Objects.requireNonNull(location.getWorld()));

        if (manager == null) {
            return varLightNotActive(plugin, location.getWorld(), fromLight, lightLevel);
        }

        fromLight = manager.getCustomLuminance(IntPositionExtension.toIntPosition(location), 0);

        if (lightLevel < 0) {
            return zeroReached(plugin, fromLight, lightLevel);
        }

        if (lightLevel > 15) {
            return fifteenReached(plugin, fromLight, lightLevel);
        }

        if (plugin.getNmsAdapter().isIllegalBlock(location.getBlock())) {
            return invalidBlock(plugin, fromLight, lightLevel);
        }

        LightUpdateEvent lightUpdateEvent = new LightUpdateEvent(location.getBlock(), fromLight, lightLevel, !Bukkit.getServer().isPrimaryThread());
        Bukkit.getPluginManager().callEvent(lightUpdateEvent);

        if (lightUpdateEvent.isCancelled()) {
            return cancelled(plugin, fromLight, lightUpdateEvent.getToLight());
        }

        int lightTo = lightUpdateEvent.getToLight();

        manager.setCustomLuminance(location, lightTo);

        if (doUpdate) {
            plugin.getNmsAdapter().updateBlocksAndChunk(location);
        }

        return updated(plugin, fromLight, lightTo);
    }

}
