package me.speedy.fastreset.access;

public interface FastResetManagedServer {

    void fastreset$markQueued(String worldName);

    void fastreset$markLoaded();

    void fastreset$markDiscarded();

    String fastreset$getWorldName();
}
