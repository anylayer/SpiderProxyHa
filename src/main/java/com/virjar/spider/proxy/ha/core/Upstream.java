package com.virjar.spider.proxy.ha.core;

import com.virjar.spider.proxy.ha.Configs;
import com.virjar.spider.proxy.ha.safethread.ValueCallback;
import com.virjar.spider.proxy.ha.utils.NettyUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.internal.ConcurrentSet;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个上游资源，一个特定的代理ip资源。我们的HA系统就是将代理请求转发到这些代理ip上面
 * <br>
 * 需要注意，不是所有代理都会mapping到服务器（也不应该全部mapping），因为当已经mapping的
 * 代理资源无法使用的时候，需要有未使用的Upstream资源进行软切换
 */
@Slf4j
public class Upstream {
    private final Source source;
    @Getter
    private final String upstreamHost;
    @Getter
    private final Integer upstreamPort;
    @Getter
    private final String outIp;


    /**
     * 连接缓存，和真的代理服务器保持的连接，他是提前创建的连接用于代理转发加速
     */
    private final LinkedList<Channel> channelCache = new LinkedList<>();

    /**
     * 当前正在使用的连接资源，用于监控上游代理服务器是否掉线
     */
    private final Set<Channel> usedChannels = new HashSet<>();


    private long createTimestamp;

    private final Set<UpstreamDestroyEvent> destroyCallbacks = new ConcurrentSet<>();
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private static final NioEventLoopGroup upstreamConnectionGroup = new NioEventLoopGroup(
            0,
            new DefaultThreadFactory("upstream-group-" + DefaultThreadFactory.toPoolName(NioEventLoopGroup.class))
            //, NioUdtProvider.BYTE_PROVIDER
    );

    private Bootstrap upstreamBootstrap;

    private AtomicInteger connectFailedCount = new AtomicInteger(0);


    Upstream(Source source, String upstreamHost, Integer upstreamPort, String outIp) {
        this.source = source;
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        this.outIp = outIp;
        init();
    }

    public String resourceKey() {
        return upstreamHost + ":" + upstreamPort;
    }

    public void addDestroyListener(UpstreamDestroyEvent upstreamDestroyEvent) {
        this.destroyCallbacks.add(upstreamDestroyEvent);
    }

    private void init() {
        createTimestamp = System.currentTimeMillis();
        upstreamBootstrap = new Bootstrap()
                .group(upstreamConnectionGroup)
                .channelFactory(NioSocketChannel::new)
                .handler(new MonitorHandler());
        upstreamBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout);
        scheduleCreateCacheConnection();
    }

    private void safeDo(Runnable runnable) {
        source.getLooper().post(runnable);
    }

    public void borrowConnect(ValueCallback<Channel> valueCallback) {
        safeDo(() -> borrowConnect0(value -> {
            if (value == null) {
                if (connectFailedCount.incrementAndGet() > 3) {
                    doDestroy();
                    source.getLooper().postDelay(() -> {
                        // 代理服务器的代理服务关闭，只是server socket关闭，无法接受请求
                        // 但是已经创建完成的连接还是可以工作，此时upstream存在alive的连接
                        // 可以认为此时代理ip处于可用和可用的中间态（可用指：当前处理的channel可能仍然在传输数据，所以不能关闭。不可用指：新的代理连接已经无法创建）
                        // 此时我们的策略：马上进行upstream路由，切换到其他代理ip源上。60s后关闭上面的所有连接，避免泄漏
                        NettyUtils.closeAll(usedChannels);
                    }, 60 * 1000);

                }
                valueCallback.onReceiveValue(null);
                return;
            }
            usedChannels.add(value);
            value.eventLoop().execute(() -> {
                if (!value.isActive()) {
                    borrowConnect(valueCallback);
                } else {
                    connectFailedCount.set(0);
                    value.attr(IS_IDLE_CONNECTION).set(false);
                    valueCallback.onReceiveValue(value);
                }
            });

        }));
    }

    private void borrowConnect0(ValueCallback<Channel> valueCallback) {
        source.getLooper().checkLooper();
        while (true) {
            Channel one = channelCache.poll();
            if (one == null) {
                break;
            }
            if (one.isActive()) {
                valueCallback.onReceiveValue(one);
                return;
            }
        }

        createUpStreamImpl().addListener((ChannelFutureListener) channelFuture -> {
            if (channelFuture.isSuccess()) {
                safeDo(() -> valueCallback.onReceiveValue(channelFuture.channel()));
                return;
            }
            valueCallback.onReceiveValue(null);
        });
    }

    private final AtomicInteger connectionCacheTaskSize = new AtomicInteger(0);

    private ChannelFuture createUpStreamImpl() {
        ChannelFuture future = upstreamBootstrap.connect(upstreamHost, upstreamPort);
        DefaultChannelPromise promise = new DefaultChannelPromise(future.channel());
        future.addListener((ChannelFutureListener) channelFuture -> {
            if (channelFuture.isSuccess()) {
                promise.setSuccess();
                channelFuture.channel().closeFuture().addListener((ChannelFutureListener) channelFuture1 -> onUpstreamConnectionClose(channelFuture1.channel()));
            } else {
                promise.setFailure(channelFuture.cause());
            }
        });
        return promise;
    }

    private void scheduleCreateCacheConnection() {
        if (!isActive()) {
            return;
        }

        for (int i = channelCache.size(); i < Configs.cacheConnPerUpstream; i++) {
            if (!createCacheConnection()) {
                break;
            }
        }
    }

    private boolean createCacheConnection() {
        if (connectionCacheTaskSize.getAndIncrement() > Configs.cacheConnPerUpstream) {
            connectionCacheTaskSize.decrementAndGet();
            return false;
        }
        createUpStreamImpl().addListener((ChannelFutureListener) channelFuture -> {
            connectionCacheTaskSize.decrementAndGet();
            if (!channelFuture.isSuccess()) {
                // 缓存请求，理论上不应该失败，如果失败了进行一次检测
                destroyIfDetected();
                return;
            }
            channelFuture.channel().attr(IS_IDLE_CONNECTION).set(true);

            safeDo(() -> {
                Channel upstreamChannel = channelFuture.channel();
                // 这里，优先使用最近产生的连接
                channelCache.addFirst(upstreamChannel);
                upstreamChannel.eventLoop().schedule(() -> {
                            // 超时一直没有使用，那么把他销毁
                            if (upstreamChannel.isActive()) {
                                upstreamChannel.attr(IS_IDLE_NORMAL_CLOSE).set(true);
                                upstreamChannel.close();
                                log.info("destroy unused channel");
                            }
                        },
                        Configs.cacheConnAliveSeconds + Math.abs((int) (Configs.cacheConnAliveSeconds * ThreadLocalRandom.current().nextGaussian())),
                        TimeUnit.SECONDS);
            });
        });
        return true;
    }

    // 标记这个链接是idle链接，一旦链接被使用了，那么他会切换为非idle态
    private static final AttributeKey<Boolean> IS_IDLE_CONNECTION = AttributeKey.newInstance("IS_IDLE_CONNECTION");
    // 标记链接是idle超时后主动关闭，主动关闭的资源不参与销毁判定
    private static final AttributeKey<Boolean> IS_IDLE_NORMAL_CLOSE = AttributeKey.newInstance("IS_IDLE_NORMAL_CLOSE");

    private void onUpstreamConnectionClose(Channel channel) {
        safeDo(() -> {
            usedChannels.remove(channel);
            boolean isIdleChannel = BooleanUtils.isTrue(channel.attr(IS_IDLE_CONNECTION).get());
            boolean isIdleNormalClose = BooleanUtils.isTrue(channel.attr(IS_IDLE_NORMAL_CLOSE).get());
            if (isActive()) {
                if (isIdleChannel && !isIdleNormalClose) {
                    // idle链接，如果是服务器主动关闭的，那么认为可能掉线
                    // 如果是我们自己认为idle太久关闭的，那么我们忽略他
                    destroyIfDetected();
                }
                scheduleCreateCacheConnection();
            }


        });
    }

    private void destroyIfDetected() {
        if (!source.getLooper().inLooper()) {
            safeDo(this::destroyIfDetected);
            return;
        }
        if (!usedChannels.isEmpty()) {
            return;
        }
        boolean canDestroy = true;
        while (!channelCache.isEmpty()) {
            Channel peek = channelCache.peek();
            if (!peek.isActive()) {
                channelCache.removeFirst();
                continue;
            }
            canDestroy = false;
            break;
        }
        if (!canDestroy) {
            return;
        }
        doDestroy();
    }

    public void doDestroy() {
        if (destroyed.compareAndSet(false, true)) {
            for (UpstreamDestroyEvent upstreamDestroyEvent : this.destroyCallbacks) {
                upstreamDestroyEvent.onDestroy(this);
            }
        }

    }


    public boolean isActive() {
        return !destroyed.get();
    }


    public interface UpstreamDestroyEvent {
        void onDestroy(Upstream upstream);
    }

    private static final int connectTimeout = 10000;


    @ChannelHandler.Sharable
    private class MonitorHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            super.handlerAdded(ctx);
            //todo
            //  ctx.pipeline().remove(this);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            super.channelRead(ctx, msg);
            // 这里监控读写情况，可以用于判定读写超时问题
            // todo 也可以修改为 idle
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            // ignore
            if (BooleanUtils.isTrue(ctx.channel().attr(IS_IDLE_CONNECTION).get())) {
                // 空转连接被关闭，不打印日志
                ctx.close();
                return;
            }
            super.exceptionCaught(ctx, cause);
        }
    }
}
