package me.speedy.fastreset.access;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.Connection;

public interface FastResetMinecraftAccess {

    void fastreset$setSingleplayerServer(IntegratedServer server);

    void fastreset$setLocalServer(boolean localServer);

    void fastreset$setPendingConnection(Connection connection);
}
