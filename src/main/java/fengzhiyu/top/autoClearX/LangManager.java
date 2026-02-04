package fengzhiyu.top.autoClearX;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class LangManager {
    private static final String DEFAULT_LANGUAGE = "zh_CN";

    private final JavaPlugin plugin;
    private final YamlConfiguration primary;
    private final YamlConfiguration fallback;
    private final String language;

    public LangManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.language = plugin.getConfig().getString("language", DEFAULT_LANGUAGE);
        saveDefaultLanguages();
        this.fallback = loadConfig("lang/" + DEFAULT_LANGUAGE + ".yml");
        if (DEFAULT_LANGUAGE.equalsIgnoreCase(language)) {
            this.primary = fallback;
        } else {
            this.primary = loadConfig("lang/" + language + ".yml");
        }
    }

    public String get(String key, String... replacements) {
        String value = getRaw(key);
        if (replacements != null) {
            for (int i = 0; i + 1 < replacements.length; i += 2) {
                value = value.replace("{" + replacements[i] + "}", replacements[i + 1]);
            }
        }
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    public String getLanguage() {
        return language;
    }

    private String getRaw(String key) {
        String value = primary.getString(key);
        if (value == null) {
            value = fallback.getString(key);
        }
        return value == null ? key : value;
    }

    private void saveDefaultLanguages() {
        plugin.saveResource("lang/zh_CN.yml", false);
        plugin.saveResource("lang/en_US.yml", false);
    }

    private YamlConfiguration loadConfig(String path) {
        File file = new File(plugin.getDataFolder(), path);
        YamlConfiguration config = new YamlConfiguration();
        if (file.exists()) {
            config = YamlConfiguration.loadConfiguration(file);
        }
        try (InputStream stream = plugin.getResource(path)) {
            if (stream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)
                );
                config.setDefaults(defaults);
            }
        } catch (Exception ex) {
            // Ignore to avoid failing plugin startup if language defaults cannot be read.
        }
        return config;
    }
}
