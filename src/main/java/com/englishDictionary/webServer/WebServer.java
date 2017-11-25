package com.englishDictionary.webServer;

import com.englishDictionary.utils.FileUtils;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.Map;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Created by Andrew on 8/27/2016.
 */
public class WebServer {
    private final static int WEB_SERVER_PORT = 8080;

    public static void main(String[] args) throws Exception {
        WebServer webServer = new WebServer();
        webServer.start();
    }

    public void start() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ServerInitializer());
            Channel ch = b.bind(WEB_SERVER_PORT).sync().channel();
            ch.closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    private class ServerInitializer extends ChannelInitializer<SocketChannel> {
        @Override
        public void initChannel(SocketChannel ch) throws Exception {
            ChannelPipeline pipeline = ch.pipeline();
            pipeline.addLast("decoder", new HttpRequestDecoder());
            pipeline.addLast("encoder", new HttpResponseEncoder());
            pipeline.addLast("handler", new ServerRequestHandler());
        }
    }

    class ServerRequestHandler extends SimpleChannelInboundHandler<Object> {
        private HttpRequest request;
        private HttpServletRequest requestServlet = new HttpServletRequest();
        private HttpServletResponse responseServlet = new HttpServletResponse();

        private final String DEFAULT_CONTENT_TYPE = "text/html; charset=UTF-8";

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof HttpRequest) {
                request = (HttpRequest) msg;

                if (!request.decoderResult().isSuccess()) {
                    sendError(ctx, HttpResponseStatus.BAD_REQUEST);
                    return;
                }

                if (request.method() != HttpMethod.GET) {
                    sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
                    return;
                }

                QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.uri());
                final String contextPath = queryStringDecoder.path();
                //System.out.println("[WebServer] contextPath:" + contextPath);
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
                    channelRead0(ctx, new DefaultHttpRequest(HTTP_1_1, HttpMethod.GET, internalRedirectURL));
                }

                // External redirect
                String externalRedirectURL = responseServlet.getExternalRedirectURL();
                if (externalRedirectURL != null) {
                    sendExternalRedirect(ctx, externalRedirectURL);
                    return;
                }

                // Send error of requestHandler
                if (responseServlet.getErrorMessage() != null) {
                    sendError(ctx, responseServlet.getStatus(), responseServlet.getErrorMessage());
                    return;
                }

                ByteArrayOutputStream responseOutputStream = responseServlet.getOutputStream();
                HttpResponseStatus status = (responseServlet.getStatus() == null) ? HttpResponseStatus.OK : responseServlet.getStatus();
                // TODO: Find reason of nulls
                String contentType = (responseServlet.getContentType() == null) ? DEFAULT_CONTENT_TYPE : responseServlet.getContentType();
                FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status, Unpooled.copiedBuffer(responseOutputStream.toByteArray(), 0, responseOutputStream.writedBytes()));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
                for (Map.Entry<String, String> headerEntry : responseServlet.getHeaders().entrySet()) {
                    response.headers().set(headerEntry.getKey(), headerEntry.getValue());
                }

                if (HttpUtil.isKeepAlive(request)) {
                    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                    int contentLength = (responseServlet.getPresetContentLength() == -1) ? responseOutputStream.writedBytes() : responseServlet.getPresetContentLength();
                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, contentLength);
                }

                // Write the initial line, headers and body response.
                ctx.write(response);

                // Write the end marker.
                ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

                // Decide whether to close the connection or not.
                if (!HttpUtil.isKeepAlive(request)) {
                    // Close the connection when the whole content is written out.
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