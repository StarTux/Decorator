package com.winthier.decorator;

import com.github.steveice10.mc.protocol.MinecraftConstants;
import com.github.steveice10.mc.protocol.MinecraftProtocol;
import com.github.steveice10.mc.protocol.data.message.Message;
import com.github.steveice10.mc.protocol.packet.ingame.client.ClientChatPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.ServerJoinGamePacket;
import com.github.steveice10.packetlib.Client;
import com.github.steveice10.packetlib.event.session.DisconnectedEvent;
import com.github.steveice10.packetlib.event.session.PacketReceivedEvent;
import com.github.steveice10.packetlib.event.session.SessionAdapter;
import com.github.steveice10.packetlib.tcp.TcpSessionFactory;
import java.net.Proxy;
import org.bukkit.Bukkit;

final class MCProtocolLib {
    void spawnFakePlayer(final String username) {
        MinecraftProtocol protocol = new MinecraftProtocol(username);
        Client client = new Client("localhost",
                                   Bukkit.getPort(),
                                   protocol,
                                   new TcpSessionFactory(null));
        client.getSession().setFlag(MinecraftConstants.AUTH_PROXY_KEY, Proxy.NO_PROXY);
        client.getSession().addListener(new SessionAdapter() {
            @Override
            public void packetReceived(PacketReceivedEvent event) {
                if (event.getPacket() instanceof ServerJoinGamePacket) {
                    event.getSession().send(new ClientChatPacket(username + " says hello."));
                }
            }
            @Override
            public void disconnected(DisconnectedEvent event) {
                // System.out.println("Disconnected: "
                //                    + Message.fromString(event.getReason()).getFullText());
                if (event.getCause() != null) {
                    event.getCause().printStackTrace();
                }
            }
        });
        client.getSession().connect();
    }
}
