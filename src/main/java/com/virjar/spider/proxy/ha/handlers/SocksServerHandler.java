package com.virjar.spider.proxy.ha.handlers;

import com.virjar.spider.proxy.ha.core.HaProxyMapping;
import com.virjar.spider.proxy.ha.handlers.upstream.Socks5UpstreamHandShaker;
import com.virjar.spider.proxy.ha.handlers.upstream.UpstreamHandShaker;
import com.virjar.spider.proxy.ha.utils.NettyUtils;
import io.netty.channel.*;
import io.netty.handler.codec.socks.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SocksServerHandler extends SimpleChannelInboundHandler<SocksRequest> {
    private ChannelHandlerContext ctx;
    @Getter
    private Channel upstreamChannel;
    @Getter
    private HaProxyMapping haProxyMapping;

    private SocksCmdRequest req;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, SocksRequest socksRequest) throws Exception {
        this.ctx = ctx;
        switch (socksRequest.requestType()) {
            case INIT:
                // 第一版，先不做鉴权
                ctx.pipeline().addFirst(new SocksCmdRequestDecoder());
                ctx.writeAndFlush(new SocksInitResponse(SocksAuthScheme.NO_AUTH));
                break;
            case AUTH:
                ctx.pipeline().addFirst(new SocksCmdRequestDecoder());
                ctx.writeAndFlush(new SocksAuthResponse(SocksAuthStatus.SUCCESS));
                break;
            case CMD:
                handleCmd(ctx, socksRequest);
                break;
            case UNKNOWN:
                ctx.close();
                break;
        }

    }

    private void handleCmd(ChannelHandlerContext ctx, SocksRequest socksRequest) {
        req = (SocksCmdRequest) socksRequest;
        if (req.cmdType() != SocksCmdType.CONNECT) {
            ctx.close();
            return;
        }

        // 创建到 后端代理资源的链接
        haProxyMapping = HaProxyMapping.get(ctx.channel());

        haProxyMapping.borrowConnect(value -> {
            if (value == null) {
                log.warn("connect to proxy server failed:{} ", haProxyMapping.resourceKey());
                writeConnectFailed("");
                return;
            }
            upstreamChannel = value;
            new Socks5UpstreamHandShaker(upstreamChannel,
                    haProxyMapping.getSource(),
                    new UpstreamHandShaker.UpstreamHandSharkCallback() {
                        @Override
                        public void onHandSharkFailed(String message) {
                            writeConnectFailed(message);
                        }

                        @Override
                        public void onHandSharkSuccess() {
                            writeConnectSuccess();
                        }
                    },
                    (SocksCmdRequest) socksRequest
            ).doHandShark();
        });


    }

    private void writeConnectFailed(String message) {
        log.info("connect upstream failed {}", message);
        ctx.channel().writeAndFlush(
                new SocksCmdResponse(SocksCmdStatus.FAILURE, req.addressType()));
        NettyUtils.closeChannelIfActive(ctx.channel());
    }

    private void writeConnectSuccess() {

        NettyUtils.makePair(ctx.channel(), upstreamChannel);
        NettyUtils.loveOther(ctx.channel(), upstreamChannel);

        ctx.channel().writeAndFlush(new SocksCmdResponse(SocksCmdStatus.SUCCESS, req.addressType()))
                .addListener((ChannelFutureListener) channelFuture -> {
                    if (!channelFuture.isSuccess()) {
                        // 极小的可能，writeConnectSuccess的时候 用户端连接已经关闭了，
                        // 如果是这样的话，loveOther可能无效
                        NettyUtils.closeChannelIfActive(upstreamChannel);
                        return;
                    }
                    ChannelPipeline pipeline = ctx.channel().pipeline();
                    pipeline.remove(SocksMessageEncoder.class);
                    pipeline.remove(SocksServerHandler.class);


                    pipeline.addLast(new RelayHandler(upstreamChannel));
                    upstreamChannel.pipeline()
                            .addLast(new RelayHandler(ctx.channel()));
                });

    }


    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

}
