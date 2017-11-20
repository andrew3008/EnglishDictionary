package com.englishDictionary.webServer.annotations;

import java.lang.annotation.*;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestMappingByFileExtensions {
    String[] exts();
    RequestMethod method() default RequestMethod.GET;
}
