package com.virjar.spider.proxy.ha.core;

import com.google.common.base.Splitter;
import com.virjar.spider.proxy.ha.Configs;
import com.virjar.spider.proxy.ha.safethread.Looper;
import com.virjar.spider.proxy.ha.safethread.ValueCallback;
import com.virjar.spider.proxy.ha.utils.IPUtils;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.asynchttpclient.*;
import org.asynchttpclient.proxy.ProxyServer;

import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import static org.asynchttpclient.Dsl.asyncHttpClient;

/**
 * 数据源，通过特定url加载代理资源列表，HA资源路由过程发生在固定source下，不会发生source之间的资源飘逸
 */
@Slf4j
@RequiredArgsConstructor
public class Source {
    @NonNull
    @Getter
    private String name;
    @NonNull
    @Getter
    private String protocol;
    @NonNull
    private String sourceUrl;
    @NonNull
    private String mappingSpace;
    @Setter
    @Getter
    private String upstreamAuthUser;
    @Setter
    @Getter
    private String upstreamAuthPassword;

    private TreeSet<Integer> needBindPort = new TreeSet<>();
    private LinkedList<Upstream> availableUpstream = new LinkedList<>();

    @Getter
    private Looper looper;
    private boolean init = false;
    private static final AsyncHttpClient httpclient = asyncHttpClient(
            new DefaultAsyncHttpClientConfig.Builder()
                    .setKeepAlive(true)
                    .setConnectTimeout(15000)
                    .setReadTimeout(15000)
                    .setPooledConnectionIdleTimeout(40000)
                    .build());

    /**
     * 正向mapping
     */
    private ConcurrentHashMap<String, HaProxyMapping> mapping = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, HaProxyMapping> reverseMapping = new ConcurrentHashMap<>();


    private void doInit() {
        init = true;
        parseConfig();
        looper = new Looper("main-" + name);
    }

    public void refresh() {
        if (!init) {
            doInit();
        }
        httpclient.prepareGet(sourceUrl).execute().toCompletableFuture()
                .whenCompleteAsync((response, throwable) -> {
                    if (throwable != null) {
                        log.error("download resource failed:{}", sourceUrl, throwable);
                        return;
                    }
                    try {
                        handleResourceResponse(response);
                    } catch (Exception e) {
                        log.error("error", e);
                    }
                });

    }

    private void handleResourceResponse(Response response) {
        if (response.getStatusCode() != HttpResponseStatus.OK.code()) {
            log.error("error resource response url:{} response:{}", sourceUrl, response.getResponseBody(StandardCharsets.UTF_8));
            return;
        }
        String responseBody = response.getResponseBody(StandardCharsets.UTF_8);
        log.info("resource down response:{}", responseBody);
        //ip:port\nip:port\nip:port...
        for (String line : responseBody.split("\n")) {
            line = line.trim();
            // 已经被同步过的代理，不需要再次链接
            if (mapping.containsKey(line)) {
                continue;
            }
            if (!line.contains(":")) {
                continue;
            }
            testConnectForUpstream(line);
        }
    }

    private void testConnectForUpstream(String ipAndPort) {
        String[] split = ipAndPort.split(":");
        String host = split[0].trim();
        int port = NumberUtils.toInt(split[1].trim(), -1);
        if (port <= 0) {
            log.error("illegal proxy resource :{}", ipAndPort);
            return;
        }
        // 对于任意代理资源，发送代理请求，使他访问我们到代理接口，拿到真实ip，另外探测出真实的ip出口
        BoundRequestBuilder getBuilder = httpclient.prepareGet(Configs.proxyHttpTestURL);
        ProxyServer.Builder proxyBuilder = new ProxyServer.Builder(host, port);
        if (StringUtils.isNotBlank(upstreamAuthUser)) {
            proxyBuilder.setRealm(
                    new Realm.Builder(upstreamAuthUser, upstreamAuthPassword)
                            .setScheme(Realm.AuthScheme.BASIC));
        }
        getBuilder.setProxyServer(proxyBuilder);

        getBuilder.execute().toCompletableFuture().whenCompleteAsync((response, throwable) -> {
            if (throwable != null) {
                log.warn("test proxy failed:{}", ipAndPort);
                return;
            }
            try {
                onProxyResourceTestSuccess(host, port, response);
            } catch (Exception e) {
                log.error("error", e);
            }
        });
    }

    private void onProxyResourceTestSuccess(String proxyIp, int portPort, Response proxyResourceTestResponse) {
        String responseBody = proxyResourceTestResponse.getResponseBody(StandardCharsets.UTF_8).trim();
        if (!IPUtils.isIpV4(responseBody)) {
            log.warn("response not ip format:{}", responseBody);
            return;
        }
        if (reverseMapping.containsKey(responseBody)) {
            // 这个出口ip被映射过
            return;
        }
        looper.post(() -> handleUpstreamResource0(new Upstream(Source.this, proxyIp, portPort, responseBody)));
    }

    private void handleUpstreamResource0(Upstream upstreamHolder) {
        looper.checkLooper();
        upstreamHolder.addDestroyListener(upstream -> {
            // 有可能有误判，所以这里重新再探测下
            testConnectForUpstream(upstream.resourceKey());
        });
        if (needBindPort.isEmpty()) {
            // 所有出口都有映射，所以存起来，等有一些隧道断开之后再链接
            availableUpstream.addFirst(upstreamHolder);
            return;
        }

        Integer localMappingPort = needBindPort.pollFirst();

        HaProxyMapping haProxyMapping = new HaProxyMapping(localMappingPort, upstreamHolder, this);
        this.reverseMapping.put(upstreamHolder.getOutIp(), haProxyMapping);
        this.mapping.put(upstreamHolder.resourceKey(), haProxyMapping);
        haProxyMapping.startMapping();
    }

    private void parseConfig() {
        Iterable<String> pairs = Splitter.on(":").split(mappingSpace);
        for (String pair : pairs) {
            if (pair.contains("-")) {
                int index = pair.indexOf("-");
                String startStr = pair.substring(0, index);
                String endStr = pair.substring(index + 1);
                int start = Integer.parseInt(startStr);
                int end = Integer.parseInt(endStr);
                for (int i = start; i <= end; i++) {
                    needBindPort.add(i);
                }
            } else {
                needBindPort.add(Integer.parseInt(pair));
            }
        }
    }


    public void onMappingLose(HaProxyMapping haProxyMapping) {
        looper.post(() -> {
            needBindPort.add(haProxyMapping.getLocalMappingPort());
            mapping.remove(haProxyMapping.resourceKey());
            reverseMapping.remove(haProxyMapping.getUpstream().getOutIp());

        });
    }

    public boolean needAuth() {
        return StringUtils.isNotBlank(upstreamAuthUser);
    }

    /**
     * 请求一个新的代理ip资源
     */
    public void requestRoute(ValueCallback<Upstream> valueCallback) {
        looper.post(() -> {
            while (true) {
                Upstream poll = availableUpstream.poll();
                if (poll == null) {
                    valueCallback.onReceiveValue(null);
                    return;
                }
                if (!poll.isActive()) {
                    continue;
                }
                valueCallback.onReceiveValue(poll);
            }

        });
    }

    public void doUpstreamRoute(HaProxyMapping haProxyMapping, Upstream old, Upstream newUpstream) {
        if (!looper.inLooper()) {
            looper.post(() -> doUpstreamRoute(haProxyMapping, old, newUpstream));
            return;
        }
        mapping.remove(old.resourceKey());
        reverseMapping.remove(old.getOutIp());

        mapping.put(newUpstream.resourceKey(), haProxyMapping);
        reverseMapping.put(newUpstream.getOutIp(), haProxyMapping);

    }
}
