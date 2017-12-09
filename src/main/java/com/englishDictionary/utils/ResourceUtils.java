package com.englishDictionary.utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;

/**
 * Created by Andrew on 5/31/2017.
 */
public class ResourceUtils {

    public static final String DIR_FOR_SAVE_SOUNDS = "H:\\soundDatFile\\";
    public static final String DIR_FOR_SAVE_IMAGES = "H:\\images\\";

    public static String downloadResource(String resourceURL, String rootDirForSave) throws IOException {
        URL url = new URL(resourceURL);
        String fileName = getFileNameFromURL(resourceURL);
        InputStream in = new BufferedInputStream(url.openStream());
        OutputStream out = new BufferedOutputStream(new FileOutputStream(rootDirForSave + fileName));
        for (int i; (i = in.read()) != -1; ) {
            out.write(i);
        }
        in.close();
        out.close();
        //return "/mnemonics/images/" + imageIndex + ".jpg";
        return "";
    }

    public static String getFileNameFromURL(String url) {
        return url.substring(url.lastIndexOf('/') + 1, url.length());
    }

    public static String getFileNameWithoutExtnFromURL(String url) {
        String fileName = getFileNameFromURL(url);
        return fileName.substring(0, fileName.lastIndexOf('.'));
    }

    public static String getFileNameFromPath(String url) {
        return url.substring(url.lastIndexOf('\\') + 1, url.length());
    }

    public static String getFileNameWithoutExtnFromPath(String url) {
        String fileName = getFileNameFromPath(url);
        return fileName.substring(0, fileName.lastIndexOf('.'));
    }

}

//java.io.FileNotFoundException: http://www.oxfordlearnersdictionaries.com/media/english/uk_pron_ogg/x/xco/xcont/xcontd__gb_1.ogg
//        at sun.net.www.protocol.http.HttpURLConnection.getInputStream0(HttpURLConnection.java:1872)
//        at sun.net.www.protocol.http.HttpURLConnection.getInputStream(HttpURLConnection.java:1474)
//        at java.net.URL.openStream(URL.java:1045)
//        at com.englishDictionary.Convertor.downloadResource(Convertor.java:122)
//        at com.englishDictionary.Convertor.main(Convertor.java:83)
//        at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
//        at sun.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:62)
//        at sun.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)
//        at java.lang.reflect.Method.invoke(Method.java:498)
//        at com.intellij.rt.execution.application.AppMain.main(AppMain.java:140)