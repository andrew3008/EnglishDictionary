# spring-boot-starter-header-based-routing

Spring Boot starter для автоматической передачи заголовка `X-Feature-Branch` между сервисами.

## Что делает

Стартер:

- читает входящий `X-Feature-Branch` из HTTP/gRPC запроса;
- сохраняет его в context;
- автоматически добавляет его в исходящие вызовы через:
    - `RestTemplate`
    - `Feign`
    - `WebClient`
    - `gRPC client`

## Что не делает

Стартер **не** занимается маршрутизацией сам.  
Routing делает инфраструктура: ingress, gateway, service mesh.

---

## Поддерживаемый header

```http
X-Feature-Branch
```

**Пример**
```http
X-Feature-Branch: app-service-mesh-test:feature/PAAS-3171
```

## Как работает

### Если header пришёл во входящий запрос

Ничего руками делать не нужно.

Стартер:
1.	считывает X-Feature-Branch;
2.	кладёт его в RoutingContextHolder;
3.	сам прокидывает его в downstream-вызовы.

### Если сервис сам начинает цепочку вызовов

Нужно вручную положить значение в RoutingContextHolder перед первым вызовом:

```java
try {
    RoutingContextHolder.set("app-service-mesh-test:feature/PAAS-3171");
    client.call();
} finally {
    RoutingContextHolder.clear();
}
```

## Подключение:

```groovy
repositories {
    maven {
        url = "https://n.1440.space/repository/platform-maven/"
    }
    maven {
        url = "https://n.1440.space/repository/platform-maven-dev/"
    }
}
```

```groovy
dependencies {
    implementation project(":platform-starters-common:spring-boot-starter-platform-common-header-based-routing")
}
```

## Конфигурация
```yaml
platform:
  hbrouting:
    enabled: true
    validate-header: true
    max-header-length: 512
```

## Использование

### RestTemplate

Важно использовать RestTemplate как Spring bean:

```java
@Bean
public RestTemplate restTemplate(RestTemplateBuilder builder) {
    return builder.build();
}
```

### Feign
Если Feign есть в classpath, starter сам подключит interceptor.

### WebClient
Используй WebClient.Builder, созданный Spring’ом.

### gRPC
Для Spring gRPC starter регистрирует global client/server interceptors.