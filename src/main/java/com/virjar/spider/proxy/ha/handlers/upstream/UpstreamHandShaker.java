package com.virjar.spider.proxy.ha.handlers.upstream;

import com.virjar.spider.proxy.ha.core.Source;
import io.netty.channel.Channel;

public abstract class UpstreamHandShaker<T> {
    protected Channel upstreamChannel;
    protected Source source;
    protected UpstreamHandSharkCallback upstreamHandSharkCallback;
    protected T originRequest;

    public UpstreamHandShaker(Channel upstreamChannel, Source source,
                              UpstreamHandSharkCallback upstreamHandSharkCallback,
                              T originRequest) {
        this.upstreamChannel = upstreamChannel;
        this.source = source;
        this.upstreamHandSharkCallback = upstreamHandSharkCallback;
        this.originRequest = originRequest;
    }

    public interface UpstreamHandSharkCallback {
        void onHandSharkFailed(String message);

        void onHandSharkSuccess();
    }

    public abstract void doHandShark();

}
