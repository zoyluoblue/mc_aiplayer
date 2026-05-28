package io.github.zoyluo.aibot.network;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.NetworkState;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.text.Text;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class FakeClientConnection extends ClientConnection {
    private static final SocketAddress FAKE_ADDRESS = new InetSocketAddress("127.0.0.1", 0);

    public FakeClientConnection(NetworkSide side) {
        super(side);
        ((ClientConnectionAccessor) this).aibot$setChannel(new EmbeddedChannel());
    }

    @Override
    public <T extends PacketListener> void transitionInbound(NetworkState<T> state, T packetListener) {
    }

    @Override
    public void transitionOutbound(NetworkState<?> state) {
    }

    @Override
    public void setInitialPacketListener(PacketListener packetListener) {
    }

    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, PacketCallbacks callbacks) {
        if (callbacks != null) {
            callbacks.onSuccess();
        }
    }

    @Override
    public void send(Packet<?> packet, PacketCallbacks callbacks, boolean flush) {
        if (callbacks != null) {
            callbacks.onSuccess();
        }
    }

    @Override
    public void disconnect(Text disconnectReason) {
    }

    @Override
    public void disconnect(DisconnectionInfo disconnectionInfo) {
    }

    @Override
    public void handleDisconnection() {
    }

    @Override
    public SocketAddress getAddress() {
        return FAKE_ADDRESS;
    }

    @Override
    public String getAddressAsString(boolean useSnooperSetting) {
        return "127.0.0.1";
    }
}
