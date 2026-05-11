package com.aiplayer.plugin;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.di.ServiceContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PluginManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginManager.class);

    private static final PluginManager INSTANCE = new PluginManager();

        private final ConcurrentHashMap<String, ActionPlugin> loadedPlugins;

        private final List<String> loadOrder;

        private volatile boolean initialized;

    private PluginManager() {
        this.loadedPlugins = new ConcurrentHashMap<>();
        this.loadOrder = new ArrayList<>();
        this.initialized = false;
    }

        public static PluginManager getInstance() {
        return INSTANCE;
    }

        public synchronized void loadPlugins(ActionRegistry registry, ServiceContainer container) {
        if (initialized) {
            LOGGER.warn("Plugins already loaded, skipping");
            return;
        }

        AiPlayerMod.info("plugin", "Discovering plugins via ServiceLoader...");
        ServiceLoader<ActionPlugin> loader = ServiceLoader.load(ActionPlugin.class);
        List<ActionPlugin> discovered = new ArrayList<>();

        for (ActionPlugin plugin : loader) {
            discovered.add(plugin);
            AiPlayerMod.info("plugin", "Discovered plugin: {} v{} (priority: {})",
                plugin.getPluginId(), plugin.getVersion(), plugin.getPriority());
        }

        if (discovered.isEmpty()) {
            LOGGER.warn("No plugins discovered! Check META-INF/services configuration");
            return;
        }
        List<ActionPlugin> sorted = sortPlugins(discovered);
        for (ActionPlugin plugin : sorted) {
            try {
                loadPlugin(plugin, registry, container);
            } catch (Exception e) {
                LOGGER.error("Failed to load plugin {}: {}", plugin.getPluginId(), e.getMessage(), e);
            }
        }

        initialized = true;
        AiPlayerMod.info("plugin", "Plugin loading complete: {} plugins loaded", loadedPlugins.size());
    }

        private void loadPlugin(ActionPlugin plugin, ActionRegistry registry, ServiceContainer container) {
        String pluginId = plugin.getPluginId();
        if (loadedPlugins.containsKey(pluginId)) {
            LOGGER.warn("Plugin {} already loaded, skipping", pluginId);
            return;
        }
        for (String dependency : plugin.getDependencies()) {
            if (!loadedPlugins.containsKey(dependency)) {
                throw new IllegalStateException(
                    "Plugin " + pluginId + " requires " + dependency + " which is not loaded");
            }
        }

        AiPlayerMod.info("plugin", "Loading plugin: {} v{}", pluginId, plugin.getVersion());
        plugin.onLoad(registry, container);
        loadedPlugins.put(pluginId, plugin);
        loadOrder.add(pluginId);

        AiPlayerMod.info("plugin", "Plugin {} loaded successfully", pluginId);
    }

        private List<ActionPlugin> sortPlugins(List<ActionPlugin> plugins) {
        Map<String, ActionPlugin> pluginMap = new HashMap<>();
        Map<String, Set<String>> dependencies = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (ActionPlugin plugin : plugins) {
            String id = plugin.getPluginId();
            pluginMap.put(id, plugin);
            dependencies.put(id, new HashSet<>(Arrays.asList(plugin.getDependencies())));
            inDegree.put(id, 0);
        }
        for (ActionPlugin plugin : plugins) {
            for (String dep : plugin.getDependencies()) {
                if (pluginMap.containsKey(dep)) {
                    inDegree.merge(plugin.getPluginId(), 1, Integer::sum);
                }
            }
        }
        List<ActionPlugin> sorted = new ArrayList<>();
        PriorityQueue<ActionPlugin> queue = new PriorityQueue<>(
            Comparator.comparingInt(ActionPlugin::getPriority).reversed());
        for (ActionPlugin plugin : plugins) {
            if (inDegree.get(plugin.getPluginId()) == 0) {
                queue.offer(plugin);
            }
        }

        Set<String> processed = new HashSet<>();
        while (!queue.isEmpty()) {
            ActionPlugin plugin = queue.poll();
            sorted.add(plugin);
            processed.add(plugin.getPluginId());
            for (ActionPlugin other : plugins) {
                if (processed.contains(other.getPluginId())) continue;

                Set<String> deps = dependencies.get(other.getPluginId());
                if (deps.contains(plugin.getPluginId())) {
                    int newDegree = inDegree.get(other.getPluginId()) - 1;
                    inDegree.put(other.getPluginId(), newDegree);
                    boolean allSatisfied = deps.stream().allMatch(processed::contains);
                    if (allSatisfied && !queue.contains(other)) {
                        queue.offer(other);
                    }
                }
            }
        }
        if (sorted.size() != plugins.size()) {
            LOGGER.error("Circular dependency detected! Some plugins could not be sorted.");
            for (ActionPlugin plugin : plugins) {
                if (!processed.contains(plugin.getPluginId())) {
                    LOGGER.warn("Plugin {} has unresolved dependencies, loading anyway",
                        plugin.getPluginId());
                    sorted.add(plugin);
                }
            }
        }

        return sorted;
    }

        public synchronized void unloadPlugins() {
        if (!initialized) {
            LOGGER.warn("Plugins not loaded, nothing to unload");
            return;
        }

        AiPlayerMod.info("plugin", "Unloading {} plugins...", loadedPlugins.size());
        List<String> reversed = new ArrayList<>(loadOrder);
        Collections.reverse(reversed);

        for (String pluginId : reversed) {
            ActionPlugin plugin = loadedPlugins.get(pluginId);
            if (plugin != null) {
                try {
                    AiPlayerMod.info("plugin", "Unloading plugin: {}", pluginId);
                    plugin.onUnload();
                } catch (Exception e) {
                    LOGGER.error("Error unloading plugin {}: {}", pluginId, e.getMessage(), e);
                }
            }
        }

        loadedPlugins.clear();
        loadOrder.clear();
        initialized = false;

        AiPlayerMod.info("plugin", "All plugins unloaded");
    }

        public ActionPlugin getPlugin(String pluginId) {
        return loadedPlugins.get(pluginId);
    }

        public boolean isPluginLoaded(String pluginId) {
        return loadedPlugins.containsKey(pluginId);
    }

        public Set<String> getLoadedPluginIds() {
        return Collections.unmodifiableSet(loadedPlugins.keySet());
    }

        public int getPluginCount() {
        return loadedPlugins.size();
    }

        public boolean isInitialized() {
        return initialized;
    }
}
