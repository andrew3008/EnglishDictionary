# Loader contract: OpenTelemetry Java Agent 2.30.0 ACP SPI

Источник: `open-telemetry/opentelemetry-java-instrumentation` tag `v2.30.0`
commit `080c1d32ca676ba7b0210c21b60cce8e7ac39058`.

## Видимость AutoConfigurationCustomizerProvider

`AgentClassLoader` открывает fat-JAR агента и читает ресурсы с префиксом `inst/`.
Классы лежат как `.classdata`, не как `.class`. Файлы `META-INF/services/*`
ищутся как `inst/META-INF/services/*`. Имена, начинающиеся с `META-INF/`,
не переопределяются через `META-INF/versions/*`.

`ExtensionClassLoader` загружает только `extensions/*.jar` (вложенные и
`otel.javaagent.extensions`). Он не является источником upstream ACP pin.

## Scanner

Читать ровно один descriptor:

```text
inst/META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider
```

Не сканировать:

- корень fat-JAR `META-INF/services/` (для этого SPI пуст);
- `inst/<module>/META-INF/services/` (изолированные instrumentation SPI);
- вложенные JAR (в 2.30.0 их нет);
- рекурсию второго уровня.

Появление второго ACP descriptor, versioned ACP, ACP внутри `inst/<module>/`
или нечитаемого ZIP — fail.

Parser descriptor: UTF-8 без BOM, `#` comments, trim, Java binary name,
duplicate declaration — fail, пустой набор — fail, platform provider в pin — fail.
