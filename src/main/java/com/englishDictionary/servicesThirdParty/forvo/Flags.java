package com.englishDictionary.servicesThirdParty.forvo;

import java.util.HashMap;
import java.util.Map;

public class Flags {
    private final static Map<String, String> mapflagNameToCode = new HashMap<String, String>() {{
        put("United States", "us");
        put("United Kingdom", "gb");
        put("Canada", "ca");
        put("Australia", "au");
        put("New Zealand", "nz");
        put("Ireland", "ie");
        put("Grenada", "gd");
        put("Colombia", "co");
        put("Jamaica", "jm");
        put("Malaysia", "my");
        put("Spain", "es");
        put("Switzerland", "ch");
        put("South Africa", "za");
    }};

    public static String getFlagCode(String flagName) {
        return mapflagNameToCode.get(flagName);
    }
}
