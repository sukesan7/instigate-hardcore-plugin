package dev.instigatehardcore.player;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

public final class MockPlayerHelper {

    private MockPlayerHelper() {
    }

    public static class TestPlugin extends JavaPlugin {
    }

    public static JavaPlugin createMockPlugin() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Unsafe unsafe = (Unsafe) field.get(null);
            JavaPlugin plugin = (JavaPlugin) unsafe.allocateInstance(TestPlugin.class);

            Field loggerField = JavaPlugin.class.getDeclaredField("logger");
            loggerField.setAccessible(true);
            loggerField.set(plugin, java.util.logging.Logger.getLogger("InstigateHardcoreTest"));

            org.bukkit.scheduler.BukkitScheduler scheduler = (org.bukkit.scheduler.BukkitScheduler) Proxy.newProxyInstance(
                org.bukkit.scheduler.BukkitScheduler.class.getClassLoader(),
                new Class<?>[]{org.bukkit.scheduler.BukkitScheduler.class},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("runTask") && args != null && args.length >= 2 && args[1] instanceof Runnable r) {
                        r.run();
                        return null;
                    }
                    if (method.getReturnType().equals(boolean.class)) return false;
                    if (method.getReturnType().isPrimitive()) return 0;
                    return null;
                }
            );

            org.bukkit.Server server = (org.bukkit.Server) Proxy.newProxyInstance(
                org.bukkit.Server.class.getClassLoader(),
                new Class<?>[]{org.bukkit.Server.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getScheduler")) {
                        return scheduler;
                    }
                    if (name.equals("getLogger")) {
                        return java.util.logging.Logger.getLogger("InstigateHardcoreServerTest");
                    }
                    if (name.equals("broadcast")) {
                        return 0;
                    }
                    if (name.equals("getOnlinePlayers")) {
                        return Collections.emptyList();
                    }
                    if (method.getReturnType().equals(boolean.class)) return false;
                    if (method.getReturnType().isPrimitive()) return 0;
                    return null;
                }
            );

            Field serverField = JavaPlugin.class.getDeclaredField("server");
            serverField.setAccessible(true);
            serverField.set(plugin, server);

            return plugin;
        } catch (Exception e) {
            throw new RuntimeException("Failed to allocate mock JavaPlugin", e);
        }
    }

    public static World createMockWorld(String name) {
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[]{World.class},
            (proxy, method, args) -> {
                String methodName = method.getName();
                if (methodName.equals("getName")) {
                    return name;
                }
                if (methodName.equals("getSpawnLocation")) {
                    return new Location((World) proxy, 0, 64, 0);
                }
                if (methodName.equals("equals") && args != null && args.length == 1) {
                    return proxy == args[0];
                }
                if (methodName.equals("hashCode")) {
                    return name.hashCode();
                }
                if (method.getReturnType().equals(boolean.class)) {
                    return false;
                }
                if (method.getReturnType().isPrimitive()) {
                    return 0;
                }
                return null;
            }
        );
    }

    public static Player createMockPlayer(UUID uuid, String name) {
        Map<Statistic, Integer> statistics = new EnumMap<>(Statistic.class);
        final GameMode[] gameMode = {GameMode.SURVIVAL};
        final World[] world = {createMockWorld("world")};
        final Location[] location = {new Location(world[0], 0, 64, 0)};

        PlayerInventory mockInventory = (PlayerInventory) Proxy.newProxyInstance(
            PlayerInventory.class.getClassLoader(),
            new Class<?>[]{PlayerInventory.class},
            (proxy, method, args) -> {
                if (method.getReturnType().equals(boolean.class)) return false;
                if (method.getReturnType().isPrimitive()) return 0;
                return null;
            }
        );

        Inventory mockEnderChest = (Inventory) Proxy.newProxyInstance(
            Inventory.class.getClassLoader(),
            new Class<?>[]{Inventory.class},
            (proxy, method, args) -> {
                if (method.getReturnType().equals(boolean.class)) return false;
                if (method.getReturnType().isPrimitive()) return 0;
                return null;
            }
        );

        AttributeInstance mockMaxHealth = (AttributeInstance) Proxy.newProxyInstance(
            AttributeInstance.class.getClassLoader(),
            new Class<?>[]{AttributeInstance.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getValue")) {
                    return 20.0;
                }
                if (method.getReturnType().equals(double.class)) return 20.0;
                if (method.getReturnType().isPrimitive()) return 0;
                return null;
            }
        );

        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (proxy, method, args) -> {
                String methodName = method.getName();
                if (methodName.equals("getUniqueId")) {
                    return uuid;
                }
                if (methodName.equals("getName")) {
                    return name;
                }
                if (methodName.equals("isOnline")) {
                    return true;
                }
                if (methodName.equals("isDead")) {
                    return false;
                }
                if (methodName.equals("setStatistic") && args != null && args.length >= 2) {
                    statistics.put((Statistic) args[0], (Integer) args[1]);
                    return null;
                }
                if (methodName.equals("getStatistic") && args != null && args.length >= 1) {
                    return statistics.getOrDefault((Statistic) args[0], 0);
                }
                if (methodName.equals("teleport")) {
                    if (args != null && args.length > 0 && args[0] instanceof Location loc) {
                        location[0] = loc;
                        world[0] = loc.getWorld();
                    }
                    return true;
                }
                if (methodName.equals("getInventory")) {
                    return mockInventory;
                }
                if (methodName.equals("getEnderChest")) {
                    return mockEnderChest;
                }
                if (methodName.equals("getAttribute")) {
                    if (args != null && args.length > 0 && args[0] == Attribute.MAX_HEALTH) {
                        return mockMaxHealth;
                    }
                    return null;
                }
                if (methodName.equals("getActivePotionEffects")) {
                    return Collections.emptyList();
                }
                if (methodName.equals("getGameMode")) {
                    return gameMode[0];
                }
                if (methodName.equals("setGameMode") && args != null && args.length > 0) {
                    gameMode[0] = (GameMode) args[0];
                    return null;
                }
                if (methodName.equals("getWorld")) {
                    return world[0];
                }
                if (methodName.equals("getLocation")) {
                    return location[0];
                }
                if (methodName.equals("hashCode")) {
                    return uuid.hashCode();
                }
                if (methodName.equals("equals") && args != null && args.length == 1) {
                    return proxy == args[0];
                }
                if (methodName.equals("toString")) {
                    return "MockPlayer[" + name + "," + uuid + "]";
                }
                if (method.getReturnType().equals(boolean.class)) {
                    return false;
                }
                if (method.getReturnType().equals(double.class)) {
                    return 0.0;
                }
                if (method.getReturnType().equals(float.class)) {
                    return 0.0f;
                }
                if (method.getReturnType().isPrimitive()) {
                    return 0;
                }
                return null;
            }
        );
    }
}
