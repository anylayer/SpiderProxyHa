package com.virjar.spider.proxy.ha.handlers;


import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;

/**
 * @author lei.X
 * @date 2021/1/30
 */

public final class RelayHandler extends ChannelInboundHandlerAdapter {

    private final Channel nextChannel;

    public RelayHandler(Channel relayChannel) {
        this.nextChannel = relayChannel;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
    }


    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (nextChannel.isActive()) {
            if (msg instanceof ReferenceCounted) {
                ((ReferenceCounted) msg).retain();
            }
            nextChannel.writeAndFlush(msg);
        } else {
            ReferenceCountUtil.release(msg);
        }
    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        //cause.printStackTrace();
        ctx.close();
    }
}

