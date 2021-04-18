package com.virjar.spider.proxy.ha.utils;

import com.google.common.io.BaseEncoding;
import com.virjar.spider.proxy.ha.core.Source;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.*;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class HttpNettyUtils {

    /**
     * Creates a new {@link FullHttpResponse} with the specified String as the body contents (encoded using UTF-8).
     *
     * @param httpVersion HTTP version of the response
     * @param status      HTTP status code
     * @param body        body to include in the FullHttpResponse; will be UTF-8 encoded
     * @return new http response object
     */
    public static FullHttpResponse createFullHttpResponse(HttpVersion httpVersion,
                                                          HttpResponseStatus status,
                                                          String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ByteBuf content = Unpooled.copiedBuffer(bytes);

        return createFullHttpResponse(httpVersion, status, "text/html; charset=utf-8", content, bytes.length);
    }

    /**
     * Creates a new {@link FullHttpResponse} with no body content
     *
     * @param httpVersion HTTP version of the response
     * @param status      HTTP status code
     * @return new http response object
     */
    public static FullHttpResponse createFullHttpResponse(HttpVersion httpVersion,
                                                          HttpResponseStatus status) {
        return createFullHttpResponse(httpVersion, status, null, null, 0);
    }

    /**
     * Creates a new { FullHttpResponse} with the specified body.
     *
     * @param httpVersion   HTTP version of the response
     * @param status        HTTP status code
     * @param contentType   the Content-Type of the body
     * @param body          body to include in the FullHttpResponse; if null
     * @param contentLength number of bytes to send in the Content-Length header; should equal the number of bytes in the ByteBuf
     * @return new http response object
     */
    public static FullHttpResponse createFullHttpResponse(HttpVersion httpVersion,
                                                          HttpResponseStatus status,
                                                          String contentType,
                                                          ByteBuf body,
                                                          int contentLength) {
        DefaultFullHttpResponse response;

        if (body != null) {
            response = new DefaultFullHttpResponse(httpVersion, status, body);
            response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, contentLength);
            response.headers().set(HttpHeaders.Names.CONTENT_TYPE, contentType);
        } else {
            response = new DefaultFullHttpResponse(httpVersion, status);
        }

        return response;
    }

    public static boolean respondWithShortCircuitResponse(Channel httpRequestChannel, HttpResponse httpResponse) {
// allow short-circuit messages to close the connection. normally the Connection header would be stripped when modifying
        // the message for proxying, so save the keep-alive status before the modifications are made.
        boolean isKeepAlive = HttpHeaders.isKeepAlive(httpResponse);

        // restore the keep alive status, if it was overwritten when modifying headers for proxying
        HttpHeaders.setKeepAlive(httpResponse, isKeepAlive);

        ChannelFuture channelFuture = httpRequestChannel.writeAndFlush(httpResponse);

        if (isLastChunk(httpResponse)) {
            writeEmptyBuffer(httpRequestChannel);
        }

        if (!HttpHeaders.isKeepAlive(httpResponse)) {
            channelFuture.addListener(future -> httpRequestChannel.close());
            return false;
        }
        return true;

    }

    public static boolean writeBadRequest(Channel httpRequestChannel, HttpRequest httpRequest) {
        String body = "Bad Request to URI: " + httpRequest.getUri().trim();
        FullHttpResponse response = createFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST, body);

        if (isHEAD(httpRequest)) {
            // don't allow any body content in response to a HEAD request
            response.content().clear();
        }

        return respondWithShortCircuitResponse(httpRequestChannel, response);
    }

    public static boolean isHEAD(HttpRequest httpRequest) {
        return HttpMethod.HEAD.equals(httpRequest.getMethod());
    }

    private static void writeEmptyBuffer(Channel channel) {
        channel.write(Unpooled.EMPTY_BUFFER);
        //write(Unpooled.EMPTY_BUFFER);
    }

    /**
     * If an HttpObject implements the market interface LastHttpContent, it
     * represents the last chunk of a transfer.
     *
     * @param httpObject
     * @return
     * @see LastHttpContent
     */
    public static boolean isLastChunk(final HttpObject httpObject) {
        return httpObject instanceof LastHttpContent;
    }


    public static boolean isCONNECT(HttpObject httpObject) {
        return httpObject instanceof HttpRequest
                && HttpMethod.CONNECT.equals(((HttpRequest) httpObject)
                .getMethod());
    }

    /**
     * Adds the Via header to specify that the message has passed through the proxy. The specified alias will be
     * appended to the Via header line. The alias may be the hostname of the machine proxying the request, or a
     * pseudonym. From RFC 7230, section 5.7.1:
     * <pre>
     * The received-by portion of the field value is normally the host and
     * optional port number of a recipient server or client that
     * subsequently forwarded the message.  However, if the real host is
     * considered to be sensitive information, a sender MAY replace it with
     * a pseudonym.
     * </pre>
     *
     * @param httpMessage HTTP message to add the Via header to
     * @param alias       the alias to provide in the Via header for this proxy
     */
    public static void addVia(HttpMessage httpMessage, String alias) {
        String newViaHeader = new StringBuilder()
                .append(httpMessage.getProtocolVersion().majorVersion())
                .append('.')
                .append(httpMessage.getProtocolVersion().minorVersion())
                .append(' ')
                .append(alias)
                .toString();

        final List<String> vias;
        if (httpMessage.headers().contains(HttpHeaders.Names.VIA)) {
            List<String> existingViaHeaders = httpMessage.headers().getAll(HttpHeaders.Names.VIA);
            vias = new ArrayList<>(existingViaHeaders);
            vias.add(newViaHeader);
        } else {
            vias = Collections.singletonList(newViaHeader);
        }

        httpMessage.headers().set(HttpHeaders.Names.VIA, vias);
    }

    public static void fillAuthenticationInfo(Source source, HttpRequest httpRequest) {
        if (!source.needAuth()) {
            return;
        }
        String authorizationContent = "Basic " + BaseEncoding.base64().encode((source.getUpstreamAuthUser() + ":" + source.getUpstreamAuthPassword()).getBytes(StandardCharsets.UTF_8));
        httpRequest.headers().set(HttpHeaders.Names.PROXY_AUTHORIZATION, authorizationContent);
    }

    public static HttpRequest copyHttpRequest(HttpRequest original) {
        if (original instanceof FullHttpRequest) {
            return ((FullHttpRequest) original).copy();
        }
        HttpRequest request = new DefaultHttpRequest(original.getProtocolVersion(),
                original.getMethod(), original.getUri());
        request.headers().set(original.headers());
        return request;
    }
}
