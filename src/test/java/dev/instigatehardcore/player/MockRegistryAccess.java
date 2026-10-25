package dev.instigatehardcore.player;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import net.kyori.adventure.key.Key;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

public final class MockRegistryAccess implements RegistryAccess {

    private final Map<Object, Registry<?>> registries = new HashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Keyed> Registry<T> getRegistry(Class<T> type) {
        return (Registry<T>) registries.computeIfAbsent(type, k -> createMockRegistry(type));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Keyed> Registry<T> getRegistry(RegistryKey<T> key) {
        return (Registry<T>) registries.computeIfAbsent(key, k -> createMockRegistry(null));
    }

    @SuppressWarnings("unchecked")
    private <T extends Keyed> Registry<T> createMockRegistry(Class<T> type) {
        Map<Key, T> cache = new HashMap<>();
        return (Registry<T>) Proxy.newProxyInstance(
            Registry.class.getClassLoader(),
            new Class<?>[]{Registry.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("get") && args != null && args.length > 0 && args[0] instanceof Key key) {
                    return cache.computeIfAbsent(key, k -> createDummyKeyed(type, key));
                }
                if (name.equals("getKey") && args != null && args.length > 0 && args[0] instanceof Keyed keyed) {
                    return keyed.getKey();
                }
                if (name.equals("stream")) {
                    return Stream.empty();
                }
                if (name.equals("keyStream")) {
                    return Stream.empty();
                }
                if (name.equals("size")) {
                    return 0;
                }
                if (name.equals("getTags")) {
                    return Collections.emptyList();
                }
                if (name.equals("hasTag")) {
                    return false;
                }
                if (method.getReturnType().equals(boolean.class)) return false;
                if (method.getReturnType().isPrimitive()) return 0;
                return null;
            }
        );
    }

    @SuppressWarnings("unchecked")
    private <T extends Keyed> T createDummyKeyed(Class<T> type, Key key) {
        Class<?> iface = (type != null && type.isInterface()) ? type : Keyed.class;
        NamespacedKey namespacedKey = key instanceof NamespacedKey nk ? nk : new NamespacedKey(key.namespace(), key.value());
        return (T) Proxy.newProxyInstance(
            iface.getClassLoader(),
            new Class<?>[]{iface, Keyed.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getKey")) {
                    return namespacedKey;
                }
                if (method.getName().equals("key")) {
                    return namespacedKey;
                }
                if (method.getName().equals("toString")) {
                    return namespacedKey.toString();
                }
                if (method.getName().equals("equals") && args != null && args.length == 1) {
                    return proxy == args[0];
                }
                if (method.getName().equals("hashCode")) {
                    return namespacedKey.hashCode();
                }
                if (method.getReturnType().equals(boolean.class)) return false;
                if (method.getReturnType().isPrimitive()) return 0;
                return null;
            }
        );
    }
}
