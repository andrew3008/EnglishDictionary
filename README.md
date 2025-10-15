## Моно-репозиторий spring-boot-platform-starters

### Описание

#### Данный репозиторий содержит в себе следующие библиотеки:
- [platform-logging-java;](./spring-boot-starter-platform-logging/README.md)
- [spring-boot-starter-platform-metrics;](./spring-boot-starter-platform-metrics/README.md)
- [spring-boot-starter-oauth2-resource-server.](./spring-boot-starter-platform-security/README.md)

Для ознакомления с полной документацией нужной Вам библиотеки перейдите по одной из ссылок выше.

#### platform-logging-java
Данная библиотека предоставляет:
- дефолтные Logback Appender’ы для логирования в консоль, logstash, JSON-файл;
- гибкую настройку логирования body и headers HTTP-запросов и ответов;
- возможность расширения правил маскирования чувствительных данных;
- интеграцию в Spring Boot-проекты через авто-конфигурацию.

#### spring-boot-starter-platform-metrics
Данная библиотека добавляет в проект набор универсальных метрик для HTTP/gRPC вызовов.


#### spring-boot-starter-oauth2-resource-server
Библиотека для аутентификации/авторизации пользователей на http API.
Сделано поверх стандартного org.springframework.boot:spring-boot-starter-oauth2-resource-server и заменяет его.

### Использование

Данная библиотека находится в репозитории платформы, поэтому для добавления нужно проверить наличие репозитория в **build.gradle**.

```groovy
repositories {

	maven {
		url = "..."
	}
	maven {
		url = "https://n.1440.space/repository/platform-maven/"
	}
	maven {
		url = "https://n.1440.space/repository/platform-maven-dev/"
	}
}
```

Добавление библиотеки:

```groovy
dependencies {
    implementation 'space.br1440.platform:spring-boot-platform-starters:{current version}'
}
```