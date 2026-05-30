package me.speedy.fastreset;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class FastResetConfig {

    public static final String CONFIG_FILE_NAME = "fastreset.properties";

    public static final int DEFAULT_DESERT_LIMIT = 25;
    public static final int MIN_DESERT_LIMIT = 0;
    public static final int MAX_DESERT_LIMIT = 100;

    public static final int DEFAULT_PARALLEL_WORLDS = 4;
    public static final int MIN_PARALLEL_WORLDS = 1;
    public static final int MAX_PARALLEL_WORLDS = 16;

    private static final String DESERT_LIMIT_KEY = "desertLimit";
    private static final String PARALLEL_WORLDS_KEY = "parallelWorlds";

    private static int desertLimit = DEFAULT_DESERT_LIMIT;
    private static int parallelWorlds = DEFAULT_PARALLEL_WORLDS;

    private FastResetConfig() {
    }

    public static int desertLimit() {
        return desertLimit;
    }

    public static int parallelWorlds() {
        return parallelWorlds;
    }

    public static void setDesertLimit(int value) {
        desertLimit = clamp(value, MIN_DESERT_LIMIT, MAX_DESERT_LIMIT);
    }

    public static void setParallelWorlds(int value) {
        parallelWorlds = clamp(value, MIN_PARALLEL_WORLDS, MAX_PARALLEL_WORLDS);
    }

    public static void load(Minecraft client) {
        Path config = getConfigPath(client);
        ensureDefaultConfig(config);

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(config)) {
            properties.load(input);
        } catch (IOException e) {
            e.printStackTrace();
        }

        setDesertLimit(parseInt(properties.getProperty(DESERT_LIMIT_KEY), DEFAULT_DESERT_LIMIT));
        setParallelWorlds(parseInt(properties.getProperty(PARALLEL_WORLDS_KEY), DEFAULT_PARALLEL_WORLDS));
    }

    public static void save(Minecraft client) {
        Path config = getConfigPath(client);

        try {
            Files.createDirectories(config.getParent());

            Properties properties = new Properties();
            properties.setProperty(DESERT_LIMIT_KEY, String.valueOf(desertLimit));
            properties.setProperty(PARALLEL_WORLDS_KEY, String.valueOf(parallelWorlds));

            try (OutputStream output = Files.newOutputStream(config)) {
                properties.store(output, "FastReset seed search settings. Set desertLimit=0 for unlimited.");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static Path getConfigPath(Minecraft client) {
        return client.gameDirectory.toPath().resolve("config").resolve(CONFIG_FILE_NAME);
    }

    private static void ensureDefaultConfig(Path config) {
        if (Files.exists(config)) return;

        try {
            Files.createDirectories(config.getParent());

            Properties properties = new Properties();
            properties.setProperty(DESERT_LIMIT_KEY, String.valueOf(DEFAULT_DESERT_LIMIT));
            properties.setProperty(PARALLEL_WORLDS_KEY, String.valueOf(DEFAULT_PARALLEL_WORLDS));

            try (OutputStream output = Files.newOutputStream(config)) {
                properties.store(output, "FastReset seed search settings. Set desertLimit=0 for unlimited.");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null) return fallback;

        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            System.out.println("[FastReset] Invalid config value '" + raw + "', using " + fallback);
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
