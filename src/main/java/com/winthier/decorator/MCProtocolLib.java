package com.winthier.decorator;

import com.github.steveice10.mc.protocol.MinecraftConstants;
import com.github.steveice10.mc.protocol.MinecraftProtocol;
import com.github.steveice10.mc.protocol.packet.ingame.server.ServerJoinGamePacket;
import com.github.steveice10.packetlib.Client;
import com.github.steveice10.packetlib.event.session.DisconnectedEvent;
import com.github.steveice10.packetlib.event.session.PacketReceivedEvent;
import com.github.steveice10.packetlib.event.session.SessionAdapter;
import com.github.steveice10.packetlib.tcp.TcpSessionFactory;
import java.net.Proxy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

final class MCProtocolLib {
    void spawnFakePlayer(JavaPlugin plugin, final String username) {
        MinecraftProtocol protocol = new MinecraftProtocol(username); // no password
        final String host = "localhost";
        final int port = Bukkit.getPort();
        plugin.getLogger().info("Spawn fake player: " + host + ":" + port);
        Client client = new Client(host, port, protocol, new TcpSessionFactory(null)); // no proxy
        client.getSession().setFlag(MinecraftConstants.AUTH_PROXY_KEY, Proxy.NO_PROXY);
        client.getSession().addListener(new SessionAdapter() {
            @Override
            public void packetReceived(PacketReceivedEvent event) {
                if (event.getPacket() instanceof ServerJoinGamePacket) {
                    plugin.getLogger().info(username + " just logged in");
                }
            }
            @Override
            public void disconnected(DisconnectedEvent event) {
                plugin.getLogger().info("Disconnected: " + event.getReason());
                if (event.getCause() != null) {
                    event.getCause().printStackTrace();
                }
            }
        });
        client.getSession().connect();
    }
}
