# 13b — Пост-совещательный пакет репозиторных спайков

## Минимальный набор исполняемых доказательств до финального арбитража

| Поле | Значение |
|---|---|
| Репозиторий | `E:\Platform_Traces`, ветка `refactor/cursor-rules`, HEAD `9fc01d7` |
| Java | `21.0.6+8-LTS-188`; toolchain `build.gradle:25` |
| Активных модулей | 16 — **инвариант, изменению не подлежит** |
| Парный документ | `13a-council-consensus-and-dissent-register.md` |
| Implementation GO | **NOT_GRANTED** — ни один спайк ниже его не выдаёт |
| Legacy deletion GO | **BLOCKED** |

---

## 0. Правила, общие для всех спайков

### 0.1 Что спайк является и чем не является

Спайк — это **исполняемое доказательство или опровержение конкретной гипотезы**, дающее артефакт, который переживает сессию и воспроизводим на чужой машине. Спайк — не реализация целевой архитектуры, не рефакторинг и не подготовка к нему.

### 0.2 Жёсткие ограничения

```text
Ни одного нового Gradle-модуля. settings.gradle остаётся с 16 include.
Ни одного compatibility-алиаса.
Ни одного общего configuration-framework.
Старый и новый writer не сосуществуют в опубликованном артефакте.
Ни один спайк не удаляет legacy-код.
Ни один спайк не изменяет production-семантику по умолчанию.
Каждый спайк производит закоммиченный артефакт, а не только вывод в консоль.
Прохождение спайка не выдаёт implementation GO; оно закрывает один гейт.
```

### 0.3 Размещение спайкового кода

Новые source set внутри **существующих** модулей допустимы — это уже установленный в репозитории приём (`platform-tracing-e2e-tests` содержит семь source set: `customRule`, `testExtension`, `jmxWireExtension`, `readinessFixtureExtension`, `e2FailureExtension`, `e1Consumer`, `configProvenance`). Новые модули запрещены.

### 0.4 Форма отчёта каждого спайка

Каждый спайк обязан произвести закоммиченный markdown-отчёт с:

```text
- команда, воспроизводящая результат, дословно;
- точный разрешённый граф зависимостей, где он релевантен;
- одна строка на проверяемое свойство: PASS | FAIL | NOT_RUN;
- сырой вывод или путь к сохранённому артефакту;
- версия JDK, ОС, дата;
- явное перечисление того, что спайк НЕ доказывает.
```

`NOT_RUN` никогда не эквивалентен `PASS`. Пропущенный тест не докладывается как пройденный.

### 0.5 Порядок исполнения

```text
S-02  первым: самый дешёвый, разблокирует больше всего уже сделанных выводов
S-01  и  S-03  параллельно: независимы друг от друга
S-04  ->  S-05 : S-05 требует финального инвентаря
S-06  параллельно всему: governance-трек, не блокируется кодом
```

---

## S-01 — Сравнение примитива состояния

**Гейт:** E3. **Разрешаемый конфликт:** C-01. **Статус входа:** победитель не назначен; Gate E3 фактически не пройден.

### S-01.1 Почему спайк необходим

Инспекция кода (13a §5.1) установила факты, которых не привёл ни один член совета:

```text
tryUpdate  —  неограниченный for(;;) retry-loop, не одиночный CAS
builder и валидатор  —  переисполняются на каждой итерации
проигранная гонка  —  поглощается циклом, до вызывающего не доходит
REJECTED, FAILED, null-кандидат, InterruptedException  ->  один и тот же false
двухаргументная перегрузка валидатора  —  0 production-вызовов
version()  —  поставляется снапшотом, holder не инкрементирует и не проверяет монотонность
prev.version() + 1  —  выписано вручную в 8 местах
normalizeSource  —  реализован независимо 3 раза
SamplerStateHolder.update(SamplerState)  —  публикует снапшот вызывающего без инкремента
после удаления трёх holder'ов  —  0 потребителей, кроме собственного теста
```

Из шести условий реверсии отчёта 04 §15 условие 3 («update semantics cannot distinguish stale conflict from internal failure») **выполнено фактически**, а условие 4 («duplicate version fields or competing sources of truth») выполнено в форме восьмикратного дублирования инкремента. Одновременно установлено, что expected-base one-shot семантика **выразима** без правки API — через `tryUpdate(prev -> prev.version() == expected ? next : null)` — что снимает главный аргумент GPT-5.6.

Следовательно ни `RETAIN_GENERIC`, ни `REFACTOR_DOMAIN_SPECIFIC` не может быть выбран без исполняемого сравнения.

### S-01.2 Варианты

**Вариант A — `VersionedStateHolder` + generic one-shot**

```text
Изменение: добавить в существующий generic-примитив ОДНУ операцию,
           дающую expected-base коммит с РАЗЛИЧАЮЩИМ результатом.
Ограничение: ни одной tracing-концепции в platform-tracing-otel.
             Ни Config Server, ни authority, ни digest, ни диагностики,
             ни audit не проникают в generic-модуль.
Инстанс: ровно один VersionedStateHolder<AcceptedTracingPolicyState>
         в platform-tracing-otel-javaagent-extension.
Обязательно проверить: достаточно ли boolean. Если для классификации
         APPLIED|UNCHANGED|REJECTED|STALE|FAILED требуется расширить
         возвращаемый тип, зафиксировать это как факт варианта A,
         а не обойти внешней логикой.
```

**Вариант B — `AcceptedTracingPolicyStateStore` с доменным `AtomicReference`**

```text
Изменение: package-private store в platform-tracing-otel-javaagent-extension,
           один AtomicReference<AcceptedTracingPolicyState>,
           одна операция compareAndCommit(expectedRuntimeInstanceId,
                                          expectedAcceptedSequence,
                                          candidate).
Ограничение: store владеет ТОЛЬКО ссылкой и переходом. Не владеет
           валидацией, текстом сообщений, audit, метриками, Config Server.
           Нормализация и валидация кандидата завершаются ДО вызова store.
           Внутри store retry-loop отсутствует.
Судьба generic: VersionedStateHolder не удаляется этим спайком.
           Удаление — предмет отдельного deletion GO.
```

### S-01.3 Требуемые доказательства

Оба варианта прогоняются через **идентичный** набор. Ни один пункт не может быть докладан как `PASS` по рассуждению.

| # | Свойство | Как доказывается | Критерий |
|---|---|---|---|
| P1 | Двое конкурентных writer'ов с одной базой: **ровно один `APPLIED`, ровно один `STALE`** | Два потока, общий `CyclicBarrier`, одна и та же прочитанная база, различные кандидаты; повтор не менее 10 000 раз | Ровно 1 `APPLIED` и ровно 1 `STALE` в **каждой** итерации. Ни одной итерации с 2 `APPLIED`. Ни одной итерации, где второй писатель получает неразличимый исход |
| P2 | Никакого скрытого rebase | Проигравший writer обязан наблюдать, что его кандидат **не** применён и что база сместилась | Ни одного случая, когда кандидат проигравшего оказался принятым состоянием |
| P3 | Никакого переисполнения builder'а | Счётчик вызовов внутри builder'а/фабрики кандидата под контентом | Счётчик вызовов = число попыток, **не** больше. Для варианта A это прямая проверка того, поглощает ли retry-loop гонку |
| P4 | Никакого дублирующего источника версии | Статический поиск по репозиторию выражений вида `version() + 1` и присвоений `acceptedSequence` | Ровно **одно** место в production-коде производит следующую версию. Сегодня — 8 (`FACT`) |
| P5 | Никакого tracing-загрязнения generic-модуля | ArchUnit: `platform-tracing-otel` не импортирует ни один тип Agent-extension; ни одного упоминания Config Server/authority/digest/audit в generic-пакете | 0 нарушений. Для варианта B тривиально; для варианта A — обязательный гейт |
| P6 | Один mutable-владелец | ArchUnit/статический поиск: ровно один production-`AtomicReference`, содержащий принятую политику | Ровно 1. Сегодня 6 независимых mutable-владельцев (`FACT`) |
| P7 | Классификация исхода однозначна | Матрица состояний: правильная база + изменённая валидная политика → `APPLIED`; правильная база + эквивалентная нормализованная политика → `UNCHANGED` без CAS; неверная ожидаемая база → `STALE` без CAS; отказ валидации → `REJECTED` без CAS; внутреннее исключение → `FAILED` без CAS | Все пять исходов **различимы вызывающим** по возвращаемому значению. Ни один не схлопывается в другой. Ни один не требует внеполосного контекста для различения |
| P8 | Монотонность версии | Стресс: 4 writer'а, 100 000 попыток; собрать все принятые последовательности | Строго возрастающая, без повторов, без регрессий. Сегодня `SamplerStateHolder.update(SamplerState)` допускает и то и другое (`FACT`) |
| P9 | Глубокая иммутабельность принятого значения | Попытка мутировать любую коллекцию, полученную из принятого состояния | `UnsupportedOperationException` либо защитная копия; ни одной утечки внутренней ссылки |
| P10 | Стоимость чтения на горячем пути | JMH: одно чтение принятого состояния против сегодняшнего per-domain чтения | Одно чтение `AtomicReference`, ноль аллокаций, ноль блокировок на hook. Существующие бюджеты P5/JMH — единственный порог; изобретённые проценты не применяются |
| P11 | ABA | Последовательность: база `Vn` → применить `Vn+1` → применить состояние, **эквивалентное по значению** `Vn`, но с версией `Vn+2`; затем попытка коммита с ожидаемой базой `Vn` | `STALE`. Проверка не полагается на равенство значений. `VersionedStateHolderTest` сегодня ABA-сценария **не содержит** (`FACT`) |
| P12 | Отказ валидации под контентом | Двое writer'ов, кандидат одного невалиден | Невалидный получает `REJECTED`, валидный — `APPLIED`; ни один не получает исход другого. Сегодня оба дают `false` (`FACT`) |

### S-01.4 Обязывающий критерий выигрыша

Критерий формулируется **до** исполнения и применяется механически. Предпочтения не участвуют.

```text
ШАГ 1 — Отбор по обязательным свойствам.
  Вариант, проваливший ЛЮБОЕ из P1, P2, P3, P5, P6, P7, P8, P11, P12,
  дисквалифицируется.

ШАГ 2 — Если дисквалифицирован ровно один вариант:
  выживший объявляется победителем.

ШАГ 3 — Если дисквалифицированы оба:
  ни один вариант не принимается. Требуется третья формулировка,
  и E3 остаётся OPEN. Council re-review обязателен.

ШАГ 4 — Если оба прошли, применяются тай-брейкеры В ЭТОМ ПОРЯДКЕ.
  Первый тай-брейкер, дающий различие, решает; последующие не рассматриваются.

  T1. Размер публичной поверхности generic-модуля.
      Побеждает вариант, добавляющий МЕНЬШЕ публичных членов
      в platform-tracing-otel. Измеряется числом добавленных
      public/protected членов, не строками.

  T2. Число долгоживущих ответственностей.
      Побеждает вариант, оставляющий МЕНЬШЕ типов, у которых
      после cutover есть ровно один потребитель.
      ФАКТ ВХОДА: после удаления трёх доменных holder'ов у generic-типа
      остаётся 0 потребителей. Если вариант A не приобретает второго
      названного потребителя, он проигрывает T2.

  T3. Строк тестов, требуемых для доказательства P7.
      Побеждает вариант, требующий меньше.

  T4. При полном равенстве — побеждает вариант B,
      поскольку он не расширяет опубликованную generic-поверхность.
```

Дополнительное правило, вытекающее из фактов: **если для достижения P7 варианту A требуется изменить возвращаемый тип операции коммита (а не только добавить `compareAndSet(T, T)`), это регистрируется как выполнение условия реверсии №1 отчёта 04 §15**, и вариант A проходит только при выполнении T2, то есть только при наличии второго названного потребителя generic-типа.

### S-01.5 Артефакты

```text
Отчёт: docs/research/state-primitive-spike-s01.md
  - таблица P1..P12 x {A, B} со значениями PASS|FAIL|NOT_RUN
  - точное число вызовов builder'а под контентом для обоих вариантов
  - точное число мест, производящих следующую версию, для обоих
  - число добавленных публичных членов generic-модуля для варианта A
  - применённый тай-брейкер и объявленный победитель
  - JMH до/после для P10
Тесты: в существующих модулях, в отдельном source set либо под src/test
Сохранённый JMH-вывод
```

### S-01.6 Чего S-01 не доказывает

Не доказывает корректность Config Server bridge, безопасность приватного writer'а, схему Category A и производительность production-пути. Не выдаёт разрешения удалять `VersionedStateHolder` или три доменных holder'а.

---

## S-02 — Восстановление и воспроизведение Step 0B

**Гейт:** E9 (новый; см. 13a §8). **Разрешаемый конфликт:** C-02. **Статус входа:** `PRESENT_BUT_UNTRACKED`.

### S-02.1 Точная задача

Это **не** задача написания тестов. Код существует и корректен. Задача — превратить `FACT_UNTRACKED` в `REPRODUCIBLE_FACT`.

```text
Текущее состояние (FACT):
  git status --short  ->   M platform-tracing-e2e-tests/build.gradle
                          ?? platform-tracing-e2e-tests/src/configProvenance/
  git ls-files … | Select-String configProvenance   ->  пусто
  git show HEAD:…/build.gradle                      ->  ни configProvenance, ни spring-cloud
  git diff                                          ->  32 вставки, 0 удалений
  build/ модуля                                     ->  пуст
  .gitignore                                        ->  путь не исключает
```

### S-02.2 Шаги

```text
1. Закоммитить 32 вставки в platform-tracing-e2e-tests/build.gradle:
     sourceSets.configProvenance                       (строки 58-65)
     зависимости configProvenance*                     (строки 187-194)
     task configProvenanceTest                         (строки 325-337)

2. Закоммитить все 8 файлов platform-tracing-e2e-tests/src/configProvenance/:
     configserver/ConfigServerProvenanceCharacterizationTest.java
     configserver/ConfigServerGitProcessCharacterizationTest.java
     configserver/ConfigServerProcessApplication.java
     configserver/ConfigServerTestApplication.java
     configserver/support/ProvenanceInspector.java
     configserver/support/ConfigServerResponseStub.java
     configserver/support/EvidenceRecorder.java
     configserver/support/ConfigClientHarness.java

3. Решить вопрос подключения к CI явно, а не по умолчанию.
   ФАКТ: configProvenanceTest НЕ подключён к check/build, и, в отличие
   от task test, НЕ имеет guard'а onlyIf { project.hasProperty('runE2e') }.
   Требуется одно из двух, зафиксированное в отчёте:
     (a) подключить к отдельному явному verification-task, либо
     (b) оставить вызываемым только вручную и задокументировать,
         что CI его не исполняет.
   Молчаливое сохранение текущего состояния недопустимо: оно даёт
   доказательство, которое никогда не деградирует заметно.

4. Исполнить и СОХРАНИТЬ артефакты:
     ./gradlew.bat :platform-tracing-e2e-tests:configProvenanceTest --no-daemon
   Сохранить build/reports/config-provenance/evidence.txt — файл,
   который task уже пишет через system property config.provenance.evidence.file.
   Сохранить XML/HTML результаты тестов.

5. Зафиксировать точный разрешённый граф зависимостей:
     ./gradlew.bat :platform-tracing-e2e-tests:dependencies \
        --configuration configProvenanceRuntimeClasspath --no-daemon
```

### S-02.3 Требуемые доказательства

| # | Свойство | Критерий |
|---|---|---|
| Q1 | Source set и task существуют в checkout | `git show HEAD:platform-tracing-e2e-tests/build.gradle` содержит `configProvenance` и `configProvenanceTest` |
| Q2 | Все 8 файлов отслеживаются | `git ls-files platform-tracing-e2e-tests/src/configProvenance` возвращает 8 путей |
| Q3 | T1–T10 исполнимы и исполнены | Результаты тестов содержат все 10 методов `t1_…`–`t10_…`, каждый с явным PASS/FAIL. T2 испускает две записи доказательств (`T2-local-yaml`, `T2-command-line`), что соответствует двухчастному вердикту документа |
| Q4 | Git-backed процессный тест Config Server исполнен | `realConfigServerProcessReadsGitRevisionAndRefreshesAfterCommit` — PASS/FAIL. Требует `git` в `PATH`; Docker не требуется. Форк дочерней JVM, polling готовности с границей 45 с — зафиксировать фактическое время |
| Q5 | Точный граф зависимостей зафиксирован | Отчёт содержит вывод `dependencies`. Ожидаемое (уже подтверждено как `FACT`): `spring-boot:3.5.6`, `spring-cloud-config-client:4.3.0`, `spring-cloud-context:4.3.0`, `spring-cloud-config-server:4.3.0`, `org.eclipse.jgit:6.10.1.202505221210-r` |
| Q6 | Тесты прогнаны на одобренном стеке-кандидате production | **Зависимость от S-06.** Пока S-06 не одобрил patch-level Boot и поезд Cloud, Q6 = `NOT_RUN`. Прогон на 3.5.6/2025.0.0 доказывает **изолированный** стек, а не production-стек |
| Q7 | Отчёт перечисляет, что не доказано | Явно: production bridge, aggregate apply, cross-domain атомарность, начальное поведение readiness, runtime structural drift, обработка refresh-storm, M10/M10c/M10d |

### S-02.4 Какие выводы разблокирует прохождение

Переводит из `UNVERIFIED` в `FACT` (13a §2.1): доставку полного обновлённого Environment (T10) и вместе с ней отклонение атаки A-04 отчёта 12; присутствие удалённых ключей в `getKeys()` со значением `null` (T9); per-key provenance; прямое чтение revision из marker-source и устойчивость к спуфингу (T8); fail-closed при локальном override policy-ключа (T6).

### S-02.5 Чего S-02 не доказывает

Ничего о production. Изолированный source set с собственным `enforcedPlatform` доказывает поведение Config Client, а не то, что репозиторий может поставлять Config Client потребителям. Это предмет S-06.

---

## S-03 — Приватный `MBeanServer`

**Гейт:** E1 + E2. **Разрешаемый конфликт:** C-06 (механизм уже разрешён). **Статус входа:** механизм `ACCEPT`, внутрирепозиторное подтверждение `OPEN`.

### S-03.1 Что уже установлено вне репозитория

Исполняемый JDK-спайк на `java 21.0.6+8-LTS-188` (13a §5.4) дал 13 наблюдений. Они **не подлежат повторному выяснению**; S-03 обязан им соответствовать.

```text
createMBeanServer(domain)   ->  обнаруживается через findMBeanServer(null)
newMBeanServer(domain)      ->  НЕ обнаруживается         => рекомендация Gemini нерабочая
findMBeanServer(String)     ->  сопоставляет MBeanServerId, НЕ default domain
                                findMBeanServer("space.br1440…internal") = 0
                                findMBeanServer("<MBeanServerId>")       = 1
                                MBeanServerId = host + timestamp, непредсказуем
платформенный сервер        ->  ТОЖЕ находится в списке фабрики
                                => «найти ровно один» требует marker-MBean
один ObjectName             ->  регистрируется на обоих серверах одновременно
дубликат createMBeanServer  ->  РАЗРЕШЁН JDK; уникальность обеспечивает приложение
releaseMBeanServer          ->  снимает только discovery-ссылку;
                                сервер и MBean остаются рабочими  => release != shutdown
releaseMBeanServer(newMBeanServer(...))  ->  IllegalArgumentException
удалённый коннектор         ->  приватный writer невидим; invoke -> InstanceNotFoundException
удалённый коннектор         ->  write-op на ПЛАТФОРМЕННОМ сервере вызывается УСПЕШНО
```

Из последнего пункта следует, что обход из отчёта 07 §11.1 существует в текущем коде: `PlatformTracingJmxRegistrar:80-82` регистрирует всё на `ManagementFactory.getPlatformMBeanServer()`, включая `applyPolicy(CompositeData)`.

### S-03.2 Обязательное предусловие — E1

Спайк **не может** начаться с удаления или переноса ни одного члена JMX, потому что baseline отсутствует (13a §2.2). Сначала:

```text
1. Сгенерировать baseline живым интроспектированием и ЗАКОММИТИТЬ:
     на каждый из 8 ObjectName:
       имя атрибута, OpenType, read/write
       сигнатура операции, impact
       нотификации
       названный потребитель (или явное «потребитель не назван»)
   Ожидаемая величина (FACT): 71 член, из них 16 мутирующих, 55 read-only.

2. Расширить DomainMBeanJmxComplianceTest до сверки с baseline-файлом.
   ФАКТ: сегодня он проверяет 6 из 71 члена и фикстур не читает,
   поэтому не способен обнаружить добавление, удаление, переименование
   члена или изменение сигнатуры.

3. Устранить незащищённое дублирование таблицы имён:
   PlatformTracingJmxObjectNames (Spring) дублирует 7 из 8 ObjectName;
   его Javadoc:9 ссылается на ObjectNameConsistencyTest, которого
   НЕ СУЩЕСТВУЕТ (FACT_ABSENCE). Либо создать тест, либо снять дублирование.
```

### S-03.3 Требуемые доказательства

Восемь свойств постановки плюс четыре, добавленных JDK-спайком.

| # | Свойство | Как доказывается | Критерий |
|---|---|---|---|
| M1 | Writer отсутствует на платформенном сервере | В процессе: `platformServer.isRegistered(writerObjectName)` | `false` |
| M2 | Удалённый коннектор не может запросить или вызвать writer | Дочерняя JVM с `com.sun.management.jmxremote.port`; клиент подключается извне | `queryNames("space.br1440.platform.tracing:*")` не содержит writer; `invoke` → `InstanceNotFoundException`. Одновременно проверить: read-only проекция **видна** |
| M3 | Spring находит ровно один приватный marker | `findMBeanServer(null)` → фильтр по `isRegistered(markerObjectName)` | Ровно 1. **Реализация обязана перечислять список и фильтровать по marker**, а не полагаться на `findMBeanServer(domain)`, который возвращает 0 |
| M4 | Cross-classloader round-trip `CompositeData` | Настоящая топология Agent-extension: Spring-сторона кодирует, Agent-сторона декодирует, ответ возвращается | Симметричный round-trip без общего прикладного DTO; только JDK Open Types и примитивы пересекают границу |
| M5 | Shutdown/release | Явный `unregisterMBean` всех членов **затем** `releaseMBeanServer` | После shutdown: `findMBeanServer(null)` не содержит приватный сервер **и** ни один MBean на нём не зарегистрирован. **Проверить оба условия по отдельности**, поскольку `release` сам по себе не снимает регистрации (`FACT`) |
| M6 | Несколько контекстов приложения не дублируют writer | Два Spring-контекста в одной JVM | Ровно один приватный сервер с marker. **JDK этого не обеспечивает** (`FACT` — дубликат домена разрешён), поэтому обязателен прикладной guard; спайк доказывает именно guard |
| M7 | Agent-only топология работает | JVM с агентом, без Spring | Приватный сервер создан, marker установлен, capability опубликована; никакого сбоя из-за отсутствия Spring |
| M8 | `mode=DISABLED` не находит и не мутирует writer | Spring-контекст с `platform.tracing.mode=DISABLED` | Ни один discovery не выполняется; ни одна операция записи не вызывается; никакого клиента не создаётся |
| M9 | Локальный attach / активация management-agent не раскрывает приватный сервер | Динамическая активация JMX-агента после запуска | Приватный сервер не экспортируется |
| M10 | Restart процесса не оставляет утечек | Многократный цикл create/register/unregister/release в одной JVM | Число серверов в списке фабрики возвращается к базовому; ни одного накопления |
| M11 | Capability устанавливается **после** готовности writer'а | Порядок: приватный сервер → codec → handler → store → **затем** `CONFIG_SNAPSHOT_V1` | Ни один потребитель не наблюдает capability раньше готовности writer'а. `FACT_ABSENCE` входа: `PlatformExtensionCapability` содержит 7 констант, `CONFIG_SNAPSHOT_V1` отсутствует |
| M12 | Платформенная проекция — только чтение | Интроспекция всех 8 ObjectName на платформенном сервере после переноса | Ноль мутирующих операций и ноль writable-атрибутов. Сегодня 16 (`FACT`) — этот критерий проверяется в спайке, но **удаление** требует отдельного deletion GO |

### S-03.4 Артефакты

```text
docs/research/private-mbeanserver-spike-s03.md
  - M1..M12 -> PASS|FAIL|NOT_RUN
  - точный механизм discovery, дословно, с обоснованием
    почему findMBeanServer(domain) не используется
  - сырой вывод процессных тестов
Закоммиченный baseline JMX-контракта (предусловие E1)
Расширенный DomainMBeanJmxComplianceTest, сверяющийся с baseline
```

### S-03.5 Условие redesign

Если M2, M3, M6 или M8 проваливаются и не могут быть исправлены прикладным guard'ом, приватный writer как механизм отвергается, и требуется равно принудительная альтернатива — **не документация**. Отчёт 12 называет это критическим общим гейтом; он остаётся таковым, но теперь с разрешённым механизмом и известными подводными камнями.

---

## S-04 — Генерируемый инвентарь свойств и переменных окружения

**Гейт:** E4. **Разрешаемый конфликт:** C-03. **Статус входа:** точные числа получены; disposition-таблица отсутствует.

### S-04.1 Установленная база

Числа из 13a §2.3 — вход, а не результат:

```text
TracingProperties листовых полей                      36
ExtensionPropertyNames констант                       42  (из них 2 не config, 1 импорт)
PropagationDefaults PROP_*                            12  (проигнорированы всеми подсчётами совета)
PlatformResourceProvider PROP_*                        7  (проигнорированы всеми подсчётами совета)

различных CONFIG_PROPERTY platform.tracing.*          78
различных ENV_VARIABLE, читаемых кодом                27
двойных объявлений (один точный ключ в двух плоскостях) 17

«64» и «67» — оба MODEL_ERROR
```

### S-04.2 Задача

Сгенерировать `docs/research/tracing-configuration-key-disposition.md`: **одна строка на точное имя, без wildcard-строк**.

Обязательные колонки:

```text
exact name
type            (CONFIG_PROPERTY | ENV_VARIABLE | RELAXED_BINDING_ALIAS |
                 RESOURCE_ATTRIBUTE | STANDARD_OTEL_KEY |
                 IMPORTED_PLATFORM_KEY | FIXTURE_ONLY_DEAD)
declaration     (file:line объявляющей константы или поля)
reader          (file:line КАЖДОГО читателя; «нет читателя» пишется явно)
consumer        (компонент времени исполнения, который наблюдает значение)
default owner   (Agent bootstrap | Spring startup | orchestration | внешний)
target disposition (DELETE | RENAME | KEEP | MOVE_OWNERSHIP | EXTERNAL_KEEP)
```

### S-04.3 Обязательные к разрешению пункты

Каждый — установленный `FACT`; ни один не может быть оставлен без решения.

| # | Пункт | Почему блокирует |
|---|---|---|
| N1 | **Коллизия целевого namespace.** `RESOURCE_POLICY_VERSION_ATTR = "platform.tracing.policy.version"` — resource-атрибут (`ResourceAttributeResolver:45`, `ResourceStartupDiagnostics:27`), а `platform.tracing.policy.*` — целевой namespace runtime-политики | Имя уже занято. Миграция создала бы столкновение config-ключа и атрибута |
| N2 | **Два независимых семейства baggage.** `baggage.enabled\|allowlist-keys\|deny-patterns` читает `BaggageExtensionConfig`; `propagation.baggage.enabled\|allowed-keys\|deny-patterns` читает `BaggagePropagationCustomizer` | Два источника истины для одного понятия внутри одного модуля |
| N3 | **Расхождение написаний force-record.** Spring `sampling.force-record-header-values` против agent `sampling.force-record-values` | Оба реальны, оба читаются. Category A обязана иметь одно каноническое имя |
| N4 | **Дрейф типов у 2 из 17 дублей.** `platform.tracing.mode`: `SdkMode AGENT` против String `null`; `sampling.route-ratios`: `Map<String,Double>` против `Map<String,String>` | Схема wire не может быть определена при дрейфе типа |
| N5 | **Дублирующий источник дефолта.** `scrubbing.built-in-rules`: Spring хардкодит 12 литералов, agent выводит из `BuiltInSpanAttributeScrubbingRules.defaultConfigNames()`; сегодня совпадают | Молчаливое расхождение при любом изменении |
| N6 | **5 никогда не вызываемых agent-читателей.** `isOutboundEnabled`, `getTrustedHostPatterns`, `isPropagate*` — 0 вызовов в любом source set | Мёртвый код, выглядящий как поддерживаемый канал |
| N7 | **Константа без читателя и без документации.** `platform.tracing.propagation.w3c.diagnostics-only` | Единственное такое имя в четырёх классах констант |
| N8 | **Опубликованный контракт указывает на несуществующий ключ.** `docs/contracts/public-contract-inventory.yaml:704` и `semantic-contract-map.yaml:509` объявляют `platform.tracing.semconv.validation.mode`; реальный ключ — `platform.tracing.semantic.validation-mode` | Опубликованный инвентарь контрактов неверен |
| N9 | **Мёртвые ключи, живущие в фикстурах.** `platform.tracing.enabled` в 14 YAML `platform-tracing-perf-harness` + `gradle/c3-published-consumer/.../ConsumerApplication.java:16`; `platform.tracing.sdk.mode` там же `:17` | Молча игнорируются relaxed binding; консьюмер-фикстура утверждает несуществующий контракт |
| N10 | **Устаревшая проза против кода.** Константа `SDK_MODE` имеет значение `platform.tracing.mode`, но `extension-configuration-refactoring-dossier.md:153,235` и `extension-configuration-package-inventory.md:102,322,725,775` утверждают `platform.tracing.sdk.mode` | Документация опровергнута кодом |
| N11 | **Env-алиас без Java-константы.** `PLATFORM_TRACING_ACTUATOR_MUTATION_ENABLED` в `config/k8s/perf-app-configmap.yaml` | Relaxed-binding алиас, не объявленный в коде |
| N12 | **Устаревшие ObjectName в perf-контроллере.** `PerfAdminController` (модуль `platform-tracing-perf-tests`, не `perf-harness`) хардкодит `type=SamplingControl`, `type=ScrubbingControl`, `type=ExportControl`, `type=DiagnosticsControl`; публикуются `type=Sampling`, `type=Scrubbing`, `type=Export`, `type=Diagnostics` | Все три мутирующих эндпоинта мертвы и маскируются под «extension not registered» |

### S-04.4 Критерий приёмки

```text
- Одна строка на точное имя. Ноль wildcard-строк.
- Сумма строк типа CONFIG_PROPERTY с префиксом platform.tracing.* = 78,
  либо расхождение объяснено построчно.
- Строк типа ENV_VARIABLE = 27, либо расхождение объяснено.
- N1..N12 закрыты, каждый с явным решением.
- Каждое имя без читателя помечено «нет читателя» и получило disposition.
- Ни одного алиаса в целевом состоянии.
- Инвентарь генерируем повторно: команда или скрипт зафиксированы,
  чтобы таблицу можно было перегенерировать и сравнить.
```

---

## S-05 — Пакет решения по Category A

**Гейт:** E5. **Разрешаемый конфликт:** C-04. **Статус входа:** 8 INCLUDE, 2 к решению, 2 EXCLUDE. **Зависимость:** S-04.

### S-05.1 Форма

Компактный пакет решения для SRE и security, содержащий **только спорные runtime-свойства** и явные последствия принятия/отказа. Не повторяет консенсус, не пересказывает архитектуру.

### S-05.2 Уже решённые пункты — вне пакета

Включаются в пакет как контекст на одну строку, не как вопросы.

```text
INCLUDE (8):
  policy.sampling.enabled
  policy.sampling.ratio
  policy.sampling.route-ratios              [требует канонизации типа, N4]
  policy.validation.enabled
  policy.validation.strict                  [при сохранении startup-огибающей
                                             javaagent.validation.strict-runtime-allowed]
  policy.exporter.enabled
  policy.propagation.trace-control-headers.enabled  [точное имя; НЕ propagation.enabled,
                                                     НЕ W3C-gate]
  policy.diagnostics.log-level              [точное имя; НЕ policy.log-level]

EXCLUDE (2):
  policy.scrubbing.built-in-rules   — композиция pipeline контролей приватности;
                                      нарушает прямой запрет; предложено только Fable
  scrubbing.enabled                 — runtime-отключение санитайзера;
                                      отвергнуто всеми тремя членами совета
```

### S-05.3 Два вопроса, требующие решения

**Вопрос 1 → SRE: `policy.sampling.drop-paths`**

```text
Текущее состояние:  мутируемо сегодня через setDropPathPrefixes
                    и updateSamplingPolicy x2
Дефолт:             ["/actuator/health", "/actuator/prometheus", "/actuator/info"]
Что даёт включение: возможность гасить шумные маршруты во время инцидента
                    без restart
Риск:               управляет тем, какой трафик наблюдаем. Ошибочный runtime-drop
                    скрывает именно тот инцидент, который расследуется
Границы при включении: размер списка, длина элемента, только префиксы,
                    запрет регулярных выражений
Последствие ACCEPT: лист входит в схему Category A; требуются граничные тесты
Последствие REJECT: переходит в Category B; изменение требует restart;
                    доменные сеттеры удаляются, значение фиксируется на старте
Требуется от SRE:   названный инцидентный сценарий либо отказ
```

**Вопрос 2 → Security: `policy.sampling.force-record-values`**

```text
Текущее состояние:  мутируемо сегодня через setForceRecordValues
                    и updateSamplingPolicy x2
Дефолт:             ["on"]
Семантика:          список значений, сопоставляемых с заголовком X-Trace-On
                    (PlatformHeaders.X_TRACE_ON)
Риск:               runtime-расширение списка меняет то, КТО может форсировать
                    запись трейса. Это вектор усиления/DoS: расширенный
                    набор значений позволяет внешнему вызывающему
                    принудительно записывать трейсы в обход сэмплирования
Осложнение:         Spring и agent используют РАЗНЫЕ написания (N3);
                    каноническое имя обязано быть выбрано до решения
Границы при включении: размер списка, длина элемента, запрет wildcard,
                    метрика счётчика forced-decision
Последствие ACCEPT: лист входит в схему; требуется тест на усиление
Последствие REJECT: переходит в Category B; значение фиксируется на старте
Требуется от security: решение по вектору усиления
```

### S-05.4 Три пункта немедленной оценки, не зависящие от GO

Пакет обязан содержать их отдельно, потому что это дефекты **текущего** pre-production кода, а не свойства целевой архитектуры.

```text
SEC-4  applyPolicy возвращает SUCCESS на запрос отключения скрабинга,
       ничего не применяя.
       Доказательство: валидатор распознаёт 16 ключей
       (RuntimePolicyControlDomainValidator.RUNTIME_POLICY_KEYS:42-58);
       applier применяет 2 домена (JmxRuntimePolicyApplier.apply:67-68);
       обработчик возвращает success(operation):98.
       8 из 16 ключей отбрасываются молча, включая scrubbing.enabled.
       Классификация: приватность-релевантный silent-success.

SEC-5  15 из 16 мутирующих JMX-операций не имеют gate и вызываемы
       удалённо на платформенном сервере.
       Доказательство: gate RuntimeControlMutationPolicy покрывает
       только applyPolicy; исполняемый спайк подтвердил, что write-op
       на платформенном сервере вызывается удалённо УСПЕШНО.

SEC-6  Листенер RefreshScope полностью без gate.
       Доказательство: TracingRefreshScopeAutoConfiguration:35-38 —
       единственное условие @ConditionalOnClass(RefreshScope.class);
       строки 55-62 вызывают applyAll безусловно.
       POST /actuator/refresh мутирует живую политику агента.
```

### S-05.5 Критерий приёмки

```text
- SRE-1 и SEC-1 получили ответ письменно.
- Финальный список Category A заморожен: точные листовые имена,
  границы, классификация секретов, канонические нормализация и digest.
- Схема wire — allowlist: неизвестные поля отвергаются ДО CAS.
- Ни один пропущенный ключ не попадает в Category A по умолчанию.
- SEC-4, SEC-5, SEC-6 получили решение: исправить сейчас либо
  зафиксировать принятие риска до cutover.
```

---

## S-06 — Матрица зависимостей production Config Client

**Гейт:** E6. **Разрешаемый конфликт:** C-05. **Статус входа:** обнаружен независимый дефект.

### S-06.1 Дефект, который необходимо исправить независимо от Config Server

```text
FACT   platform-tracing-bom/build.gradle:29
         api "org.springframework.cloud:spring-cloud-context:${springCloudContextVersion}"
FACT   gradle.properties:13   springCloudContextVersion=4.1.5
FACT   gradle.properties:10   springBootVersion=3.5.5
FACT   docs/SUPPORTED.md:49   «Spring Cloud Context | 4.1.x (optional) | 4.1.5»

Внешний факт (официальная матрица поездов Spring Cloud):
       2025.0.x (Northfields) -> Boot 3.5.x ; commons/config 4.3.x
       2023.0.x (Leyton)      -> Boot 3.2.x, 3.3.x ; commons 4.1.x ; ПОМЕЧЕН EOL

spring-cloud-context — артефакт spring-cloud-commons.

СЛЕДСТВИЕ: публикуемый BOM транслирует межпоездный констрейнт на артефакт
из EOL-поезда, в области api, приблизительно 80 сервисам-потребителям,
при закреплённом Boot 3.5.5.

Ни Fable, ни Gemini, ни GPT-5.6 этого не идентифицировали.
```

Исправление этого дефекта (GOV-1) **не требует** принятия решения по Config Server и не должно ждать implementation GO.

### S-06.2 Обязательные семь конфигураций сравнения

| # | Конфигурация | Что проверяется |
|---|---|---|
| C1 | Текущий baseline Boot (3.5.5) + поезд Cloud-кандидат | Разрешается ли; конвергенция; smoke |
| C2 | Одобренный patch Boot (3.5.6 либо иной, выбранный governance) + тот же поезд-кандидат | То же; сравнение с C1 |
| C3 | Сервис **HELM** без Config Client | Config Client отсутствует на classpath; ни один bean bridge не создаётся; отсутствие Cloud-типов не вызывает сбоя |
| C4 | Сервис **CONFIG_SERVER** без Config Client | **Fail-fast с внятной диагностикой.** Не тихая деградация, не молчаливый переход в HELM |
| C5 | Сервис **CONFIG_SERVER** с Config Client | Полный путь: import Config Data → provenance → apply |
| C6 | Потребитель публикуемого BOM | Разрешение из опубликованного BOM: какие констрейнты Cloud транслируются потребителю. **Здесь проявляется дефект §S-06.1** |
| C7 | Утечка classpath starter'ов | Ни один starter не тянет Config Client транзитивно; ни один не тянет Agent-extension транзитивно |

### S-06.3 Требуемые доказательства

| # | Свойство | Критерий |
|---|---|---|
| D1 | Точный разрешённый граф для каждой из C1–C7 | Вывод `dependencies` сохранён на каждую конфигурацию |
| D2 | Конвергенция зависимостей | Ни одного конфликта версий; ни одного downgrade Cloud-артефакта относительно поезда Boot |
| D3 | Ни одного EOL-артефакта в публикуемом констрейнте | `spring-cloud-context:4.1.5` в области `api` устранён |
| D4 | Согласованность BOM и `SUPPORTED.md` | `docs/SUPPORTED.md:42,49` и BOM обновлены **одним** изменением; ни одной комбинации, документированной как поддерживаемая, но не проверенной |
| D5 | Smoke на production-classpath | Контекст поднимается в C3, C4 (fail-fast), C5 |
| D6 | Проверки configuration-cache и build-cache | Существующие verification-task проходят без регрессии |
| D7 | Consumer-smoke опубликованного BOM | Существующая механика published-consumer используется; констрейнты, транслируемые потребителю, зафиксированы явно |
| D8 | Стек Step 0B совпадает с одобренным production-стеком | **Обратная связь в S-02 Q6.** Если governance выбирает patch Boot, отличный от 3.5.6, T1–T10 обязаны быть перепрогнаны на выбранном стеке |
| D9 | Механизм доставки refresh назван | Выбор зафиксирован (Bus+Monitor / защищённый `/actuator/refresh` / корпоративный оркестратор) вместе с владельцем fan-out и требованиями авторизации. Отложенное решение D-03 закрывается здесь либо переходит к SRE |

### S-06.4 Критерий приёмки

```text
- GOV-1 исправлен: EOL межпоездный api-констрейнт устранён.
- GOV-2 решён: корпоративный patch-level Boot и поезд Cloud выбраны;
  BOM и SUPPORTED.md обновлены согласованно.
- GOV-3 подтверждён: starters не тянут Config Client транзитивно.
- C4 демонстрирует fail-fast, а не тихую деградацию.
- D9 закрыт либо явно передан SRE как SRE-4.
- Ни одного нового Gradle-модуля.
```

### S-06.5 Чего S-06 не доказывает

Не доказывает работоспособность bridge, атомарность apply, поведение при refresh-storm и производительность (M10/M10c/M10d). Это отдельные гейты после implementation GO.

---

## Приложение A — Матрица «спайк → гейт → разблокируемое»

| Спайк | Закрывает гейт | Разрешает конфликт | Разблокирует |
|---|---|---|---|
| S-02 | E9 | C-02 | Все выводы по Config Server; отклонение атаки A-04 отчёта 12; T6–T10 как FACT |
| S-01 | E3 | C-01 | Реализацию состояния; предпосылку удаления трёх доменных holder'ов |
| S-03 | E1, E2 | C-06 | Границу записи; read-only платформенную проекцию; предпосылку удаления 16 мутирующих операций |
| S-04 | E4 | C-03 | Миграцию namespace; схему wire; guard'ы |
| S-05 | E5 | C-04 | Заморозку Category A; схему wire |
| S-06 | E6 | C-05 | Governance-одобрение; публикацию BOM; S-02 Q6 |
| — | E7 | — | Требует E1; после S-03 |
| — | E8 | — | Требует всего перечисленного; **NOT_GRANTED** |

---

## Приложение B — Что не является спайком

Следующее прямо исключено из этого пакета, чтобы «доказательное закрытие» не превратилось в скрытую реализацию.

```text
Не спайк: реализация AcceptedTracingPolicyState как production-типа
Не спайк: реализация TracingConfigServerBridge
Не спайк: реализация TracingDevControlJmxEndpoint
Не спайк: перенос читателей sampler/scrubbing/validation/export
Не спайк: удаление RuntimeConfigApplier, TracingRefreshScopeAutoConfiguration,
          @WriteOperation, доменных сеттеров, VersionedStateHolder,
          трёх доменных holder'ов
Не спайк: переименование любого свойства или переменной окружения
Не спайк: добавление CONFIG_SNAPSHOT_V1 в production-enum
          (S-03 M11 доказывает ПОРЯДОК установки, а не поставляет capability)
Не спайк: миграция M10/M10c/M10d на production-путь
```

Три исключения, где спайк неизбежно оставляет постоянный артефакт, и это допустимо:

```text
1. Закоммиченный baseline JMX-контракта  — предусловие E1;
   read-only доказательство, ничего не удаляет.
2. Закоммиченный source set configProvenance  — восстановление
   существующего доказательства, а не новая функциональность.
3. Расширенный DomainMBeanJmxComplianceTest  — сверка с baseline;
   защищает контракт, не меняет его.
```

---

## Приложение C — Сводная таблица состояния гейтов

```text
E1  Baseline JMX + инвентарь потребителей          OPEN      -> предусловие S-03
E2  Приватный MBeanServer: осуществимость          ЧАСТИЧНО  -> механизм ACCEPT, S-03
E3  Точный API примитива состояния                 OPEN      -> S-01
E4  Полная disposition свойств и env               OPEN      -> S-04
E5  Финальная Category A + границы + секреты       OPEN      -> S-05
E6  Production Boot/Cloud/Config Client + refresh  OPEN      -> S-06
E7  Точная дельта JMX/диагностики/проекции         OPEN      -> после E1
E8  Implementation GO архитектурного комитета      NOT_GRANTED
E9  Воспроизводимость Step 0B                      OPEN      -> S-02

Implementation GO:     NOT_GRANTED
Legacy deletion GO:    BLOCKED
```
