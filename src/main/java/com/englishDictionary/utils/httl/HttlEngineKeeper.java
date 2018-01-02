package com.englishDictionary.utils.httl;

import com.englishDictionary.config.Config;
import com.englishDictionary.config.EnvironmentType;
import httl.Engine;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

//http://httl.github.io/en/config.html

/**
 * Created by Andrew on 9/3/2016.
 */
public class HttlEngineKeeper {
    public static final Engine engine;

    static
    {
        String userDir = (EnvironmentType.OPEN_SHIFT_CLUSTER == Config.getEnvironmentType()) ? "/tmp/src/" : System.getProperty("user.dir");

        Properties httlProperties = new Properties();
        httlProperties.put("import.packages", "com.englishDictionary.servicesThirdParty.forvo, java.util, java.lang");
        httlProperties.put("template.directory", userDir + "/static/httl/");
        httlProperties.put("template.suffix", ".httl");
        httlProperties.put("input.encoding", StandardCharsets.UTF_8.name());
        httlProperties.put("output.encoding", StandardCharsets.UTF_8.name());
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
        httlProperties.put("code.directory", userDir + "/target/generated-sources/");
        httlProperties.put("compile.directory", userDir + "/target/generated-sources/");
        httlProperties.put("lint.unchecked", "true");

        engine = httl.Engine.getEngine(httlProperties);
    }
}
