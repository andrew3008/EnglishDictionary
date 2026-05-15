package space.br1440.platform.datasource.configuration.support;

import java.util.Map;

/**
 * Спецификация read-пула, выводимая из read-стратегии микросервиса.
 *
 * <p>Транспорт-агностична: отдаёт {@code targetServerType} как строковую константу
 * pgJDBC/r2dbc-postgresql, явный флаг {@code loadBalanced} (round-robin между хостами)
 * и map дополнительных driver-параметров. Конкретный URL- или builder-уровень собирается
 * в стартере транспорта (JDBC URL query-string vs R2DBC-builder).
 *
 * <p><b>loadBalanced как явное поле, а не key в {@link #extraParams}.</b> В JDBC-ветке
 * параметр {@code loadBalanceHosts=true} попадает в URL query-string как driver-property;
 * в R2DBC-ветке — это уже builder-флаг {@code PostgresqlConnectionConfiguration.Builder
 * #loadBalanceHosts(boolean)}, не driver-property. Хранение в {@link #extraParams} вело
 * к неявному контракту через side-effect map'а; явное поле устраняет эту хрупкость.
 */
public record ReadPoolUrlSpec(String targetServerType,
                              boolean loadBalanced,
                              Map<String, String> extraParams) {

    public ReadPoolUrlSpec {
        extraParams = (extraParams == null) ? Map.of() : Map.copyOf(extraParams);
    }
}
