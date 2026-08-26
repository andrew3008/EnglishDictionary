# Platform Logging Library

Библиотека предоставляет:
- дефолтные Logback Appender’ы для логирования в консоль, logstash, JSON-файл;
- гибкую настройку логирования body и headers HTTP-запросов и ответов;
- возможность расширения правил маскирования чувствительных данных;
- интеграцию в Spring Boot-проекты через авто-конфигурацию.

---
Логгируются эндпойнты по маске "/api/*".
## Подключение зависимости
Для подключения библиотеки в Gradle-проекте нужно добавить зависимость:
```groovy
dependencies {
    implementation 'space.br1440.platform.starters:spring-boot-starter-platform-logging:{Желаемая версия}'
}
```

## Logback appenders
После добавления зависимости в Web-приложение становятся доступны преднастроенные appenders из библиотеки.

| **Название**              | **Назначение**                                            | **Комментарий**                                                                                   |
|---------------------------|-----------------------------------------------------------|---------------------------------------------------------------------------------------------------|
| **PLATFORM-CONSOLE-TEXT** | Консольный аппендер для текстового логирования            | Кодировка UTF-8, использует шаблон CONSOLE_LOG_PATTERN                                            |
| **PLATFORM-CONSOLE-JSON** | Консольный аппендер для JSON-логов                        | Использует LogstashEncoder, добавляет поле app_name с именем приложения                           |
| **PLATFORM-FILE-JSON**    | Аппендер для логирования в файл в формате JSON с ротацией | Файл /tmp/application.log, ротация по дате, хранение до 30 файлов, кодировка UTF-8, поле app_name |


Задействовать их можно через Spring-профили. Например:
```xml
<?xml version="1.0" encoding="UTF-8"?>

<configuration>
    <include resource="space/br1440/platform/logging/logback-spring-defaults.xml"/>

    <springProfile name="development">
        <root level="DEBUG">
            <appender-ref ref="PLATFORM-CONSOLE-TEXT"/>
        </root>
    </springProfile>

    <springProfile name="test">
        <root level="DEBUG">
            <appender-ref ref="PLATFORM-CONSOLE-TEXT"/>
        </root>
    </springProfile>

    <springProfile name="production">
        <root level="INFO">
            <appender-ref ref="PLATFORM-CONSOLE-JSON"/>
            <appender-ref ref="PLATFORM-FILE-JSON"/>
        </root>
    </springProfile>
</configuration>
```

## Конфигурация
Конфигурация библиотеки осуществляется посредством параметров в файле application.yml с префиксом platform.http-logging.
Указание параметров не является обязательным: при их отсутствии используется предопределённая конфигурация по умолчанию.

Полный пример Spring-конфигурации:
```yaml
platform:
  http-logging:
    enable: true
    mdc-excluded-keys:
      - correlation_id
      - uri
    servlet-filter:
      enabled: true
    custom-sensitive-data-rules-file-path: classpath:space/br1440/platform/logging/platform-log-masking-rules.yaml

    request:
      headers:
        enable: true
        exclude-headers:
          - x-request-id
          - user-agent
      body:
        enable: true
        max-length: 1024

    response:
      headers:
        enable: true
        exclude-headers:
          - content-type
          - content-length
      body:
        enable: true
        max-length: 1024
```

Таблица с их описанием относительно префикса platform.http-logging:

| **Параметр**                                   | **Тип**  | **Значение по умолчанию** | **Описание**                                                                                       |
|------------------------------------------------|----------|---------------------------|----------------------------------------------------------------------------------------------------|
| **enable**                                     | boolean	 | true                      | Включает/отключает HTTP-логгирование                                                               |
| **http-filter.enabled**                        | boolean	 | true                      | Включает/отключает Servlet-фильтр, через который Logbook производит перехват HTTP-запроса и ответа |
| **custom-sensitive-data-rules-file-path**      | string   | ""                        | Путь к файлу с правилами маскирования чувствительных данных                                        |
| **request.headers.enable**                     | boolean  | true                      | Логгировать заголовки HTTP-запроса                                                                 |
| **request.headers.exclude-headers**            | list     | []                        | Список исключаемых из логирования HTTP-headers в request                                           |
| **request.body.enable**                        | boolean  | true                      | Включает/отключает логгирование body для HTTP-запроса                                              |
| **request.body.max-length**                    | integer  | -1 (не ограничена)        | Максимальная длина логируемого тела HTTP-запроса                                                   |
| **response.headers.enable**                    | boolean	 | true                      | Включает/отключает логгирование заголовков HTTP-ответов                                            |
| **response.headers.exclude-headers**           | list     | []                        | Список исключаемых из логов заголовков HTTP-ответа                                                 |
| **response.body.enable**                       | boolean  | true                      | Включает/отключает логгирование body HTTP-ответа                                                   |
| **response.body.max-length**                   | integer  | -1 (не ограничена)        | Максимальная длина логируемого body HTTP-ответа                                                    |


## Маскирование чувствительных данных
Секретных данные в HTTP-запросах и ответах автоматически маскируются при логировании.
Маскирование производится по правилам, определённом в ресурсном файле "classpath:space/br1440/platform/logging/platform-log-masking-rules.yaml".
Пример правил из этого файла:
```yaml
rules:
  - name: token
    type: scalar
    apply-to-keys:
      - token
      - access_token
      - bearer_token
    mask: "*****"

  - name: tokens
    type: array
    apply-to-keys:
      - tokens
      - access_tokens
      - bearer_tokens
    mask-item: "*****"
```

Таблица с описанием параметров правила:

| **Параметр**              | **Тип** | **Описание**                                              |
|---------------------------|---------|-----------------------------------------------------------|
| **rules[].name**          | string  | Название правила                                          |
| **rules[].type**          | string  | Тип значения: scalar — одиночное значение, array — массив |
| **rules[].apply-to-keys** | list    | Список ключей, к которым применяется маскирование         |
| **rules[].mask**          | string  | Строка для маскирования значения scalar                   |
| **rules[].mask-item**     | string  | Строка для маскирования каждого элемента массива          |

Если type равен scalar, то используется параметр mask.
Если type равен array, то используется параметр mask-item.

В Web-приложении, использующем библиотеку, можно добавить кастомные рулы в YAML-файл, путь к которому указывается в
параметре platform.http-logging.custom-sensitive-data-rules-file-path. Кастомные рулы дополняют рулы по-умолчанию из
файла "platform-log-masking-rules.yaml", а не переопределяют их.

Пример маскирования HTTP-запроса и ответа:
![HTTP_logs_masking_example](docs/diagrams/HTTP_logs_masking_example.png)


## Аннотации для MDC-логирования
В библиотеку были добавлены специальные аннотации для управления тем, какие данные методов бизнес-логики должны быть автоматически помещены
в Mapped Diagnostic Context (MDC) для последующего использования в логировании.

| **Аннотация**    | **Уровень<br> применения** | **Назначение**                                                                     | **Когда использовать**                                                           |
|------------------|----------------------------|------------------------------------------------------------------------------------|----------------------------------------------------------------------------------|
| **@MDCLoggable** | Метод                      | Активирует аспект MDCLoggingAspect для автоматического помещения параметров в MDC. | Для методов, где необходимо фиксировать бизнес-ключи или идентификаторы в логах. |
| **@MDCLogKey**   | Параметр                   | Определяет ключ MDC, под которым будет сохранено значение параметра.               | Для передачи в логи бизнес-идентификаторов: requestId, userId, orderId.          |
| **@Masked**      | Параметр                   | Маскирует значение параметра, предотвращая утечку чувствительных данных.           | Для токенов, паролей, персональных данных и других конфиденциальных значений.    |

## MDC-контракт с platform-tracing v1

При подключении `space.br1440.platform.starters.tracing:spring-boot-starter-platform-tracing-mvc`
или `space.br1440.platform.starters.tracing:spring-boot-starter-platform-tracing-webflux` tracing-стarter
**записывает** в SLF4J MDC
следующие ключи (camelCase, W3C/Micrometer):

| MDC-ключ | Константа в logging | Источник (writer) |
|----------|---------------------|-------------------|
| `traceId` | `TracingMdcLogKeys.TRACE_ID` | platform-tracing MDC projection (`MDC.get`); agent `logback-mdc` только для logging event |
| `spanId` | `TracingMdcLogKeys.SPAN_ID` | то же |
| `traceFlags` | `TracingMdcLogKeys.TRACE_FLAGS` | то же |

OTel Agent `logback-mdc` обогащает logging event и **не гарантирует** `MDC.get()`.
Application-visible MDC принадлежит platform-tracing projection.

Logging-стартер **только читает** эти ключи:

- **Текстовый паттерн** (`PLATFORM-CONSOLE-TEXT`): блок `%maskedMDC` выводит все MDC-поля,
  кроме служебных из `platform.http-logging.mdc-excluded-keys` (по умолчанию HTTP timing и
  `correlation_id`; ключи `traceId`/`spanId`/`traceFlags` **не** исключаются).
- **JSON** (`PLATFORM-CONSOLE-JSON`, `PLATFORM-FILE-JSON`): `LogstashEncoder` включает MDC-поля
  автоматически.

Устаревшие snake_case-ключи (`trace_id`, `span_id`, `trace_flags`) в v1 **не поддерживаются**.

Циклическая зависимость на `platform-tracing-api` отсутствует — контракт зафиксирован
дублированием строк в `TracingMdcLogKeys` (logging) и `TracingMdcKeys` (tracing).

Пример использования аннотаций:
```java
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    @MDCLoggable
    public String create(
            @MDCLogKey("userId") String userId,
            @MDCLogKey("sec_password") @Masked("*****") String password) {
        return "Profile was created for userId " + userId;
    }

    @MDCLoggable
    public String updateProfile(
            @MDCLogKey("userId") String userId,
            @MDCLogKey("email") @Masked("hidden-email") String email) {
        return "Profile was updated for userId " + userId;
    }

}
```

## Кастомизация логирования
Логирование HTTP-запросов и ответов осуществляется с использованием библиотеки [Zalando Logbook](https://github.com/zalando/logbook).
Реализация построена модульно и позволяет расширять функциональность через Spring-бины.
Последовательность обработки запросов и ответов проиллюстрирована на Sequence-диаграмме ниже.

![HTTP Logging Sequence](docs/diagrams/HTTP_filtering_via_LogBook.png)

В этой диаграмме:
    - Client вызывает приложение.
    - LogbookFilter (через FilterRegistrationBean) перехватывает HTTP-запрос и ответ.
    - Через цепочку RequestFilters, ResponseFilters, HeaderFilter, QueryFilter, BodyFilter применяется маскирование чувствительных данных.
    - HttpLogFormatter отвечает за RFC7230 формат и исключение заголовков.
    - HttpLogWriter пишет строку.

consoleSink — компонент, обеспечивающий вывод логов HTTP-запросов и ответов в системный лог (консоль или файл) с применением настроенного HttpLogFormatter и HttpLogWriter
через Logback-аппендеры.

Для кастомизации фильтрации в Web-приложении, использующем библиотеку, необходимо реализовать соответствующий интерфейс из пакета "org.zalando.logbook"
и зарегистрировать бин в Spring-контексте:

| **Интерфейс**      | **Назначение**                            | **Подробно**                                                                            |
|--------------------|-------------------------------------------|-----------------------------------------------------------------------------------------|
| **RequestFilter**  | Фильтрует HTTP-запрос перед логированием  | Маскировка или удаление конфиденциальных полей в теле запроса (например: пароль, токен) |
| **ResponseFilter** | Фильтрует HTTP-ответ перед логированием   | Маскировка чувствительных данных в теле ответа или заголовках                           |
| **HeaderFilter**   | Фильтрует заголовки HTTP-запроса и ответа | Исключение или маскировка заголовков, содержащих секретные токены или ключи API         |
| **QueryFilter**    | Фильтрует параметры query string          | Маскировка параметров запроса (например: password или api_key)                          |
| **BodyFilter**     | Фильтрует тело запроса или ответа         | Маскировка или удаление чувствительных данных в JSON перед записью в лог                |

Кастомная реализация фильтра автоматически включается в соответствующий список фильтров при создании бина класса "org.zalando.logbook.Logbook" через Spring DI:

```java
@Bean
public Logbook logbook(List<RequestFilter> requestFilters,
                       List<ResponseFilter> responseFilters,
                       List<HeaderFilter> headerFilters,
                       List<QueryFilter> queryFilters,
                       List<BodyFilter> bodyFilters,
                       Sink consoleSink) {
     return Logbook.builder()
               .requestFilters(requestFilters)
               .responseFilters(responseFilters)
               .headerFilters(headerFilters)
               .queryFilters(queryFilters)
               .bodyFilters(bodyFilters)
               .sink(consoleSink)
               .build();
}
```

Logbook перехватывает HTTP-запрос и ответ для логирования через Servlet-фильтр:
```java
@Bean
@ConditionalOnProperty(prefix = "platform.http-logging", name = "logbook.servlet-filter.enabled", havingValue = "true", matchIfMissing = true)
public FilterRegistrationBean<LogbookFilter> platformLogbookFilterRegistration(Logbook logbook) {
    FilterRegistrationBean<LogbookFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(new LogbookFilter(logbook));
    registration.addUrlPatterns("/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
}
```

При необходимости библиотечный Servlet-фильтр можно отключить через свойство "platform.http-logging.servlet-filter.enabled" и
заменить на собственную реализацию Servlet-фильтра для интеграции с Logbook.

## Динамическое изменение уровня логирования в runtime

**GET /actuator/loggers** — список логгеров.

**GET /actuator/loggers/{name}** — инфо по логгеру.

**POST /actuator/loggers/{name}** — изменить:
```json
{"configuredLevel": "TRACE" }
```


### 1) Как посмотреть текущие уровни
#### 1.1 Список всех логгеров

```
curl -s http://{service-address}/actuator/loggers
```

#### 1.2 Конфигурация конкретного логгера

```
curl -s http://service-address/actuator/loggers/имя класса
```
Важно! Если нужно изменить или просмотреть уровень логирования для всего пакета, нужно обращаться ко всему пакету.

#### Примеры:
| **Цель**                                  | **Имя логгера**       | **Пример запроса**                                 |
|-------------------------------------------|-----------------------|----------------------------------------------------|
| **Изменить уровень для всего приложения** | ROOT                  | /actuator/loggers/ROOT                             |
| **Для определенного пакета**              | имя пакета            | /actuator/loggers/space.br1440                     |
| **Для одного класса**                     | имя пакета.имя класса | /actuator/loggers/space.br1440/ObtsService         |
| **Для JPA/Hibernate**                     | org.hibernate.SQL     | /actuator/loggers/space.br1440/org.hibernate.SQL   |


Ответ покажет:

**configuredLevel** — явно заданный через рантайм-вызов уровень (может быть null);

**effectiveLevel** — фактический применённый уровень с учётом наследования.

### 2) Примеры изменений уровня логирования на лету

#### 2.1 Поднять уровень до DEBUG
```
   curl -X POST "http://service-address/actuator/loggers/имя логгера" \
   -H "Content-Type: application/json" \
   -d '{"configuredLevel":"DEBUG"}'
```
#### 2.2 Включить TRACE для конкретного класса
```
curl -X POST "http://service-address/actuator/loggers/имя пакета/имя класса" \
-H "Content-Type: application/json" \
-d '{"configuredLevel":"TRACE"}'
```
#### 2.3 Снизить уровень ROOT до INFO
```
curl -X POST "http://service-address/actuator/loggers/ROOT" \
-H "Content-Type: application/json" \
-d '{"configuredLevel":"INFO"}'
```
#### 2.4 Сбросить принудительный уровень (вернуться к конфигу)
```
curl -X POST "http://service-address/actuator/loggers/имя логгера" \
-H "Content-Type: application/json" \
-d '{"configuredLevel": null}'
```
### 3) Пример минимальной конфигурации .yaml
```yaml
server:
  port: "Необходимый порт"

management:
  server:
    port: "Порт для Actuator"
  endpoints:
    web:
      exposure:
        include: "loggers" //Нужно добавить loggers, если его нет
```
