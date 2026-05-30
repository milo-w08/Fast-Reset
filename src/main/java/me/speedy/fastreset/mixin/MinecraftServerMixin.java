package me.speedy.fastreset.mixin;

import me.speedy.fastreset.access.FastResetManagedServer;
import me.speedy.fastreset.FastResetLogic;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin implements FastResetManagedServer {

    @Unique
    private volatile String fastreset$worldName;

    @Unique
    private volatile boolean fastreset$queued;

    @Unique
    private volatile boolean fastreset$loaded;

    @Unique
    private volatile boolean fastreset$discarded;

    @Unique
    private volatile boolean fastreset$paused;

    @Inject(
            method = "stopServer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;saveAllChunks(ZZZ)Z"
            )
    )
    private void skipRejectedCandidateSave(CallbackInfo ci) {
        FastResetLogic.prepareServerForRejectedCandidateShutdown((MinecraftServer)(Object)this);
    }

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void pauseFastResetQueuedServer(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        this.fastreset$tryPause();
    }

    @Override
    public void fastreset$markQueued(String worldName) {
        this.fastreset$worldName = worldName;
        this.fastreset$queued = true;
        this.fastreset$loaded = false;
        this.fastreset$discarded = false;
    }

    @Override
    public synchronized void fastreset$markLoaded() {
        this.fastreset$loaded = true;
        this.notifyAll();
    }

    @Override
    public synchronized void fastreset$markDiscarded() {
        this.fastreset$discarded = true;
        this.notifyAll();
    }

    @Override
    public String fastreset$getWorldName() {
        return this.fastreset$worldName;
    }

    @Unique
    private boolean fastreset$shouldPause() {
        MinecraftServer server = (MinecraftServer) (Object) this;
        return this.fastreset$queued
                && !this.fastreset$loaded
                && !this.fastreset$discarded
                && server.isReady()
                && !server.isStopped();
    }

    @Unique
    private void fastreset$tryPause() {
        if (!this.fastreset$shouldPause()) return;

        synchronized (this) {
            while (this.fastreset$shouldPause()) {
                try {
                    this.fastreset$paused = true;
                    this.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } finally {
                    this.fastreset$paused = false;
                }
            }
        }
    }
}
