package me.speedy.fastreset;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biomes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FastResetLogic {

    private enum PendingResult {
        NONE,
        KEEP_DESERT,
        DELETE_REJECTED
    }

    private static final String RESULT_PREFIX = "Desert Seed #";
    private static final Pattern RESULT_WORLD_NAME = Pattern.compile("^" + Pattern.quote(RESULT_PREFIX) + "(\\d+)$");
    private static final String CONFIG_FILE_NAME = "fastreset.properties";
    private static final String DESERT_LIMIT_KEY = "desertLimit";
    private static final int DEFAULT_DESERT_LIMIT = 25;

    private static final Set<String> claimedWorlds = ConcurrentHashMap.newKeySet();
    private static final Set<String> rejectedWorlds = ConcurrentHashMap.newKeySet();

    public static volatile boolean active = false;
    public static volatile boolean worldLoaded = false;
    public static volatile String currentWorldName;

    private static volatile boolean autoCreateWorldRequested = false;
    private static volatile boolean stopRequested = false;
    private static volatile PendingResult pendingResult = PendingResult.NONE;
    private static volatile String pendingResultWorldName;
    private static int desertsFoundThisRun = 0;
    private static int desertLimit = DEFAULT_DESERT_LIMIT;

    public static void tick(Minecraft client) {
        if (!active) return;

        if (client.level != null && client.player != null && !worldLoaded && currentWorldName != null) {
            worldLoaded = true;

            BlockPos pos = client.player.blockPosition();
            boolean isDesert = client.level.getBiome(pos).is(Biomes.DESERT);

            if (isDesert) {
                desertsFoundThisRun++;
                pendingResult = PendingResult.KEEP_DESERT;
                pendingResultWorldName = currentWorldName;
                claimedWorlds.remove(currentWorldName);
                System.out.println("[FastReset] Desert found -> KEEP: " + currentWorldName);
            } else {
                pendingResult = PendingResult.DELETE_REJECTED;
                pendingResultWorldName = currentWorldName;
                rejectedWorlds.add(currentWorldName);
                System.out.println("[FastReset] Not desert -> DELETE: " + currentWorldName);
            }

            client.execute(() -> client.disconnectFromWorld(Component.literal("[FastReset]")));
        }

        if (worldLoaded && client.getSingleplayerServer() == null && pendingResult != PendingResult.NONE) {
            finishCurrentCandidate(client);
        }
    }

    public static boolean isActive() {
        return active;
    }

    public static Component getSearchButtonLabel() {
        return Component.literal(active ? "Stop Search" : "Seed Search");
    }

    public static void toggleFromTitleScreen(Minecraft client) {
        if (active) {
            requestStop();
            return;
        }

        if (client.screen instanceof TitleScreen && client.level == null) {
            startSearch(client);
        }
    }

    public static void toggleFromKey(Minecraft client) {
        if (active) {
            requestStop();
            return;
        }

        if (client.screen instanceof TitleScreen && client.level == null) {
            startSearch(client);
        }
    }

    public static void requestStopFromButton() {
        requestStop();
    }

    public static boolean consumeCreateWorldRequest() {
        if (!autoCreateWorldRequested) return false;

        autoCreateWorldRequested = false;
        return active && !stopRequested;
    }

    public static String prepareCandidateWorld(Minecraft client) {
        if (!active || stopRequested) return null;

        String name = RESULT_PREFIX + getFirstAvailableResultNumber(client);
        currentWorldName = name;
        worldLoaded = false;
        pendingResult = PendingResult.NONE;
        pendingResultWorldName = null;
        claimedWorlds.add(name);

        System.out.println("[FastReset] Trying to create: " + name);
        return name;
    }

    public static void prepareServerForRejectedCandidateShutdown(MinecraftServer server) {
        if (pendingResult != PendingResult.DELETE_REJECTED || pendingResultWorldName == null) return;
        if (!rejectedWorlds.contains(pendingResultWorldName)) return;

        System.out.println("[FastReset] Skipping chunk save for rejected candidate: " + pendingResultWorldName);
        server.setAutoSave(false);
    }

    private static void startSearch(Minecraft client) {
        loadConfig(client);

        active = true;
        stopRequested = false;
        autoCreateWorldRequested = false;
        worldLoaded = false;
        currentWorldName = null;
        pendingResult = PendingResult.NONE;
        pendingResultWorldName = null;
        desertsFoundThisRun = 0;
        claimedWorlds.clear();
        rejectedWorlds.clear();

        System.out.println("[FastReset] Seed search started. desertLimit=" + desertLimit);
        openCreateWorldScreen(client);
    }

    private static void requestStop() {
        if (!active) return;

        stopRequested = true;
        autoCreateWorldRequested = false;
        System.out.println("[FastReset] Stop requested. Cleaning current candidate first.");

        if (!worldLoaded && currentWorldName == null && pendingResult == PendingResult.NONE) {
            finishSearch("manual stop");
        }
    }

    private static void finishSearch(String reason) {
        active = false;
        stopRequested = false;
        autoCreateWorldRequested = false;
        worldLoaded = false;
        currentWorldName = null;
        pendingResult = PendingResult.NONE;
        pendingResultWorldName = null;
        claimedWorlds.clear();
        rejectedWorlds.clear();

        System.out.println("[FastReset] Seed search stopped: " + reason + ". Desert worlds kept this run: " + desertsFoundThisRun);
    }

    private static void finishCurrentCandidate(Minecraft client) {
        PendingResult result = pendingResult;
        String name = pendingResultWorldName;

        pendingResult = PendingResult.NONE;
        pendingResultWorldName = null;
        currentWorldName = null;
        worldLoaded = false;

        if (result == PendingResult.DELETE_REJECTED) {
            boolean deleted = deleteClaimedWorld(client, name);
            rejectedWorlds.remove(name);

            if (!deleted) {
                finishSearch("failed to delete rejected candidate " + name);
                return;
            }
        }

        if (shouldContinueSearch()) {
            openCreateWorldScreen(client);
        } else if (stopRequested) {
            finishSearch("manual stop");
        } else {
            finishSearch("desert limit reached");
        }
    }

    private static boolean shouldContinueSearch() {
        if (!active || stopRequested) return false;
        return desertLimit == 0 || desertsFoundThisRun < desertLimit;
    }

    private static int getFirstAvailableResultNumber(Minecraft client) {
        try {
            Path saves = getSavesPath(client);
            if (!Files.exists(saves)) return 1;

            Set<Integer> usedNumbers = new HashSet<>();

            try (var stream = Files.list(saves)) {
                for (Path path : (Iterable<Path>) stream::iterator) {
                    Matcher matcher = RESULT_WORLD_NAME.matcher(path.getFileName().toString());
                    if (matcher.matches()) {
                        usedNumbers.add(Integer.parseInt(matcher.group(1)));
                    }
                }
            }

            for (int number = 1; number < Integer.MAX_VALUE; number++) {
                if (!usedNumbers.contains(number)) return number;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return 1;
    }

    private static boolean deleteClaimedWorld(Minecraft client, String name) {
        System.out.println("[FastReset] Trying to delete: " + name);

        try {
            if (name == null || !claimedWorlds.remove(name)) {
                System.out.println("[FastReset] Refusing to delete unmanaged world: " + name);
                return false;
            }

            Path saves = getSavesPath(client).toAbsolutePath().normalize();
            Path dir = saves.resolve(name).normalize();

            if (!dir.startsWith(saves) || !RESULT_WORLD_NAME.matcher(name).matches()) {
                System.out.println("[FastReset] Refusing to delete unexpected world path: " + dir);
                return false;
            }

            if (!Files.exists(dir)) {
                System.out.println("[FastReset] Rejected candidate already gone: " + name);
                return true;
            }

            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                        throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });

            System.out.println("[FastReset] Deleted: " + name);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static void loadConfig(Minecraft client) {
        Path config = getConfigPath(client);
        ensureDefaultConfig(config);

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(config)) {
            properties.load(input);
        } catch (IOException e) {
            e.printStackTrace();
        }

        desertLimit = parseDesertLimit(properties.getProperty(DESERT_LIMIT_KEY));
    }

    private static void ensureDefaultConfig(Path config) {
        if (Files.exists(config)) return;

        try {
            Files.createDirectories(config.getParent());

            Properties properties = new Properties();
            properties.setProperty(DESERT_LIMIT_KEY, String.valueOf(DEFAULT_DESERT_LIMIT));

            try (OutputStream output = Files.newOutputStream(config)) {
                properties.store(output, "FastReset seed search settings. Set desertLimit=0 for unlimited.");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static int parseDesertLimit(String rawLimit) {
        if (rawLimit == null) return DEFAULT_DESERT_LIMIT;

        try {
            int parsed = Integer.parseInt(rawLimit.trim());
            return Math.max(0, parsed);
        } catch (NumberFormatException e) {
            System.out.println("[FastReset] Invalid desertLimit '" + rawLimit + "', using " + DEFAULT_DESERT_LIMIT);
            return DEFAULT_DESERT_LIMIT;
        }
    }

    private static Path getSavesPath(Minecraft client) {
        return client.gameDirectory.toPath().resolve("saves");
    }

    private static Path getConfigPath(Minecraft client) {
        return client.gameDirectory.toPath().resolve("config").resolve(CONFIG_FILE_NAME);
    }

    private static void openCreateWorldScreen(Minecraft client) {
        if (!active || stopRequested) return;

        autoCreateWorldRequested = true;
        client.execute(() -> CreateWorldScreen.openFresh(client, () -> {}));
    }
}
