package space.br1440.platform.logging;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.Optional;

public class SpringContext implements ApplicationContextAware {

    private static ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        context = applicationContext;
    }

    public static <T> T getBean(Class<T> beanClass) {
        T result = null;
        try {
            result = context.getBean(beanClass);
        } catch (Exception e) {
            //nothing here!
        }
        return result;
    }

    public static ApplicationContext getContext() {
        return context;
    }
}
