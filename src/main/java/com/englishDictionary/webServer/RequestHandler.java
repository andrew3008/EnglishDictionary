package com.englishDictionary.webServer;

import com.englishDictionary.webServer.annotations.RequestMethod;

import java.lang.reflect.Method;

/**
 * Created by Andrew on 8/27/2016.
 */
public class RequestHandler {
    private RequestMethod requestMethod;
    private Object handlerClassObject;
    private Method handlerClassMethod;
    private boolean needSendHttpRequest;
    private boolean needSendHttpResponse;

    public RequestMethod getRequestMethod() {
        return requestMethod;
    }

    public void setRequestMethod(RequestMethod requestMethod) {
        this.requestMethod = requestMethod;
    }

    public Object getHandlerClassObject() {
        return handlerClassObject;
    }

    public void setHandlerClassObject(Object handlerClassObject) {
        this.handlerClassObject = handlerClassObject;
    }

    public Method getHandlerClassMethod() {
        return handlerClassMethod;
    }

    public void setHandlerClassMethod(Method handlerClassMethod) {
        this.handlerClassMethod = handlerClassMethod;
    }

    public boolean isNeedSendHttpRequest() {
        return needSendHttpRequest;
    }

    public void setNeedSendHttpRequest(boolean needSendHttpRequest) {
        this.needSendHttpRequest = needSendHttpRequest;
    }

    public boolean isNeedSendHttpResponse() {
        return needSendHttpResponse;
    }

    public void setNeedSendHttpResponse(boolean needSendHttpResponse) {
        this.needSendHttpResponse = needSendHttpResponse;
    }
}
