# Политика архитектурных тестов tracing

## Назначение

Архитектурный тест защищает границу, нарушение которой приводит к наблюдаемому production-риску: смешению classloader-плоскостей, обходу владельца состояния или политики, утечке implementation-типа в публичный API либо загрузке несовместимого web-стека.

ArchUnit не используется для фиксации истории рефакторинга, полного перечня классов, соглашений об именовании и деталей реализации, которые безопаснее проверяются компилятором или узким unit/contract-тестом.

## Условия добавления правила

Новое правило допускается только при одновременном выполнении следующих условий:

1. Назван владелец архитектурной границы.
2. Сформулирован конкретный production failure mode.
3. Проверяется направление зависимости, доступ к capability или единственность composition owner, а не текущее устройство каждого класса.
4. Нужную гарантию нельзя дешевле получить через Gradle dependency graph, Java visibility, compilation или поведенческий тест.
5. Scope импорта соответствует модулю-потребителю и не создаёт ложных результатов из-за случайного test/runtime classpath.
6. Для custom predicate или нетривиального import scope существует компактное negative sensitivity доказательство.

Решение о правиле должно содержать владельца, failure mode и критерий удаления. Правила без активного production-consumer удаляются вместе с self-test и fixtures.

## Запрещённые паттерны

Без отдельного решения архитектурного комитета не добавляются:

- exact allowlist всех типов или методов крупного production-пакета;
- ABI snapshot, требующий обновления при каждом корректном расширении слоя;
- проверки отсутствия давно удалённых имён, пакетов и migration-этапов;
- требования к `final`, private-конструкторам, default/static методам и форме DTO, если это обычный языковой контракт;
- центральный inventory конфигурации, документации или исходных файлов;
- дублирующие module launcher с одинаковым `AnalyzeClasses` scope.

Exact allowlist допустим только для явно утверждённой security- или published-API-границы, если additive изменение само является несовместимостью. Обоснование хранится рядом с тестом.

## Выбор вида проверки

- Межмодульные зависимости и ownership — ArchUnit в модуле-потребителе.
- Поля, методы, enum и validator semantics — unit/contract test владельца.
- Содержимое JAR, ServiceLoader и embedded extension — отдельный artifact test, зависящий от собранного артефакта.
- Запуск `premain` и production wiring — отдельный subprocess qualification gate.
- Dependency/publication metadata и воспроизводимость — Gradle/release pipeline.
- Spring registry — узкий integrity test без ArchUnit.

## Организация reusable rules

Shared rule размещается в `platform-tracing-testkit`, только если у него есть минимум два module consumer либо один consumer с действительно переиспользуемым custom predicate. Каждый модуль сохраняет собственный launcher, когда ему требуется отдельный `AnalyzeClasses` scope.

Self-test shared rule проверяет чувствительность предиката минимальным набором positive/negative fixtures. Он не повторяет проверки каждого consumer и не фиксирует точную форму production API.

## Сопровождение

При удалении или объединении consumer suite выполняется caller scan shared rule library. Orphan rules, self-tests и fixtures удаляются атомарно. Метрики количества suite не включают artifact, registry и обычные contract tests.

Default module `test` должен оставаться быстрым. Artifact и subprocess qualification запускаются отдельными задачами; release/build gate явно вызывает нужную категорию, а тест не получает скрытую зависимость от документационного inventory.
