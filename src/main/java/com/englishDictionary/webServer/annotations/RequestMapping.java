package com.englishDictionary.webServer.annotations;

import java.lang.annotation.*;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestMapping {
    String url() default "";
    RequestMethod method() default RequestMethod.GET;
}
