package com.englishDictionary.webServer;

import io.netty.channel.ChannelHandlerContext;

import java.util.List;
import java.util.Map;

/**
 * Created by Andrew on 9/3/2016.
 */
public class HttpServletRequest {
    private String contextPath;
    private Map<String, List<String>> parameters;
    private String ifModifiedSince;
    private ChannelHandlerContext channelHandlerContext;

    public String getContextPath() {
        return contextPath;
    }

    public void setContextPath(String contextPath) {
        this.contextPath = contextPath;
    }

    public void setParameters(Map<String, List<String>> parameters) {
        this.parameters = parameters;
    }

    public String getParameter(String name) {
        List<String> parameterValues = parameters.get(name);
        return (parameterValues == null) ? null : parameterValues.get(0);
    }

    public Map<String, List<String>> getParameters() {
        return parameters;
    }

    public String getIfModifiedSince() {
        return ifModifiedSince;
    }

    public void setIfModifiedSince(String ifModifiedSince) {
        this.ifModifiedSince = ifModifiedSince;
    }

    public ChannelHandlerContext getChannelHandlerContext() {
        return channelHandlerContext;
    }

    public void setChannelHandlerContext(ChannelHandlerContext channelHandlerContext) {
        this.channelHandlerContext = channelHandlerContext;
    }
}
