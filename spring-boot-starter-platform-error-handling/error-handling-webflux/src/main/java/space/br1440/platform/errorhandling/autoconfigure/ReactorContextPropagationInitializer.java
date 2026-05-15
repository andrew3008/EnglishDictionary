package space.br1440.platform.errorhandling.autoconfigure;

import io.micrometer.context.ContextRegistry;
import org.springframework.beans.factory.SmartInitializingSingleton;
import reactor.core.publisher.Hooks;

public class ReactorContextPropagationInitializer implements SmartInitializingSingleton {

    @Override
    public void afterSingletonsInstantiated() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new MdcThreadLocalAccessor());
        Hooks.enableAutomaticContextPropagation();
    }
}
