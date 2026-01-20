# Библиотека Leader Election

Java-библиотека для ShedLock и выбора лидера в Spring Boot приложениях с использованием Redis как хранилища блокировок.

Она предоставляет функционал, с помощью которого гарантируется, что запланированные задачи и критически важные операции выполняются только одним экземпляром приложения за раз, 
предотвращая повреждение данных и конфликты ресурсов в развертываниях с несколькими репликами.

Подробная техническая документация находится на [Док Портале](https://doc-portal.platform.stg.corp.1440.space/docs/java/libraries/leader_election_guides/).

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
2. Указать зависимость.
```groovy
dependencies {
    implementation 'space.br1440.platform.starters:spring-boot-starter-platform-leader-election:{Желаемая версия}'
}
```