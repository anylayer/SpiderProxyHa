package com.virjar.spider.proxy.ha.utils;

import com.virjar.spider.proxy.ha.Constants;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;

import java.util.Collection;

public class NettyUtils {
    public static void closeChannelIfActive(Channel channel) {
        if (channel == null) {
            return;
        }
        if (!channel.isActive()) {
            return;
        }
        channel.writeAndFlush(Unpooled.EMPTY_BUFFER)
                .addListener(ChannelFutureListener.CLOSE);
    }

    public static void loveOther(Channel girl, Channel boy) {
        girl.closeFuture().addListener(future -> closeChannelIfActive(boy));
        boy.closeFuture().addListener(future -> closeChannelIfActive(girl));
    }

    public static void makePair(Channel girl, Channel boy) {
        girl.attr(Constants.NEXT_CHANNEL).set(boy);
        boy.attr(Constants.NEXT_CHANNEL).set(girl);
    }

    public static void closeAll(Collection<Channel> channels) {
        if (channels == null) {
            return;
        }
        for (Channel channel : channels) {
            closeChannelIfActive(channel);
        }
    }
}
