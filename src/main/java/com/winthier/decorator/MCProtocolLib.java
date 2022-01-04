package com.winthier.decorator;

import com.github.steveice10.mc.protocol.MinecraftProtocol;
import com.github.steveice10.packetlib.event.session.ConnectedEvent;
import com.github.steveice10.packetlib.event.session.DisconnectedEvent;
import com.github.steveice10.packetlib.event.session.SessionAdapter;
import com.github.steveice10.packetlib.tcp.TcpClientSession;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

final class MCProtocolLib {
    void spawnFakePlayer(JavaPlugin plugin, final String username) {
        MinecraftProtocol protocol = new MinecraftProtocol(username); // no password
        final String host = "localhost";
        final int port = Bukkit.getPort();
        plugin.getLogger().info("Spawn fake player: " + host + ":" + port);
        TcpClientSession client = new TcpClientSession(host, port, protocol); // no proxy
        client.addListener(new SessionAdapter() {
            @Override
            public void connected(ConnectedEvent event) {
                plugin.getLogger().info(username + " just logged in");
            }
            @Override
            public void disconnected(DisconnectedEvent event) {
                plugin.getLogger().info("Disconnected: " + event.getReason());
                if (event.getCause() != null) {
                    event.getCause().printStackTrace();
                }
            }
        });
        client.connect();
    }
}
