package com.englishDictionary.utils;

/**
 * Created by Andrew on 9/10/2016.
 */
public class FileUtils {
    public static String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return ((dotIndex == -1) || (dotIndex == 0)) ? "" : fileName.substring(dotIndex + 1);
    }
}
