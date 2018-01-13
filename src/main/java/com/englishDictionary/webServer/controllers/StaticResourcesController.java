package com.englishDictionary.webServer.controllers;

import com.englishDictionary.config.Config;
import com.englishDictionary.config.EnvironmentType;
import com.englishDictionary.webServer.HttpServletRequest;
import com.englishDictionary.webServer.HttpServletResponse;
import com.englishDictionary.webServer.annotations.Controller;
import com.englishDictionary.webServer.annotations.RequestMappingByFileExtensions;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

//https://www.sitepoint.com/web-foundations/mime-types-complete-list/
//https://netty.io/4.0/xref/io/netty/example/http/file/HttpStaticFileServerHandler.html

@Controller
public class StaticResourcesController {

    public static final String HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz";
    public static final String HTTP_DATE_GMT_TIMEZONE = "GMT";
//    public static final int HTTP_CACHE_SECONDS = 1; // 1 sec
    public static final int HTTP_CACHE_SECONDS = 31536000; // 1 year

    private static final int BUFFER_SIZE = 4096;
    private static final Map<String, String> mimeTypes = new HashMap<String, String>() {
        {put("js","application/javascript");}
        {put("css","text/css");}
        {put("ico","image/x-icon");}
        {put("png","image/png");}
        {put("gif","image/gif");}
        {put("json","application/json");}
    };

    @RequestMappingByFileExtensions(exts = {"js", "css", "ico", "png", "jpg", "gif", "json"})
    public void getStaticResource(HttpServletRequest request, HttpServletResponse response) throws IOException, ParseException {
        String contextPath = request.getContextPath();
        String userDir = (EnvironmentType.OPEN_SHIFT_CLUSTER == Config.INSTANCE.getEnvironmentType()) ? "/tmp/src/" : System.getProperty("user.dir");
        String realpath;
        if (contextPath.startsWith("/static/images/mnemonics/mnemonics.png")) {
            realpath = Config.INSTANCE.getMnemonicsDir() + "mnemonics.png";
        } else if (contextPath.startsWith("/mnemonics/images/")) {
            realpath = Config.INSTANCE.getMnemonicsImagesDir() + contextPath.substring("/mnemonics/images/".length(), contextPath.length());
        } else if (contextPath.startsWith("/static/images/flags/")) {
            realpath = Config.INSTANCE.getMnemonicsFlagsDir() + contextPath.substring("/static/images/flags/".length(), contextPath.length());
        } else if (contextPath.startsWith("/static/images/forvo.png")) {
            realpath = Config.INSTANCE.getForvoDir() + "forvo.png";
        } else if (contextPath.startsWith("/static/images/oald9/")) {
            realpath = Config.INSTANCE.getOALD9ImagesDir() + contextPath.substring("/static/images/oald9/".length(), contextPath.length());
        } else if (contextPath.startsWith("/OALD9/images/")) { // For OpenShift
            realpath = Config.INSTANCE.getOALD9ImagesDir() + contextPath.substring("/OALD9/images/".length(), contextPath.length());
        } else if (contextPath.startsWith("/LDOCE6/images/")) {
            realpath = Config.INSTANCE.getLDOCE6ImagesDir() + contextPath.substring("/LDOCE6/images/".length(), contextPath.length());
        } else if (contextPath.startsWith("/static/images/wordCard/")) {
            realpath = Config.INSTANCE.getWordCardsImagesDir() + contextPath.substring("/static/images/wordCard/".length(), contextPath.length());
        } else if (contextPath.startsWith("/static/images/")) {
            realpath = Config.INSTANCE.getCommonImagesDir() + contextPath.substring("/static/images/".length(), contextPath.length());
        } else {
            realpath = userDir + contextPath;
        }
        File file = new File(realpath);
        if (!file.exists()) {
            response.setStatus(HttpResponseStatus.NOT_FOUND);
            response.setErrorMessage("Not found resource:" + contextPath);
            return;
        } else if (!file.isFile() || file.isHidden()) {
            response.setStatus(HttpResponseStatus.FORBIDDEN);
            response.setErrorMessage("Forbidden resource:" + contextPath);
            return;
        }

        // Cache Validation
        String ifModifiedSince = request.getIfModifiedSince();
        if (ifModifiedSince != null && !ifModifiedSince.isEmpty()) {
            SimpleDateFormat dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
            Date ifModifiedSinceDate = dateFormatter.parse(ifModifiedSince);

            // Only compare up to the second because the datetime format we send to the client
            // does not have milliseconds
            long ifModifiedSinceDateSeconds = ifModifiedSinceDate.getTime() / 1000;
            long fileLastModifiedSeconds = file.lastModified() / 1000;
            if (ifModifiedSinceDateSeconds == fileLastModifiedSeconds) {
                sendNotModified(request.getChannelHandlerContext());
                response.setForceClosed(true);
                return;
            }
        }

        try {
            response.setStatus(HttpResponseStatus.OK);
            response.setContentType(getMimeType(realpath));
            setDateAndCacheHeaders(response, file);

            OutputStream os = response.getOutputStream();
            FileInputStream is = new FileInputStream(file);
            transferStreams(is, os);
            is.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
      * When file timestamp is the same as what the browser is sending up, send a "304 Not Modified"
      *
      * @param ctx
      *        Context
      */
    private static void sendNotModified(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_MODIFIED);
        SimpleDateFormat dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
        dateFormatter.setTimeZone(TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE));

        Calendar time = new GregorianCalendar();
        response.headers().set(HttpHeaderNames.DATE, dateFormatter.format(time.getTime()));

        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
      * Sets the Date and Cache headers for the HTTP Response
      *
      * @param response
      *            HTTP response
      * @param fileToCache
      *            file to extract content type
      */
    private static void setDateAndCacheHeaders(HttpServletResponse response, File fileToCache) {
        SimpleDateFormat dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
        dateFormatter.setTimeZone(TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE));

        // Date header
        Calendar time = new GregorianCalendar();
        response.addHeader(HttpHeaderNames.DATE.toString(), dateFormatter.format(time.getTime()));

        // Add cache headers
        time.add(Calendar.SECOND, HTTP_CACHE_SECONDS);
        response.addHeader(HttpHeaderNames.EXPIRES.toString(), dateFormatter.format(time.getTime()));
        response.addHeader(HttpHeaderNames.CACHE_CONTROL.toString(), "private, max-age=" + HTTP_CACHE_SECONDS);
        response.addHeader(HttpHeaderNames.LAST_MODIFIED.toString(), dateFormatter.format(new Date(fileToCache.lastModified())));
    }

    private void transferStreams(InputStream is, OutputStream os) throws IOException {
        int bytesRead;
        byte[] bufferIO = new byte[BUFFER_SIZE];
        while ((bytesRead = is.read(bufferIO, 0, BUFFER_SIZE)) != -1) {
            os.write(bufferIO, 0, bytesRead);
        }
    }

    private String getFileExtension(String filePath) {
        try {
            return filePath.substring(filePath.lastIndexOf(".") + 1);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String getMimeType(String filePath) throws IOException {
        String mimeType = mimeTypes.get(getFileExtension(filePath));
        return (mimeType == null) ? "application/octet-stream" : mimeType;
    }
}
