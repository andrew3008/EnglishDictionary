package com.englishDictionary.utils.httl;

import httl.Engine;

import java.util.Properties;

//http://httl.github.io/en/config.html

/**
 * Created by Andrew on 9/3/2016.
 */
public class HttlEngineKeeper {
    public static final Engine engine;

    static
    {
        Properties httlProperties = new Properties();
        httlProperties.put("import.packages", "com.englishDictionary.webServices.forvo, java.util, java.lang");
        httlProperties.put("template.directory", System.getProperty("user.dir") + "/static/httl/");
        httlProperties.put("template.suffix", ".httl");
        httlProperties.put("input.encoding", "UTF-8");
        httlProperties.put("output.encoding", "UTF-8");
        httlProperties.put("precompiled", "true");
        httlProperties.put("cache", "httl.spi.caches.AdaptiveCache");
        httlProperties.put("cache_capacity", "1024");
        // Set to FALSE to
        // 1) can open the heat load: (according to the file's last modification time automatically clear the cache)
        // 2) clear header 'Expires' for template files
        httlProperties.put("reloadable", "false");
        //httlProperties.put("reloadable", "true");
        httlProperties.put("translator", "httl.spi.translators.CompiledTranslator");
        httlProperties.put("loaders", "httl.spi.loaders.FileLoader");
        httlProperties.put("comment.left", "<!--");
        httlProperties.put("comment.right", "-->");

        httlProperties.put("compiler", "httl.spi.compilers.JdkCompiler");
        httlProperties.put("compile.version", "1.8");
        httlProperties.put("source.in.class", "true");
        httlProperties.put("text.in.class", "true");
        httlProperties.put("code.directory", System.getProperty("user.dir") + "/target/generated-sources/");
        httlProperties.put("compile.directory", System.getProperty("user.dir") + "/target/generated-sources/");
        httlProperties.put("lint.unchecked", "true");

        engine = httl.Engine.getEngine(httlProperties);
    }
}
