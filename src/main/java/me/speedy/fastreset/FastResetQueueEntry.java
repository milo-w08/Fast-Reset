package me.speedy.fastreset;

import me.speedy.fastreset.access.FastResetManagedServer;
import net.minecraft.client.server.IntegratedServer;

public class FastResetQueueEntry {

    private final String worldName;
    private final IntegratedServer server;
    private volatile boolean loadRequested;
    private volatile boolean stopRequested;
    private volatile boolean deleteAfterStop;

    public FastResetQueueEntry(String worldName, IntegratedServer server) {
        this.worldName = worldName;
        this.server = server;
        ((FastResetManagedServer) server).fastreset$markQueued(worldName);
    }

    public String worldName() {
        return this.worldName;
    }

    public IntegratedServer server() {
        return this.server;
    }

    public boolean isReady() {
        return this.server.isReady();
    }

    public boolean isShutdown() {
        return this.server.isShutdown();
    }

    public boolean isStopping() {
        return this.stopRequested;
    }

    public boolean shouldDeleteAfterStop() {
        return this.deleteAfterStop;
    }

    public void markLoadRequested() {
        this.loadRequested = true;
        ((FastResetManagedServer) this.server).fastreset$markLoaded();
    }

    public boolean isLoadRequested() {
        return this.loadRequested;
    }

    public void stop(boolean deleteAfterStop) {
        if (this.stopRequested) return;

        this.stopRequested = true;
        this.deleteAfterStop = deleteAfterStop;
        ((FastResetManagedServer) this.server).fastreset$markDiscarded();
        this.server.halt(false);
    }
}
