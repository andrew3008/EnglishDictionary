# Библиотека UID1440
Библиотека предназначена для локальной генерации и валидации UID без обращения к внешним сервисам. Для генерации UUIDv7 использовалась библиотека UUID Generator: https://github.com/f4b6a3/uuid-creator.

## Подключение библиотеки:</h2>

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
2. Указать зависимость.
```groovy
dependencies {
    implementation 'space.br1440.platform.starters:spring-boot-starter-uid1440:{Желаемая версия}'
}
```

## Формат UID

UID имеет фиксированный строковый формат:

``` sql
{domain}-{entity}-{uuidv7_without_dashes}
```

Пример
```
UMS-DEVICE-0196fd907502787bbf876695e03172e6
```

| Часть                                    | Описание                                      |
|------------------------------------------|-----------------------------------------------|
| `domain`                                 | Домен (enum `Domain`)                         |
| `entity`                                 | Идентификатор сущности                        |
| `uuidV7`                                 | UUID версии 7, без дефисов, в нижнем регистре |

## Публичные API библиотеки
### Генерация UID

``` java
String uid = Uid1440.generateUID(Domain.UMS, "DEVICE");
```

При генерации `domain` и `entity` валидируются, `UUIDv7` генерируется внутри библиотеки, и проект не передаёт UUID напрямую.

При некорректных аргументах выбрасывается Uid1440Exception.

### Валидация UID

``` java
boolean valid = Uid1440.validateUID(uid);
```

Метод проверяет формат UID, валидирует домен, валидирует entity, проверяет UUID на соответствие UUIDv7.

**Метод никогда не выбрасывает исключений и возвращает:**

`true` — UID корректен.

`false`— UID невалиден.

## Обработка ошибок

Все ошибки, связанные с UID, приводятся к одному типу исключения:

``` java
public class Uid1440Exception extends IllegalArgumentException
```

Это позволяет отличать ошибки UID от прочих `IllegalArgumentException`.