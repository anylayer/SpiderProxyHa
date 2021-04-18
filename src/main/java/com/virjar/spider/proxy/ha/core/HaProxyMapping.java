package com.virjar.spider.proxy.ha.core;

import com.virjar.spider.proxy.ha.handlers.ProxyProtocolRouter;
import com.virjar.spider.proxy.ha.safethread.ValueCallback;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HaProxyMapping {
    private static ServerBootstrap httpProxyBootstrap;
    @Getter
    private final Integer localMappingPort;
    @Getter
    private Upstream upstream;
    @Getter
    private final Source source;
    private Channel serverChannel;

    private static final AttributeKey<HaProxyMapping> proxyMappingKey = AttributeKey.newInstance("haProxyMapping");

    public HaProxyMapping(Integer localMappingPort, Upstream upstream, Source source) {
        this.localMappingPort = localMappingPort;
        this.upstream = upstream;
        this.source = source;
    }

    private void onProxyServerEstablish(Channel channel) {
        // 服务端的channel
        channel.attr(proxyMappingKey).set(this);
        serverChannel = channel;

        upstream.addDestroyListener(upstream -> source.requestRoute(value -> {
            if (value == null) {
                // 没有成功获得ip，关闭当前
                HaProxyMapping.this.doClose();
                return;
            }
            source.doUpstreamRoute(HaProxyMapping.this, HaProxyMapping.this.upstream, value);
            HaProxyMapping.this.upstream = value;
        }));

    }

    public static HaProxyMapping get(Channel channel) {
        if (channel == null) {
            return null;
        }
        HaProxyMapping haProxyMapping = channel.attr(proxyMappingKey).get();
        if (haProxyMapping != null) {
            return haProxyMapping;
        }
        return get(channel.parent());
    }


    public void startMapping() {
        httpProxyBootstrap.bind(localMappingPort)
                .addListener((ChannelFutureListener) channelFuture -> {
                    if (!channelFuture.isSuccess()) {
                        log.error("can not open proxy server on port:{}", localMappingPort, channelFuture.cause());
                        source.onMappingLose(HaProxyMapping.this);
                        return;
                    }

                    Channel channel = channelFuture.channel();
                    onProxyServerEstablish(channel);
                });
    }

    public static void staticInit() {
        httpProxyBootstrap = new ServerBootstrap();
        NioEventLoopGroup serverBossGroup = new NioEventLoopGroup(
                0,
                new DefaultThreadFactory("HttpProxy-boss-group" + DefaultThreadFactory.toPoolName(NioEventLoopGroup.class))
        );
        NioEventLoopGroup serverWorkerGroup = new NioEventLoopGroup(
                0,
                new DefaultThreadFactory("HttpProxy-worker-group" + DefaultThreadFactory.toPoolName(NioEventLoopGroup.class))
        );
        httpProxyBootstrap.group(serverBossGroup, serverWorkerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(
                                "idle",
                                new IdleStateHandler(0, 0, 70)
                        );
                        // 需要支持socks4/socks5/http/https
                        // 所以这里需要判定协议类型
                        pipeline.addLast(new ProxyProtocolRouter());
                    }
                });


    }


    public String resourceKey() {
        return upstream.resourceKey();
    }


    public void doClose() {
        serverChannel.close();
        source.onMappingLose(this);
    }


    public void borrowConnect(ValueCallback<Channel> valueCallback) {
        upstream.borrowConnect(value -> {
            if (value != null) {
                valueCallback.onReceiveValue(value);
                return;
            }
            // 只要有空闲代理资源，那么代理connect永远不会失败
            // 之后就是如何处理优化延时问题了
            failover(valueCallback, 0);
        });
    }

    private void failover(ValueCallback<Channel> valueCallback, int retry) {
        source.requestRoute(tmpUpstream -> {
            if (tmpUpstream == null) {
                log.warn("borrow failed and no available upstream resource");
                valueCallback.onReceiveValue(null);
                return;
            }
            tmpUpstream.borrowConnect(value -> {
                if (value != null) {
                    source.doUpstreamRoute(HaProxyMapping.this, HaProxyMapping.this.upstream, tmpUpstream);
                    HaProxyMapping.this.upstream = tmpUpstream;
                    valueCallback.onReceiveValue(value);
                    return;
                }

                // 连接都获取不要，要他何用
                tmpUpstream.doDestroy();

                if (retry > 3) {
                    log.warn("borrow failed with max failed retry :" + retry);
                    valueCallback.onReceiveValue(null);
                    return;
                }
                failover(valueCallback, retry + 1);
            });
        });
    }
}
