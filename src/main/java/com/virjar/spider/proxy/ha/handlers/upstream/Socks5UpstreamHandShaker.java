package com.virjar.spider.proxy.ha.handlers.upstream;

import com.google.common.collect.Lists;
import com.virjar.spider.proxy.ha.core.Source;
import io.netty.channel.*;
import io.netty.handler.codec.socks.*;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class Socks5UpstreamHandShaker extends UpstreamHandShaker<SocksCmdRequest> {
    public Socks5UpstreamHandShaker(Channel upstreamChannel, Source source,
                                    UpstreamHandSharkCallback upstreamHandSharkCallback,
                                    SocksCmdRequest socksCmdRequest) {
        super(upstreamChannel, source, upstreamHandSharkCallback, socksCmdRequest);
    }


    private void handleUpstreamSocksResponse(ChannelHandlerContext ctx, SocksResponse msg) {
        switch (msg.responseType()) {
            case INIT:
                handleInitResponse((SocksInitResponse) msg);
                break;
            case AUTH:
                handleAuthResponse((SocksAuthResponse) msg);
                break;
            case CMD:
                handleCmdResponse((SocksCmdResponse) msg);
                break;
            default:
                upstreamHandSharkCallback.onHandSharkFailed("error upstream protocol");

        }
    }

    private void handleCmdResponse(SocksCmdResponse socksCmdResponse) {
        if (socksCmdResponse.cmdStatus() == SocksCmdStatus.SUCCESS) {
            ChannelPipeline pipeline = upstreamChannel.pipeline();
            // 建立了透明隧道，此时移除所有编解码，之后虽然可以负载其他任意TCP协议了
            pipeline.remove(SocksMessageEncoder.class);
            pipeline.remove(SocksUpstreamHandSharkHandler.class);
            upstreamHandSharkCallback.onHandSharkSuccess();
            return;
        }
        // if(socksCmdResponse.addressType()!=SocksAddressType.IPv4){}
        upstreamHandSharkCallback.onHandSharkFailed("connect failed:" + socksCmdResponse.cmdStatus());
    }

    private void handleInitResponse(SocksInitResponse socksInitResponse) {
        SocksAuthScheme socksAuthScheme = socksInitResponse.authScheme();
        switch (socksAuthScheme) {
            case NO_AUTH:
                //服务器不要密码
                doSocksConnectImpl();
                break;
            case AUTH_PASSWORD:
                doPasswordAuth();
                break;
            default:
                // 不支持的鉴权方式，目前要么不需要鉴权、要么只支持密码鉴权
                upstreamHandSharkCallback.onHandSharkFailed("none support auth scheme:" + socksAuthScheme);

        }
    }

    private void handleAuthResponse(SocksAuthResponse socksAuthResponse) {
        if (socksAuthResponse.authStatus() == SocksAuthStatus.SUCCESS) {
            doSocksConnectImpl();
            return;
        }
        upstreamHandSharkCallback.onHandSharkFailed("upstream auth failed");
    }

    private void doSocksConnectImpl() {
        upstreamChannel.pipeline().addFirst(new SocksCmdResponseDecoder());

        upstreamChannel.writeAndFlush(originRequest)
                .addListener(future -> {
                    if (!future.isSuccess()) {
                        upstreamHandSharkCallback.onHandSharkFailed("broken upstream channel");
                    }
                });

    }

    private void doPasswordAuth() {
        if (!source.needAuth()) {
            log.error("the upstream server need auth ,but no auth configured for source:{}", source.getName());
            upstreamHandSharkCallback.onHandSharkFailed("upstream need auth ,but no auth info configured");
            return;
        }

        upstreamChannel.pipeline().addFirst(new SocksAuthResponseDecoder());
        SocksAuthRequest socksAuthRequest = new SocksAuthRequest(source.getUpstreamAuthUser(),
                source.getUpstreamAuthPassword());
        upstreamChannel.writeAndFlush(socksAuthRequest)
                .addListener(future -> {
                    if (!future.isSuccess()) {
                        upstreamHandSharkCallback.onHandSharkFailed("upstream broken");
                    }
                });
    }

    @Override
    public void doHandShark() {
        upstreamChannel.pipeline().addLast(new SocksMessageEncoder());
        List<SocksAuthScheme> socksAuthSchemes = Lists.newLinkedList();
        socksAuthSchemes.add(SocksAuthScheme.NO_AUTH);
        if (source.needAuth()) {
            socksAuthSchemes.add(SocksAuthScheme.AUTH_PASSWORD);
        }
        SocksInitRequest socksInitRequest = new SocksInitRequest(socksAuthSchemes);
        upstreamChannel.writeAndFlush(socksInitRequest)
                .addListener((ChannelFutureListener) channelFuture -> {
                    if (!channelFuture.isSuccess()) {
                        upstreamHandSharkCallback.onHandSharkFailed(channelFuture.cause().getMessage());
                        return;
                    }
                    ChannelPipeline pipeline = upstreamChannel.pipeline();
                    pipeline.addFirst(new SocksInitResponseDecoder());
                    pipeline.addLast(new SocksUpstreamHandSharkHandler());
                });
    }

    private class SocksUpstreamHandSharkHandler extends SimpleChannelInboundHandler<SocksResponse> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, SocksResponse msg) throws Exception {
            handleUpstreamSocksResponse(ctx, msg);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            upstreamHandSharkCallback.onHandSharkFailed(cause.getMessage());
            super.exceptionCaught(ctx, cause);
        }
    }


}
