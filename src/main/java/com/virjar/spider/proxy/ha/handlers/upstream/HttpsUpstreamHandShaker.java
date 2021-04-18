package com.virjar.spider.proxy.ha.handlers.upstream;

import com.virjar.spider.proxy.ha.core.Source;
import com.virjar.spider.proxy.ha.utils.HttpNettyUtils;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCounted;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HttpsUpstreamHandShaker extends UpstreamHandShaker<HttpRequest> {
    private boolean hasSendAuthentication = false;

    public HttpsUpstreamHandShaker(Channel upstreamChannel, Source source, UpstreamHandSharkCallback upstreamHandSharkCallback, HttpRequest httpRequest) {
        super(upstreamChannel, source, upstreamHandSharkCallback, httpRequest);
    }

    @Override
    public void doHandShark() {
        ChannelPipeline pipeline = upstreamChannel.pipeline();
        pipeline.addFirst(new HttpRequestEncoder());
        pipeline.addFirst(new HttpResponseDecoder());

        if (originRequest instanceof ReferenceCounted) {
            ((ReferenceCounted) originRequest).retain();
        }

        HttpNettyUtils.fillAuthenticationInfo(source, originRequest);

        upstreamChannel.writeAndFlush(originRequest)
                .addListener(future -> {
                    if (!future.isSuccess()) {
                        upstreamHandSharkCallback.onHandSharkFailed(future.cause().getMessage());
                    }
                    upstreamChannel.pipeline().addLast(new ConnectResponseHandler());
                });

    }

    private void handleConnectHttpResponse(HttpResponse msg) {
        int code = msg.getStatus().code();
        if (code == HttpResponseStatus.OK.code()) {
            ChannelPipeline pipeline = upstreamChannel.pipeline();
            pipeline.remove(HttpRequestEncoder.class);
            pipeline.remove(HttpResponseDecoder.class);
            pipeline.remove(ConnectResponseHandler.class);
            upstreamHandSharkCallback.onHandSharkSuccess();
            return;
        }
        if (code == HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED.code()) {
            if (hasSendAuthentication) {
                log.warn("upstream password error");
                upstreamHandSharkCallback.onHandSharkFailed("upstream password error");
                return;
            }

            // 有部分代理服务器标准实现不合理，首次发送了代理鉴权内容但是他不认，依然返回407
            // 然后java okhttpclient 网络库一旦首次发送过密码，即使407也不会重新发送带鉴权请求，而是直接报告失败
            // 所以我们这里屏蔽这个问题，如果407我们再发送一次报文。

            HttpRequest httpRequest = HttpNettyUtils.copyHttpRequest(originRequest);
            HttpNettyUtils.fillAuthenticationInfo(source, httpRequest);
            upstreamChannel.writeAndFlush(httpRequest)
                    .addListener(future -> {
                        if (!future.isSuccess()) {
                            upstreamHandSharkCallback.onHandSharkFailed(future.cause().getMessage());
                        }
                        hasSendAuthentication = true;
                    });
            return;


        }
        upstreamHandSharkCallback.onHandSharkFailed(msg.getStatus().reasonPhrase());
    }


    private class ConnectResponseHandler extends SimpleChannelInboundHandler<HttpResponse> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpResponse msg) throws Exception {
            handleConnectHttpResponse(msg);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            upstreamHandSharkCallback.onHandSharkFailed(cause.getMessage());
            super.exceptionCaught(ctx, cause);
        }
    }
}
