package space.br1440.platform.logging.configuration;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import space.br1440.platform.logging.logback.MdcSensitiveDataMaskerConverter;
import space.br1440.platform.logging.masking.SensitiveDataMasker;

@Component
public class MdcMaskerInitializer implements ApplicationListener<ApplicationReadyEvent> {

    private final SensitiveDataMasker masker;
    private final LoggingProperties loggingProperties;

    public MdcMaskerInitializer(SensitiveDataMasker masker, LoggingProperties loggingProperties) {
        this.masker = masker;
        this.loggingProperties = loggingProperties;
    }

    @Override
    public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
        MdcSensitiveDataMaskerConverter.setMasker(masker, loggingProperties);
    }

}
