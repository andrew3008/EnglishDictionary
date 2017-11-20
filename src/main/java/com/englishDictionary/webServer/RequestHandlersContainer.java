package com.englishDictionary.webServer;

import com.englishDictionary.webServer.annotations.Controller;
import com.englishDictionary.webServer.annotations.RequestMapping;
import com.englishDictionary.webServer.annotations.RequestMappingByFileExtensions;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Created by Andrew on 9/10/2016.
 */
public class RequestHandlersContainer {
    private final static String WEB_SERVER_CONTROLLERS_PACKAGE_NAME = "com.englishDictionary.webServer.controllers";

    private static Map<String, RequestHandler> handlers;

    static {
        try {
            handlers = new HashMap<>();
            Map<String, Object> controllerClasses = new HashMap<>();
            for (Class checkingClass : getClasses(WEB_SERVER_CONTROLLERS_PACKAGE_NAME)) {
                Annotation annotationController = checkingClass.getAnnotation(Controller.class);
                if (annotationController != null) {
                    String controllerClassName = checkingClass.getName();
                    if (controllerClasses.get(controllerClassName) == null) {
                        controllerClasses.put(controllerClassName, checkingClass.newInstance());
                    }

                    Method[] methods = checkingClass.getDeclaredMethods();
                    for (Method checkingMethod : methods) {
                        Annotation[] annotations = checkingMethod.getAnnotations();
                        for (Annotation annotation : annotations) {
                            if (annotation instanceof RequestMapping) {
                                RequestMapping annReqMapping = (RequestMapping) annotation;
                                RequestHandler requestHandler = new RequestHandler();
                                requestHandler.setRequestMethod(annReqMapping.method());
                                requestHandler.setHandlerClassObject(controllerClasses.get(controllerClassName));
                                requestHandler.setHandlerClassMethod(checkingMethod);
                                String fullRequestURL = ((Controller) annotationController).url() + annReqMapping.url();
                                handlers.put(fullRequestURL, requestHandler);
                                break;
                            } else if (annotation instanceof RequestMappingByFileExtensions) {
                                RequestMappingByFileExtensions annReqMappingByFileExtensions = (RequestMappingByFileExtensions) annotation;
                                for (String ext : annReqMappingByFileExtensions.exts()) {
                                    RequestHandler requestHandler = new RequestHandler();
                                    requestHandler.setRequestMethod(annReqMappingByFileExtensions.method());
                                    requestHandler.setHandlerClassObject(controllerClasses.get(controllerClassName));
                                    requestHandler.setHandlerClassMethod(checkingMethod);
                                    handlers.put(ext, requestHandler);
                                }
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Class[] getClasses(String packageName) throws ClassNotFoundException, IOException {
        String classFileExtension = ".class";
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> resources = classLoader.getResources(packageName.replace('.', '/'));
        ArrayList<Class> classes = new ArrayList<Class>();
        while (resources.hasMoreElements()) {
            File directory = new File(resources.nextElement().getFile());
            if (directory.exists()) {
                // Unpacked jar
                File[] files = directory.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().endsWith(classFileExtension)) {
                            classes.add(Class.forName(packageName + '.' + f.getName().substring(0, f.getName().length() - classFileExtension.length())));
                        }
                    }
                }
            } else {
                // Packed jar
                String[] parts = directory.getPath().substring("file:".length()).split("!");
                if (parts.length == 2) {
                    Enumeration e = new JarFile(parts[0]).entries();
                    while (e.hasMoreElements()) {
                        String jen = ((JarEntry) e.nextElement()).getName();
                        if (jen.startsWith(packageName.replace('.', '/')) && jen.endsWith(classFileExtension)) {
                            jen = jen.substring(packageName.length() + 1);
                            jen = jen.substring(0, jen.length() - classFileExtension.length());
                            classes.add(Class.forName(packageName + '.' + jen));
                        }
                    }
                }
            }
        }
        return classes.toArray(new Class[classes.size()]);
    }

    public static Map<String, RequestHandler> getHandlers() {
        return handlers;
    }
}
