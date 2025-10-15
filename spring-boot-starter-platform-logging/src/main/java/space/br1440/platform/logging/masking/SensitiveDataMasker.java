package space.br1440.platform.logging.masking;

public interface SensitiveDataMasker {

    String maskValue(String name, String value);

    String maskMessage(String message);

}
