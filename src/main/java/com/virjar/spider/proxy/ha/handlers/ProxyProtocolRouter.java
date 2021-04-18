package com.virjar.spider.proxy.ha.handlers;

import com.virjar.spider.proxy.ha.core.HaProxyMapping;
import com.virjar.spider.proxy.ha.core.Source;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.socks.SocksInitRequestDecoder;
import io.netty.handler.codec.socks.SocksMessageEncoder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class ProxyProtocolRouter extends ChannelInboundHandlerAdapter {

    /**
     * SOCKS protocol version 5
     */
    private static final byte SOCKS5_VERSION = (byte) 0x05;
    private static final byte SOCKS4_VERSION = (byte) 0x04;

    private static final int MAX_INITIAL_LINE_LENGTH_DEFAULT = 8192;
    private static final int MAX_HEADER_SIZE_DEFAULT = 8192 * 2;
    private static final int MAX_CHUNK_SIZE_DEFAULT = 8192 * 2;

    /**
     * socks5的头部只有3个字节
     */
    private static final int ROUTER_MAGIC_HEADER_LENGTH = 3;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf)) {
            log.error(ProxyProtocolRouter.class.getName() + ": can only handle ByteBuf message");
            ctx.close();
            return;
        }

        ByteBuf byteBuf = (ByteBuf) msg;
        if (byteBuf.readableBytes() < ROUTER_MAGIC_HEADER_LENGTH) {
            // not enough data
            return;
        }

        byte firstByte = ((ByteBuf) msg).getByte(0);
        byte[] readBuf = new byte[ROUTER_MAGIC_HEADER_LENGTH];
        ((ByteBuf) msg).getBytes(0, readBuf);

        ChannelPipeline pipeline = ctx.channel().pipeline();
        // 处理http协议的编解码
        if (maybeHttpRequest(readBuf)) {
            route2Http(pipeline);
        } else if (firstByte == SOCKS5_VERSION) {
            route2Socks5(pipeline);
        } else if (firstByte == SOCKS4_VERSION) {
            route2Socks4(pipeline);
        } else {
            // this
            ctx.close();
            return;
        }

        pipeline.remove(this);
        super.channelRead(ctx, msg);
    }

    private void route2Socks4(ChannelPipeline pipeline) {
        if (!checkSupport(pipeline.channel(), "socks4")) {
            // 当前channel不支持socks5
            pipeline.close();
            return;
        }
        log.info("not support SOCKS4 proxy");
        pipeline.channel().close();
    }


    private void route2Socks5(ChannelPipeline pipeline) {
        if (!checkSupport(pipeline.channel(), "socks5")) {
            // 当前channel不支持socks5
            pipeline.close();
            return;
        }

        pipeline.addLast(new SocksInitRequestDecoder());
        pipeline.addLast(new SocksMessageEncoder());
        pipeline.addLast(new SocksServerHandler());
    }

    private void route2Http(ChannelPipeline pipeline) {
        //todo http/https的区分需要后置
        if (!checkSupport(pipeline.channel(), "http")
                && !checkSupport(pipeline.channel(), "https")
        ) {
            // 当前channel不支持socks5
            pipeline.close();
            return;
        }

        pipeline.addLast(new HttpResponseEncoder());
        pipeline.addLast(new HttpRequestDecoder(
                MAX_INITIAL_LINE_LENGTH_DEFAULT,
                MAX_HEADER_SIZE_DEFAULT,
                MAX_CHUNK_SIZE_DEFAULT));
        pipeline.addLast(new HttpServerHandler());
    }

    private boolean checkSupport(Channel channel, String protocolName) {
        return HaProxyMapping.get(channel).getSource().getProtocol().contains(protocolName);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
        cause.printStackTrace();
    }


    enum Method {
        GET,
        PUT,
        POST,
        DELETE,
        HEAD,
        OPTIONS,
        TRACE,
        CONNECT,
        PATCH,
        PROPFIND,
        PROPPATCH,
        MKCOL,
        MOVE,
        COPY,
        LOCK,
        UNLOCK
    }

    private static class Trie {
        private final Map<Byte, Trie> values = new HashMap<>();
        private String method = null;

        void addToTree(byte[] data, int index, String worldEntry) {
            if (index >= data.length) {
                //the last
                if (this.method == null) {
                    this.method = worldEntry;
                }
                return;
            }
            Trie trie = values.get(data[index]);
            if (trie == null) {
                trie = new Trie();
                values.put(data[index], trie);
            }
            trie.addToTree(data, index + 1, worldEntry);
        }

        String find(byte[] dictTable, int index) {
            if (index >= dictTable.length) {
                return this.method;
            }

            Trie trie = values.get(dictTable[index]);
            if (trie == null) {
                return this.method;
            }
            return trie.find(dictTable, index + 1);
        }

    }

    private static final Trie methodCharacterTrie = new Trie();


    static {
        for (Method method : Method.values()) {
            byte[] name = method.name().getBytes();
            if (name.length > ROUTER_MAGIC_HEADER_LENGTH) {
                byte[] buf = new byte[ROUTER_MAGIC_HEADER_LENGTH];
                System.arraycopy(name, 0, buf, 0, ROUTER_MAGIC_HEADER_LENGTH);
                name = buf;
            }
            methodCharacterTrie.addToTree(name, 0, method.name());
        }
    }


    // 判断是否为HTTP 协议请求头
    private static boolean maybeHttpRequest(byte[] data) {
        return StringUtils.isNotBlank(methodCharacterTrie.find(data, 0));
    }
}
