package com.virjar.spider.proxy.ha.handlers.upstream;

import com.virjar.spider.proxy.ha.core.Source;
import com.virjar.spider.proxy.ha.utils.HttpNettyUtils;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HttpUpstreamHandShaker extends UpstreamHandShaker<HttpRequest> {
    public HttpUpstreamHandShaker(Channel upstreamChannel, Source source, UpstreamHandSharkCallback upstreamHandSharkCallback, HttpRequest httpRequest) {
        super(upstreamChannel, source, upstreamHandSharkCallback, httpRequest);
    }

    @Override
    public void doHandShark() {
        HttpNettyUtils.fillAuthenticationInfo(source, originRequest);
        // http协议下，只需要创建了到代理服务器的链接，那么就认为协议握手完成
        upstreamHandSharkCallback.onHandSharkSuccess();
    }
}
