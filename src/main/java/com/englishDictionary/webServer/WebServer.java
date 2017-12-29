package com.englishDictionary.webServer;

import com.englishDictionary.config.Config;
import com.englishDictionary.utils.FileUtils;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Created by Andrew on 8/27/2016.
 */
public class WebServer {

    private static final int AGGREFATOR_HTTP_REQUESTS_BUFFER_INIT_SIZE = 10240;

    public static void main(String[] args) throws Exception {
        WebServer webServer = new WebServer();
        webServer.start();
    }

    public void start() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(group).channel(NioServerSocketChannel.class).childHandler(new ServerInitializer());
            Channel ch = b.bind(Config.WEB_SERVER_PORT).channel();
            ch.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    private class ServerInitializer extends ChannelInitializer<SocketChannel> {
        @Override
        public void initChannel(SocketChannel ch) throws Exception {
            ChannelPipeline pipeline = ch.pipeline();
            pipeline.addLast(new HttpServerCodec());
            pipeline.addLast(new HttpObjectAggregator(AGGREFATOR_HTTP_REQUESTS_BUFFER_INIT_SIZE));
            pipeline.addLast(new ServerRequestHandler());
        }
    }

    private static class ServletResponsePoolFactory {
        static final ServletResponsePoolFactory INSTANCE = new ServletResponsePoolFactory();
        Map<HttpServletResponse, Boolean> httpServletResponsePool = new HashMap<HttpServletResponse, Boolean>();

        HttpServletResponse getHttpServletResponse() {
            for (Map.Entry<HttpServletResponse, Boolean> entry : httpServletResponsePool.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    httpServletResponsePool.put(entry.getKey(), Boolean.FALSE);
                    return entry.getKey();
                }
            }
            HttpServletResponse responseServlet = new HttpServletResponse();
            httpServletResponsePool.put(responseServlet, Boolean.TRUE);
            return responseServlet;
        }

        void freeHttpServletResponse(HttpServletResponse response) {
            httpServletResponsePool.put(response, Boolean.TRUE);
        }
    }

    class ServerRequestHandler extends SimpleChannelInboundHandler<Object> {
        private FullHttpRequest request;
        private HttpServletRequest requestServlet = new HttpServletRequest();
        private HttpServletResponse responseServlet = ServletResponsePoolFactory.INSTANCE.getHttpServletResponse();

        private final static String DEFAULT_CONTENT_TYPE = "text/html; charset=UTF-8";
        private final Set<HttpMethod> SUPPORTED_HTTP_METHODS = new HashSet<>(Arrays.asList(HttpMethod.GET, HttpMethod.POST));

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof FullHttpRequest) {
                request = (FullHttpRequest) msg;
                if (request == null) {
                    return;
                }

                if (!request.decoderResult().isSuccess()) {
                    sendError(ctx, HttpResponseStatus.BAD_REQUEST);
                    return;
                }

                if (!SUPPORTED_HTTP_METHODS.contains(request.method()))  {
                    sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
                    return;
                }

                QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.uri());
                final String contextPath = queryStringDecoder.path();
                RequestHandler requestHandler = RequestHandlersContainer.getHandlers().get(contextPath);
                if (requestHandler == null) {
                    String fileExt = FileUtils.getFileExtension(contextPath);
                    if (fileExt != null) {
                        requestHandler = RequestHandlersContainer.getHandlers().get(fileExt);
                    }
                }

                if (requestHandler == null) {
                    sendError(ctx, HttpResponseStatus.NOT_FOUND);
                    return;
                }

                requestServlet.setContextPath(contextPath);
                requestServlet.setParameters(queryStringDecoder.parameters());
                if (HttpMethod.POST.equals(request.method())) {
                    requestServlet.setContent(request.content());
                }
                requestServlet.setIfModifiedSince(request.headers().get(HttpHeaderNames.IF_MODIFIED_SINCE));
                requestServlet.setChannelHandlerContext(ctx);

                responseServlet.setStatus(HttpResponseStatus.OK);
                responseServlet.setErrorMessage(null);
                responseServlet.setContentType(DEFAULT_CONTENT_TYPE);
                responseServlet.setInternalRedirectURL(null);
                responseServlet.setExternalRedirectURL(null);
                responseServlet.getOutputStream().reset();
                responseServlet.getHeaders().clear();
                responseServlet.setForceClosed(false);

                Method handlerClassMethod = requestHandler.getHandlerClassMethod();
                LinkedList<Object> handlerClassMethodArgs = new LinkedList<>();
                for (Class parameterClass : handlerClassMethod.getParameterTypes()) {
                    if (parameterClass == HttpServletRequest.class) {
                        handlerClassMethodArgs.add(requestServlet);
                    } else if (parameterClass == HttpServletResponse.class) {
                        handlerClassMethodArgs.add(responseServlet);
                    }
                }
                handlerClassMethod.invoke(requestHandler.getHandlerClassObject(), handlerClassMethodArgs.toArray());
            }

            if (msg instanceof LastHttpContent) {
                // Force closed
                if (responseServlet.isForceClosed()) {
                    return;
                }

                // Internal redirect
                String internalRedirectURL = responseServlet.getInternalRedirectURL();
                if (internalRedirectURL != null) {
                    channelRead0(ctx, new DefaultFullHttpRequest(HTTP_1_1, HttpMethod.GET, internalRedirectURL));
                }

                // External redirect
                String externalRedirectURL = responseServlet.getExternalRedirectURL();
                if (externalRedirectURL != null) {
                    ServletResponsePoolFactory.INSTANCE.freeHttpServletResponse(responseServlet);
                    sendExternalRedirect(ctx, externalRedirectURL);
                    return;
                }

                // Send error of requestHandler
                if (responseServlet.getErrorMessage() != null) {
                    ServletResponsePoolFactory.INSTANCE.freeHttpServletResponse(responseServlet);
                    sendError(ctx, responseServlet.getStatus(), responseServlet.getErrorMessage());
                    return;
                }

                ByteArrayOutputStream responseOutputStream = responseServlet.getOutputStream();
                HttpResponseStatus status = (responseServlet.getStatus() == null) ? HttpResponseStatus.OK : responseServlet.getStatus();
                String contentType = (responseServlet.getContentType() == null) ? DEFAULT_CONTENT_TYPE : responseServlet.getContentType();
                FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status, Unpooled.copiedBuffer(responseOutputStream.toByteArray(), 0, responseOutputStream.writedBytes()));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
                for (Map.Entry<String, String> headerEntry : responseServlet.getHeaders().entrySet()) {
                    response.headers().set(headerEntry.getKey(), headerEntry.getValue());
                }

                boolean isKeepAlive = HttpMethod.POST.equals(request.method()) ? false : HttpUtil.isKeepAlive(request);
                if (isKeepAlive) {
                    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                    int contentLength = (responseServlet.getPresetContentLength() == -1) ? responseOutputStream.writedBytes() : responseServlet.getPresetContentLength();
                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, contentLength);
                }

                // Write the initial line, headers and body response.
                ctx.write(response);

                // Write the end marker.
                ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

                // Decide whether to close the connection or not.
                if (!isKeepAlive) {
                    // Close the connection when the whole content is written out.
                    ServletResponsePoolFactory.INSTANCE.freeHttpServletResponse(responseServlet);
                    lastContentFuture.addListener(ChannelFutureListener.CLOSE);
                }
            }
        }

        private void sendExternalRedirect(ChannelHandlerContext ctx, String newUri) {
            FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.FOUND);
            response.headers().set(HttpHeaderNames.LOCATION, newUri);

            // Close the connection as soon as the error message is sent.
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
            sendError(ctx, status, "Failure: " + status + "\r\n");
        }

        private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String errorMessage) {
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HTTP_1_1, status, Unpooled.copiedBuffer(errorMessage, CharsetUtil.UTF_8));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");

            // Close the connection as soon as the error message is sent.
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            if (ctx.channel().isActive()) {
                sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR);
            }
        }
    }

}
