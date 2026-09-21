package team.nekolla.netbridge.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;

import java.net.InetSocketAddress;

public interface ConnectionExecutorAdapter {

    EventLoopGroup eventLoopGroup();

    void initNativeChannel(Channel channel);

    ChannelFuture openTcp(InetSocketAddress address);

}
