package me.speedy.fastreset;

import me.speedy.fastreset.access.FastResetManagedServer;
import me.speedy.fastreset.access.FastResetMinecraftAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.progress.LoggingLevelLoadListener;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FastResetLogic {

    public record WallSlot(int index, String title, String status, boolean active) {
        public String label() {
            return "#" + index + " " + title + "\n" + status;
        }
    }

    private enum PendingResult {
        NONE,
        KEEP_DESERT,
        DELETE_REJECTED
    }

    private static final String RESULT_PREFIX = "Desert Seed #";
    private static final Pattern RESULT_WORLD_NAME = Pattern.compile("^" + Pattern.quote(RESULT_PREFIX) + "(\\d+)$");

    private static final Set<String> claimedWorlds = ConcurrentHashMap.newKeySet();
    private static final Set<String> rejectedWorlds = ConcurrentHashMap.newKeySet();
    private static final Queue<FastResetQueueEntry> queuedWorlds = new ConcurrentLinkedQueue<>();

    public static volatile boolean active = false;
    public static volatile boolean worldLoaded = false;
    public static volatile String currentWorldName;

    private static volatile boolean autoCreateWorldRequested = false;
    private static volatile boolean stopRequested = false;
    private static volatile PendingResult pendingResult = PendingResult.NONE;
    private static volatile String pendingResultWorldName;
    private static volatile FastResetQueueEntry currentEntry;
    private static volatile String creatingWorldName;
    private static volatile String finishReason;
    private static int desertsFoundThisRun = 0;

    public static void tick(Minecraft client) {
        if (!active) return;

        cleanupStoppedQueuedWorlds(client);

        if (client.level != null && client.player != null && !worldLoaded && currentEntry != null) {
            evaluateCurrentWorld(client);
        }

        if (worldLoaded && client.getSingleplayerServer() == null && pendingResult != PendingResult.NONE) {
            finishCurrentCandidate(client);
        }

        if (currentEntry == null && pendingResult == PendingResult.NONE) {
            if (finishReason != null) {
                if (queuedWorlds.isEmpty() && !autoCreateWorldRequested && creatingWorldName == null) {
                    finishSearch(finishReason);
                }
                return;
            }

            fillQueue(client);
            loadReadyQueuedWorld(client);
        }
    }

    public static boolean isActive() {
        return active;
    }

    public static Component getSearchButtonLabel() {
        return Component.literal(active ? "Stop Search" : "Seed Search");
    }

    public static Component getWallSummary() {
        String limit = FastResetConfig.desertLimit() == 0
                ? "Unlimited"
                : String.valueOf(FastResetConfig.desertLimit());

        if (!active) {
            return Component.literal("Seed Search ready - Parallel worlds: " + FastResetConfig.parallelWorlds());
        }
        if (finishReason != null) {
            return Component.literal("Stopping - " + finishReason + " - Kept: " + desertsFoundThisRun + "/" + limit);
        }
        if (stopRequested) {
            return Component.literal("Stopping - cleaning queued worlds - Kept: " + desertsFoundThisRun + "/" + limit);
        }

        return Component.literal("Queue: " + queuedWorlds.size() + " ready / " + FastResetConfig.parallelWorlds()
                + " - Kept: " + desertsFoundThisRun + "/" + limit);
    }

    public static List<WallSlot> getWallSlots() {
        int slotCount = FastResetConfig.parallelWorlds();
        List<WallSlot> slots = new ArrayList<>(slotCount);

        if (currentEntry != null) {
            slots.add(new WallSlot(slots.size() + 1, currentEntry.worldName(), "Checking", true));
        }

        for (FastResetQueueEntry entry : new ArrayList<>(queuedWorlds)) {
            if (slots.size() >= slotCount) break;

            String status;
            if (entry.isStopping()) {
                status = "Cleaning";
            } else if (entry.isReady()) {
                status = "Ready";
            } else {
                status = "Loading";
            }

            slots.add(new WallSlot(slots.size() + 1, entry.worldName(), status, true));
        }

        if (slots.size() < slotCount && (creatingWorldName != null || autoCreateWorldRequested)) {
            String title = creatingWorldName == null ? "New world" : creatingWorldName;
            slots.add(new WallSlot(slots.size() + 1, title, "Building", true));
        }

        while (slots.size() < slotCount) {
            slots.add(new WallSlot(slots.size() + 1, "Empty", active ? "Waiting" : "Idle", false));
        }

        return slots;
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
        return active && !stopRequested && finishReason == null;
    }

    public static String prepareCandidateWorld(Minecraft client) {
        if (!active || stopRequested || finishReason != null) return null;

        String name = RESULT_PREFIX + getFirstAvailableResultNumber(client);
        creatingWorldName = name;
        currentWorldName = name;
        claimedWorlds.add(name);

        System.out.println("[FastReset] Queueing candidate: " + name);
        return name;
    }

    public static boolean captureQueuedWorldLoad(Minecraft client, LevelStorageSource.LevelStorageAccess levelAccess,
                                                 PackRepository packRepository, WorldStem worldStem,
                                                 Optional<GameRules> gameRules) {
        String name = creatingWorldName;
        if (!active || name == null) return false;

        creatingWorldName = null;

        try {
            levelAccess.saveDataTag(worldStem.worldDataAndGenSettings().data());

            IntegratedServer server = MinecraftServer.spin(thread -> new IntegratedServer(
                    thread,
                    client,
                    levelAccess,
                    packRepository,
                    worldStem,
                    gameRules,
                    client.services(),
                    LoggingLevelLoadListener.forSingleplayer()
            ));

            FastResetQueueEntry entry = new FastResetQueueEntry(name, server);
            queuedWorlds.add(entry);
            currentWorldName = null;

            System.out.println("[FastReset] Background world started: " + name);
            client.setScreen(new TitleScreen());
            return true;
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            finishReason = "failed to start queued world " + name;
            rejectedWorlds.add(name);
            levelAccess.safeClose();
            deleteClaimedWorld(client, name);
            rejectedWorlds.remove(name);
            return true;
        }
    }

    public static void prepareServerForRejectedCandidateShutdown(MinecraftServer server) {
        String name = ((FastResetManagedServer) server).fastreset$getWorldName();
        if (name == null || !rejectedWorlds.contains(name)) return;

        System.out.println("[FastReset] Skipping chunk save for rejected candidate: " + name);
        server.setAutoSave(false);
    }

    private static void startSearch(Minecraft client) {
        FastResetConfig.load(client);

        active = true;
        stopRequested = false;
        autoCreateWorldRequested = false;
        worldLoaded = false;
        currentWorldName = null;
        creatingWorldName = null;
        pendingResult = PendingResult.NONE;
        pendingResultWorldName = null;
        currentEntry = null;
        finishReason = null;
        desertsFoundThisRun = 0;
        claimedWorlds.clear();
        rejectedWorlds.clear();
        queuedWorlds.clear();

        System.out.println("[FastReset] Seed search started. desertLimit=" + FastResetConfig.desertLimit()
                + ", parallelWorlds=" + FastResetConfig.parallelWorlds());
        fillQueue(client);
    }

    private static void requestStop() {
        if (!active) return;

        stopRequested = true;
        autoCreateWorldRequested = false;
        finishReason = "manual stop";
        System.out.println("[FastReset] Stop requested. Cleaning queued candidates.");

        cleanupQueuedCandidates();
    }

    private static void finishSearch(String reason) {
        active = false;
        stopRequested = false;
        autoCreateWorldRequested = false;
        worldLoaded = false;
        currentWorldName = null;
        creatingWorldName = null;
        pendingResult = PendingResult.NONE;
        pendingResultWorldName = null;
        currentEntry = null;
        finishReason = null;
        claimedWorlds.clear();
        rejectedWorlds.clear();
        queuedWorlds.clear();

        System.out.println("[FastReset] Seed search stopped: " + reason + ". Desert worlds kept this run: " + desertsFoundThisRun);
    }

    private static void evaluateCurrentWorld(Minecraft client) {
        worldLoaded = true;
        currentWorldName = currentEntry.worldName();

        BlockPos pos = client.player.blockPosition();
        boolean isDesert = client.level.getBiome(pos).is(Biomes.DESERT);

        if (isDesert) {
            desertsFoundThisRun++;
            pendingResult = PendingResult.KEEP_DESERT;
            pendingResultWorldName = currentEntry.worldName();
            claimedWorlds.remove(currentEntry.worldName());
            System.out.println("[FastReset] Desert found -> KEEP: " + currentEntry.worldName());
        } else {
            pendingResult = PendingResult.DELETE_REJECTED;
            pendingResultWorldName = currentEntry.worldName();
            rejectedWorlds.add(currentEntry.worldName());
            System.out.println("[FastReset] Not desert -> DELETE: " + currentEntry.worldName());
        }

        client.execute(() -> client.disconnectFromWorld(Component.literal("[FastReset]")));
    }

    private static void finishCurrentCandidate(Minecraft client) {
        PendingResult result = pendingResult;
        String name = pendingResultWorldName;

        pendingResult = PendingResult.NONE;
        pendingResultWorldName = null;
        currentWorldName = null;
        worldLoaded = false;
        currentEntry = null;

        if (result == PendingResult.DELETE_REJECTED) {
            boolean deleted = deleteClaimedWorld(client, name);
            rejectedWorlds.remove(name);

            if (!deleted) {
                finishReason = "failed to delete rejected candidate " + name;
                cleanupQueuedCandidates();
                return;
            }
        }

        if (shouldContinueSearch()) {
            fillQueue(client);
            loadReadyQueuedWorld(client);
        } else {
            finishReason = stopRequested ? "manual stop" : "desert limit reached";
            cleanupQueuedCandidates();
        }
    }

    private static boolean shouldContinueSearch() {
        if (!active || stopRequested || finishReason != null) return false;
        int limit = FastResetConfig.desertLimit();
        return limit == 0 || desertsFoundThisRun < limit;
    }

    private static void fillQueue(Minecraft client) {
        if (!shouldContinueSearch()) return;
        if (autoCreateWorldRequested || creatingWorldName != null) return;
        if (client.level != null || client.getSingleplayerServer() != null) return;
        if (client.screen instanceof CreateWorldScreen) return;
        if (loadedWorldCount() >= FastResetConfig.parallelWorlds()) return;

        openCreateWorldScreen(client);
    }

    private static int loadedWorldCount() {
        int count = queuedWorlds.size();
        if (currentEntry != null) count++;
        if (autoCreateWorldRequested || creatingWorldName != null) count++;
        return count;
    }

    private static void loadReadyQueuedWorld(Minecraft client) {
        if (!shouldContinueSearch()) return;
        if (currentEntry != null || client.level != null || client.getSingleplayerServer() != null) return;
        if (autoCreateWorldRequested || creatingWorldName != null || client.screen instanceof CreateWorldScreen) return;

        for (FastResetQueueEntry entry : new ArrayList<>(queuedWorlds)) {
            if (entry.isStopping() || !entry.isReady()) continue;
            if (!queuedWorlds.remove(entry)) continue;

            currentEntry = entry;
            worldLoaded = false;
            currentWorldName = entry.worldName();
            entry.markLoadRequested();
            connectToQueuedWorld(client, entry);
            return;
        }
    }

    private static void connectToQueuedWorld(Minecraft client, FastResetQueueEntry entry) {
        try {
            Instant start = Instant.now();
            LevelLoadTracker tracker = new LevelLoadTracker(0L);
            LevelLoadingScreen loadingScreen = new LevelLoadingScreen(tracker, LevelLoadingScreen.Reason.OTHER);
            client.setScreen(loadingScreen);

            FastResetMinecraftAccess access = (FastResetMinecraftAccess) client;
            access.fastreset$setSingleplayerServer(entry.server());
            access.fastreset$setLocalServer(true);
            client.updateReportEnvironment(ReportEnvironment.local());

            int radius = Math.max(5, 3) + ChunkLevel.RADIUS_AROUND_FULL_CHUNK + 1;
            tracker.setServerChunkStatusView(entry.server().createChunkLoadStatusView(radius));

            SocketAddress address = entry.server().getConnection().startMemoryChannel();
            Connection connection = Connection.connectToLocalServer(address);
            connection.initiateServerboundPlayConnection(
                    address.toString(),
                    0,
                    new ClientHandshakePacketListenerImpl(
                            connection,
                            client,
                            null,
                            null,
                            false,
                            Duration.between(start, Instant.now()),
                            component -> {},
                            tracker,
                            null
                    )
            );
            connection.send(new ServerboundHelloPacket(client.getUser().getName(), client.getUser().getProfileId()));
            access.fastreset$setPendingConnection(connection);

            System.out.println("[FastReset] Loading queued world: " + entry.worldName());
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            rejectedWorlds.add(entry.worldName());
            FastResetMinecraftAccess access = (FastResetMinecraftAccess) client;
            access.fastreset$setSingleplayerServer(null);
            access.fastreset$setLocalServer(false);
            access.fastreset$setPendingConnection(null);
            if (!queuedWorlds.contains(entry)) {
                queuedWorlds.add(entry);
            }
            entry.stop(true);
            currentEntry = null;
            finishReason = "failed to load queued world " + entry.worldName();
            cleanupQueuedCandidates();
        }
    }

    private static void cleanupQueuedCandidates() {
        for (FastResetQueueEntry entry : new ArrayList<>(queuedWorlds)) {
            if (entry.isStopping()) continue;

            rejectedWorlds.add(entry.worldName());
            entry.stop(true);
            System.out.println("[FastReset] Stopping queued candidate: " + entry.worldName());
        }
    }

    private static void cleanupStoppedQueuedWorlds(Minecraft client) {
        for (FastResetQueueEntry entry : new ArrayList<>(queuedWorlds)) {
            if (!entry.isStopping() || !entry.isShutdown()) continue;

            queuedWorlds.remove(entry);
            if (entry.shouldDeleteAfterStop()) {
                deleteClaimedWorld(client, entry.worldName());
                rejectedWorlds.remove(entry.worldName());
            }
        }
    }

    private static int getFirstAvailableResultNumber(Minecraft client) {
        try {
            Path saves = getSavesPath(client);
            Set<Integer> usedNumbers = new HashSet<>();

            if (Files.exists(saves)) {
                try (var stream = Files.list(saves)) {
                    for (Path path : (Iterable<Path>) stream::iterator) {
                        addResultNumber(usedNumbers, path.getFileName().toString());
                    }
                }
            }

            for (String claimedWorld : claimedWorlds) {
                addResultNumber(usedNumbers, claimedWorld);
            }

            for (int number = 1; number < Integer.MAX_VALUE; number++) {
                if (!usedNumbers.contains(number)) return number;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return 1;
    }

    private static void addResultNumber(Set<Integer> usedNumbers, String worldName) {
        Matcher matcher = RESULT_WORLD_NAME.matcher(worldName);
        if (matcher.matches()) {
            usedNumbers.add(Integer.parseInt(matcher.group(1)));
        }
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

    private static Path getSavesPath(Minecraft client) {
        return client.gameDirectory.toPath().resolve("saves");
    }

    private static void openCreateWorldScreen(Minecraft client) {
        if (!active || stopRequested || finishReason != null) return;

        autoCreateWorldRequested = true;
        client.execute(() -> CreateWorldScreen.openFresh(client, () -> {}));
    }
}
