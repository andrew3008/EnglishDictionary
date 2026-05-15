# Библиотека spring-boot-starter-platform-metrics

## Описание

Данная библиотека добавляет в проект набор универсальных метрик для HTTP/gRPC вызовов. 
Все метрики, добавленные данной библиотекой, имеют префикс platform_ 

**Важно!** gRPC метрики собираются только при использовании официального стартера https://github.com/spring-projects/spring-, при этом не нужно добавлять зависимость io.grpc в явном виде!

Более подробно о метриках описано [тут](https://c.1440.space/pages/viewpage.action?pageId=508642331#id-[WIP]%D0%A2%D1%80%D0%B5%D0%B1%D0%BE%D0%B2%D0%B0%D0%BD%D0%B8%D1%8F%D0%BA%D0%BC%D0%B5%D1%82%D1%80%D0%B8%D0%BA%D0%B0%D0%BC%D1%81%D0%B5%D1%80%D0%B2%D0%B8%D1%81%D0%BE%D0%B2-%D0%AD%D1%82%D0%B0%D0%BB%D0%BE%D0%BD%D0%BD%D1%8B%D0%B5%D0%BC%D0%B5%D1%82%D1%80%D0%B8%D0%BA%D0%B8)


## Использование

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
    implementation 'space.br1440.platform.starters:spring-boot-starter-platform-metrics:{Желаемая версия}'
}
```

**Отдельно тянуть spring-boot-actuator не нужно! Он приедет транзитивно с этим стартером.** 

## Управление HTTP-метриками
Для дополнительной конфигурации поддерживаются две возможности:
1. Переключения режима формирования path
2. Отключение отдельных тегов у конкретных метрик


### Режим формирования PATH

Свойство:
```yaml
platform:
  metrics:
    http:
      path-mode: normalized
```

Поддерживаются два значения:
- normalized
- raw

#### normalized
В этом режиме в метрику попадает не raw path, а шаблон endpoint’а из Spring mapping.

**Пример:**

Было:
```
/api/download/firmware/artifact/10758
/api/download/firmware/artifact/10761
```

Станет:
```
/api/download/firmware/artifact/{id}
```

**raw**

В этом режиме библиотека пишет в метрику исходный request path без нормализации.

Пример:
```
/api/download/firmware/artifact/10758
/api/download/firmware/artifact/10761
```

## Отключение отдельных тегов
Например, если для  platform.http.requests не нужен тег status, его можно убрать через конфигурацию application.yml.

Пример:
```yaml
platform:
  metrics:
    labels:
      metrics:
        "[platform.http.requests]":
          disabled-tags:
            - status
```

Механизм отключения тегов работает не только для HTTP-метрик.

**Примеры:**
```yaml
platform:
  metrics:
    labels:
      metrics:
        "[platform.grpc.server.sent]":
          disabled-tags:
            - rpc_status_code
```

```yaml
platform:
  metrics:
    labels:
      metrics:
        "[platform_consumer_messages_total]":
          disabled-tags:
            - client_id
```

## Поведение по умолчанию

Если явно не задано, сервис будет запускаться с такой конфигурацией. Прописывать её необязательно:
```yaml
platform:
  metrics:
    http:
      path-mode: normalized
```