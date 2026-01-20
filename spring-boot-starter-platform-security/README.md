## Библиотека spring-boot-starter-platform-security

### Описание


Библиотека для аутентификации/авторизации пользователей на http API.

Сделано поверх стандартного org.springframework.boot:spring-boot-starter-oauth2-resource-server и заменяет его.

Так же включено в библиотеку:
1. ClaimUtil - утилитарный класс для работы с claims jwt токена.
2. Готовый конвертер jwt токенов - Converter<Jwt, ? extends AbstractAuthenticationToken> br1440JwtAuthenticationConverter
3. Готовые конфигурации безопасности для разных профилей.

<h2>Для подключения библиотеки:</h2>

1. Указать репозитории зависимостей:
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
2. Указать зависимость. ВНИМАНИЕ!!! Стандартный org.springframework.boot:spring-boot-starter-oauth2-resource-server может вызывать конфликты.
```groovy
dependencies {
    implementation 'space.br1440.platform.starters:spring-boot-starter-platform-security:{Желаемая версия}'
}
```
3. Создать файл с маппингом пермиссий на роли в ресурсах сервиса
```yaml
role_permission_binding:
  ROLE:
    - PERMISSION
```
4. Указать параметры idp, cors и файла маппинга в application.yaml
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${KEYCLOAK_CERT_URL}

security:
  origins:
    allowed: origins для cors через запятую.(http://localhost:[*],https://graveyard.1440.space,https://graveyard-test.1440.space,https://mcc-graveyard.1440.space)
  policy:
    file-location: расположение файла пермиссий в ресурсах сервиса.(policy.yaml)
```

<h2>Профили:</h2>

Есть 2 уже готовые и автоматически подключаемые конфигурации безопасности:
1. профиль local - разрешен доступ ко всем ручкам без аутентификации/авторизации.
2. все остальные профили - разрешен доступ без проверки до ручек /service, /swagger-ui, /v3/api-docs, /actuator. Ручки /api защищены.

<h2>Защита ручек пермиссиями:</h2>

```java
    @GetMapping("/test")
    @PreAuthorize("hasPermission('PERMISSION')")
    public String test() {
        return "SUCCESS";
    }
```