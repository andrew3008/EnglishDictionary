package space.br1440.platform.metrics.buildinfo;

/**
 * Контракт JSON {@code build-info.json} в classpath (корень JAR после {@code processResources}).
 * <p>
 * Поля заполняются на этапе сборки модулем Gradle (задача {@code generateBuildInfo}).
 * Имена полей в JSON совпадают с именами компонентов record для десериализации Jackson в рантайме.
 */
public record BuildInfo(
        String version,
        String buildTime
) {}
