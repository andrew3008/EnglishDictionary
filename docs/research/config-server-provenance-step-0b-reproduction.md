# Воспроизведение Step 0B — Config Server property provenance (спайк S-02)

| Поле | Значение |
|---|---|
| Спайк | **S-02** (13b §S-02), гейт **E9** (13a §8), конфликт **C-02** (13a §2.1) |
| Репозиторий | `E:\Platform_Traces`, ветка `refactor/cursor-rules` |
| HEAD на момент исполнения | `9fc01d74bd55598cc1560b755d3003d36c103e9f` |
| Java | 21.0.6+8-LTS-188 |
| Дата | 2026-07-27 |
| Изменений в production-коде | **0** |
| Новых Gradle-модулей | **0** (активных проектов по-прежнему 16) |
| **Общий результат** | **PASS** |
| **Гейт E9** | **CLOSED** для изолированного стека; Q6 (одобренный production-стек) остаётся `NOT_RUN` до S-06 |
| **Implementation GO** | **NOT_GRANTED** — этот спайк его не выдаёт и не приближает |

---

## 1. Scope и вердикт

### 1.1 Что требовалось

Перевести артефакты Step 0B из состояния `FACT_UNTRACKED` (физически присутствуют в рабочем дереве, но отсутствуют в контроле версий) в `REPRODUCIBLE_FACT`: закоммитить source set и тесты, исполнить их, сохранить доказательства, явно решить вопрос владения верификацией.

Это **не** задача написания тестов и **не** реализация целевой архитектуры.

### 1.2 Вердикт

```text
Восстановление артефактов          PASS
Исполнение T1–T10                  PASS  (10 из 10, ни одного NOT_RUN)
Git-backed процессный тест         PASS
Изоляция зависимостей              PASS
Граф зависимостей зафиксирован     PASS
Владение верификацией              РЕШЕНО явно (configProvenanceVerify)
Прогон на production-стеке (Q6)    NOT_RUN — зависит от S-06
```

Все шесть обязательных условий приёмки S-02 (Q1–Q5, Q7) выполнены. Q6 остаётся открытым по построению: S-06 ещё не выбрал одобренный production-стек.

---

## 2. Состояние репозитория до спайка

Проверено напрямую, а не принято из отчёта.

| Проверка | Результат |
|---|---|
| `git branch --show-current` | `refactor/cursor-rules` — соответствует ожиданию |
| `git rev-parse HEAD` | `9fc01d74bd55598cc1560b755d3003d36c103e9f` — соответствует ожиданию |
| `git status --short` | ` M platform-tracing-e2e-tests/build.gradle`, `?? platform-tracing-e2e-tests/src/configProvenance/` |
| `git ls-files platform-tracing-e2e-tests/src/configProvenance` | пусто — ни один файл не отслеживался |
| `git diff -- platform-tracing-e2e-tests/build.gradle` | 32 вставки, 0 удалений, 3 hunk'а |
| Посторонние изменения рабочего дерева | Два untracked markdown-файла совета (`docs/analysis/platform-tracing-configuration/13a-…`, `13b-…`) — **не затронуты, не staged, не закоммичены** |
| `git --version` | `2.33.0.windows.2` — Git-backed тест исполним |
| `java -version` | `21.0.6+8-LTS-188`, `JAVA_HOME=G:\JavaFrameworks\jdk-21.0.6` — коррекция окружения не потребовалась |

### 2.1 Коррекция к 13a §2.1

13a §2.1 утверждает: «Каталог `build/` модуля пуст: нет `test-results/configProvenanceTest`, … нет `build/reports/config-provenance/evidence.txt`».

**Это утверждение неверно.** На момент начала S-02 в рабочем дереве присутствовали:

```text
platform-tracing-e2e-tests/build/test-results/configProvenanceTest/
    TEST-…ConfigServerProvenanceCharacterizationTest.xml   1 133 386 б   2026-07-26 15:40:57
    TEST-…ConfigServerGitProcessCharacterizationTest.xml     114 206 б   2026-07-26 15:40:57
platform-tracing-e2e-tests/build/reports/config-provenance/evidence.txt
                                                            24 171 б   2026-07-26 15:40:57
```

Первый вызов `:platform-tracing-e2e-tests:configProvenanceTest` вернул `UP-TO-DATE` именно поэтому. Прежний прогон существовал, но был локальным, невоспроизводимым и неотличимым от отсутствия для любого другого рабочего места.

Это **не** меняет вердикт C-02: артефакты исполнения в `build/` не находятся под контролем версий и стираются любым `clean`. Но формулировка «build/ пуст» в 13a должна читаться как «в `build/` нет воспроизводимых из checkout артефактов». Все результаты ниже получены принудительным перезапуском (`--rerun-tasks`), а не наследованы от прогона 2026-07-26.

---

## 3. Восстановленные файлы

### 3.1 Восемь Java-файлов

Проверены поимённо; отчёт 13a подтверждён без расхождений.

| Файл | Байт | Роль |
|---|---:|---|
| `configserver/ConfigServerProvenanceCharacterizationTest.java` | 18 879 | Layer A: ровно 10 `@Test` (T1–T10) |
| `configserver/ConfigServerGitProcessCharacterizationTest.java` | 9 622 → 10 221 | Layer B: 1 `@Test` с дочерним процессом Config Server |
| `configserver/ConfigServerProcessApplication.java` | 549 | `@EnableConfigServer`, точка входа дочерней JVM |
| `configserver/ConfigServerTestApplication.java` | 338 | Минимальный клиентский контекст |
| `configserver/support/ProvenanceInspector.java` | 6 433 | Классификатор provenance, per-key evidence, дерево источников |
| `configserver/support/ConfigServerResponseStub.java` | 3 704 | In-JVM HTTP-стаб на `com.sun.net.httpserver` |
| `configserver/support/EvidenceRecorder.java` | 2 279 | Дозапись в `config.provenance.evidence.file` |
| `configserver/support/ConfigClientHarness.java` | 2 278 | Запуск клиента через `spring.config.import=configserver:` |

Все восемь путей соответствуют заявленным. Ни одного лишнего или отсутствующего файла. Каталог `src/configProvenance/resources` отсутствует — task `processConfigProvenanceResources` корректно репортит `NO-SOURCE`.

### 3.2 Ревью реализации

| Критерий | Результат |
|---|---|
| Ровно десять T1–T10 | PASS — методы `t1_…`–`t10_…`, по одному `@Test` |
| Один Git-backed процессный тест | PASS — `realConfigServerProcessReadsGitRevisionAndRefreshesAfterCommit` |
| Docker не требуется | PASS — ни Testcontainers, ни docker-клиента в source set |
| Ограниченный старт и остановка процесса | PASS — polling с границей 45 с, `destroy` → `destroyForcibly` с двумя окнами по 10 с |
| Фиксированные учётные данные | Отсутствуют |
| Машинозависимые пути | В коде отсутствуют; `@TempDir` и `java.home` вычисляются в runtime |
| Риск коллизии портов | Стаб биндит порт 0 (без гонки). Дочерний сервер использует `ServerSocket(0)` с последующим освобождением — окно гонки существует, но узкое и на loopback |
| Детерминированное создание Git-репозитория | PASS — `git init -b main`, локальные `user.name`/`user.email`, всё внутри `@TempDir` |
| Очистка дочерней JVM в успехе и в отказе | PASS — `stop(server)` в `finally` |
| Совместимость с Windows | PASS — выбор `java.exe`, проверка `os.name`, путь через `Path.of` |
| Обработка пути вывода доказательств | PASS — no-op при незаданной system property |
| Утечка production-зависимостей | Отсутствует (§4) |
| Зависимости в обычных конфигурациях | Отсутствуют (§4) |
| Скрытое использование internal Spring API | Отсутствует. Используются публичные `OriginLookup`, `OriginTrackedMapPropertySource`, `ContextRefresher`, `EnvironmentChangeEvent`. Тип `ConfigServiceOrigin` **наблюдается** в доказательствах, но код на него не ссылается и не импортирует |
| Утверждение теста сильнее доказанного | Не обнаружено. Имя `t2_…LocalYamlPrecedenceIsCharacterized` честно говорит «характеризуется», а не «требуется» |

Функционирующие тесты по стилистическим причинам не переписывались. Внесённые изменения перечислены в §11.

---

## 4. Изоляция source set и зависимостей в Gradle

### 4.1 Структура

`configProvenance` — **седьмой** source set существующего модуля `platform-tracing-e2e-tests` (`build.gradle:58-65`), рядом с `customRule`, `testExtension`, `jmxWireExtension`, `readinessFixtureExtension`, `e2FailureExtension`, `e1Consumer`. Новый Gradle-модуль не создавался; `settings.gradle` не изменялся; активных проектов — 16.

Зависимости объявлены исключительно на конфигурациях `configProvenanceImplementation` и `configProvenanceRuntimeOnly` (`build.gradle:186-194`). Собственный `enforcedPlatform` изолирует стек от репозиторного `springBootVersion=3.5.5`.

### 4.2 Доказательство отсутствия утечки

Пять конфигураций разрешены и просканированы (`docs/research/evidence/config-provenance/isolation-scan.txt`):

| Конфигурация | Вхождений `spring-cloud` / `jgit` |
|---|---:|
| `platform-tracing-e2e-tests:testRuntimeClasspath` | 0 |
| `platform-tracing-spring-boot-starter-servlet:runtimeClasspath` | 0 |
| `platform-tracing-spring-boot-starter-reactive:runtimeClasspath` | 0 |
| `platform-tracing-spring-boot-autoconfigure:runtimeClasspath` | 0 |
| `platform-tracing-e2e-tests:configProvenanceRuntimeClasspath` | полный стек Config Client/Server (ожидаемо) |

Структурное обоснование: `configProvenanceImplementation` и `configProvenanceRuntimeOnly` не расширяются ни одной другой конфигурацией, поэтому повлиять на `api`, `implementation`, `runtimeOnly`, `testImplementation` или publication metadata они не могут. Модуль вдобавок не публикуется — все task'и `publish*` отключены в `platform-tracing-e2e-tests/build.gradle:79-81`.

### 4.3 Наблюдение, не относящееся к S-02

На `platform-tracing-e2e-tests:testRuntimeClasspath` присутствует `spring-boot:3.5.6`, а не `3.5.5`. Источник — внешний стартер `space.br1440.platform.starters:spring-boot-starter-platform-logging:1.0.4` (`build.gradle:144`). Это предсуществующее состояние тестового classpath, к `configProvenance` отношения не имеет и в scope S-02 не входит. Отмечено, поскольку при поверхностном чтении легко принять за утечку.

---

## 5. Точный граф зависимостей

Команда:

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:dependencies `
  --configuration configProvenanceRuntimeClasspath --no-daemon
```

Полный вывод: `docs/research/evidence/config-provenance/dependency-tree.txt` (39 416 б).

| Артефакт | Разрешённая версия | Ожидалось | Совпадение |
|---|---|---|---|
| `org.springframework.boot:spring-boot` | 3.5.6 | 3.5.6 | да |
| `org.springframework.cloud:spring-cloud-config-client` | 4.3.0 | 4.3.0 | да |
| `org.springframework.cloud:spring-cloud-context` | 4.3.0 | 4.3.0 | да |
| `org.springframework.cloud:spring-cloud-config-server` | 4.3.0 | 4.3.0 | да |
| `org.springframework.cloud:spring-cloud-commons` | 4.3.0 | 4.3.0 | да |
| `org.eclipse.jgit:org.eclipse.jgit` | 6.10.1.202505221210-r | 6.10.1.202505221210-r | да |
| `org.junit.jupiter:junit-jupiter-api` | 5.12.2 | — | зафиксировано |
| `org.junit.platform:junit-platform-launcher` | 1.12.2 | — | зафиксировано |
| `org.springframework:spring-core` | 6.2.11 | — | зафиксировано |

Расхождений с кандидатным стеком из 13a §2.1 нет. Сопутствующие артефакты JGit — `org.eclipse.jgit.http.apache` и `org.eclipse.jgit.ssh.apache` той же версии.

---

## 6. Окружение исполнения

```text
os                 Microsoft Windows 10 Pro, build 10.0.19045
java               21.0.6+8-LTS-188, JAVA_HOME=G:\JavaFrameworks\jdk-21.0.6
gradle             8.14 (wrapper), --no-daemon для каждого зафиксированного прогона
git                2.33.0.windows.2
docker             не требуется и не использовался
```

Канонический прогон:

```powershell
.\gradlew.bat configProvenanceVerify --rerun-tasks --no-daemon --console=plain
```

Результат: `BUILD SUCCESSFUL in 44s` (по часам 44,8 с, включая холодную компиляцию source set). 11 тестов исполнено, 11 пройдено, 0 отказов, 0 пропусков.

Полный снимок: `docs/research/evidence/config-provenance/execution-environment.txt`.

### 6.1 Регрессионные проверки

| Команда | Результат |
|---|---|
| `:platform-tracing-e2e-tests:configProvenanceTest` | **PASS** — 11/11, см. §7 и §8 |
| `:platform-tracing-e2e-tests:test` | **NOT_RUN** — `SKIPPED` по действующему guard'у `onlyIf { project.hasProperty('runE2e') }` (`build.gradle:303`). Это предсуществующее поведение, не следствие S-02, и пройденным не считается |
| `pr0StarterDependencySmoke` | **PASS** — `BUILD SUCCESSFUL`, ожидания по зависимостям публичных стартеров не нарушены |
| `pr1ModuleTaxonomyVerify` | **PASS** — `BUILD SUCCESSFUL` |
| `pr4ArchitectureFitnessVerify` | **PASS** — `BUILD SUCCESSFUL`, ArchUnit-гейты и taxonomy-smoke пройдены |

Добавление `configProvenanceVerify` в корневой `build.gradle` ни одну из этих задач не затронуло.

---

## 7. Матрица результатов T1–T10

Источник: `build/test-results/configProvenanceTest/TEST-…ConfigServerProvenanceCharacterizationTest.xml`, `tests=10 failures=0 errors=0 skipped=0 time=24.658s`.

| # | Метод | Результат | Время | Что зафиксировано исполнением |
|---|---|---|---:|---|
| T1 | `t1_pureRemotePolicyHasTrustedEffectiveAndDesiredViews` | **PASS** | 2,171 с | Effective и remote desired совпадают и указывают на `configserver:stub:application.yml` (`OriginTrackedMapPropertySource`); маркер `configClient` присутствует, `version=remote-v1`, `state=state-v1` |
| T2 | `t2_commandLineWinsWhileLocalYamlPrecedenceIsCharacterized` | **PASS** | 2,172 с | Две записи доказательств. `T2-command-line`: аргумент командной строки побеждает, remote desired при этом остаётся видимым, `localOverride=true`, `conflict=true`. `T2-local-yaml`: источник `configserver:` стоит **выше** YAML из `spring.config.additional-location`, поэтому побеждает remote |
| T3 | `t3_refreshRemovalRebuildsFullSnapshotAndSelectsExplicitDefault` | **PASS** | 3,261 с | После refresh с пустым remote ключ исчезает из обоих представлений, `EnvironmentChangeEvent` его содержит, полный снапшот пересобирается с явным default `1.0` |
| T4 | `t4_equalStructuralBootstrapAndRemoteValuesDoNotRequireRestart` | **PASS** | 2,123 с | Равные значения structural-ключа локально и удалённо: `localOverride=true`, но `conflict=false`, `restartRequired=false` |
| T5 | `t5_differentStructuralDesiredValueIsVisibleAndRequiresRestart` | **PASS** | 2,159 с | Различающийся structural-ключ: effective `2048`, remote desired `4096` — remote desired остаётся наблюдаемым, `restartRequired=true` |
| T6 | `t6_systemPolicyOverrideIsDetectedAndFailsClosed` | **PASS** | 2,168 с | System property перекрывает policy-ключ; классификация даёт `SYSTEM`, guard режима `CONFIG_SERVER` возвращает fail-closed |
| T7 | `t7_successfulFetchWithoutVersionKeepsRevisionOptional` | **PASS** | 2,143 с | `configClient` присутствует при `version=null`: успешная выборка не подразумевает наличие revision |
| T8 | `t8_directMarkerReadDefeatsEffectiveVersionSpoofing` | **PASS** | 2,161 с | `environment.getProperty("config.client.version")` возвращает подделанное `fake-local-version`, прямое чтение из marker-source — настоящее `actual-remote-version` |
| T9 | `t9_removedPropertyAppearsInDiffAndDisappearsFromBothViews` | **PASS** | 3,148 с | `eventKeys=[config.client.version, platform.tracing.policy.sampling.ratio, config.client.state]` — удалённый ключ присутствует в diff; категория `ABSENT`; полный снапшот получает `1.0` |
| T10 | `t10_listenerSeesUpdatedEnvironmentSourcesAndMarker` | **PASS** | 3,151 с | Внутри обработчика `EnvironmentChangeEvent` Environment уже обновлён: effective `0.22`, remote desired `0.22`, маркер `remote-v10-after`, effective source содержит `configserver:` |

`NOT_RUN` — ни одного.

### 7.1 Факты, извлечённые из исполнения

Эти наблюдения зафиксированы прогоном и релевантны для решения; ни одно не было доказано до S-02.

**Порядок `PropertySource` (одинаков во всех сценариях):**

```text
0  configurationProperties                    ConfigurationPropertySourcesPropertySource
1  commandLineArgs                            SimpleCommandLinePropertySource
2  systemProperties                           PropertiesPropertySource
3  systemEnvironment                          OriginAwareSystemEnvironmentPropertySource
4  random                                     RandomValuePropertySource
5  cachedrandom                               CachedRandomPropertySource
6  configserver:<имя>                         OriginTrackedMapPropertySource
7  configClient                               MapPropertySource
```

Из этого следует то, что T4–T6 и характеризуют: при `spring.config.import=configserver:` удалённые значения находятся **ниже** command line, system properties и переменных окружения. Любой локальный override policy-ключа побеждает молча, и обнаруживать его обязан классификатор, а не порядок источников.

**Маркер `configClient`** — обычный `org.springframework.core.env.MapPropertySource`, а не специальный тип, и располагается **после** удалённого источника. Опираться на его тип нельзя; опознаётся он только по имени.

**Origin зависит от бэкенда.** Против стаба `OriginLookup.getOrigin` возвращает `null`. Против настоящего Config Server (Layer B) он возвращает `org.springframework.cloud.config.client.ConfigServicePropertySourceLocator$ConfigServiceOrigin` со значением вида `Config Server file:///…/provenance-client.yml:5:16`. Это исполняемое подтверждение уже принятого правила: origin — **подтверждающий сигнал**, а не типизированный дискриминатор, и построить на нём обязательную классификацию нельзя, поскольку в части конфигураций он просто отсутствует.

---

## 8. Git-backed процессный тест Config Server

Источник: `TEST-…ConfigServerGitProcessCharacterizationTest.xml`, `tests=1 failures=0 errors=0 skipped=0 time=10.01s`.

| Параметр | Значение |
|---|---|
| Результат | **PASS** |
| Метод | `realConfigServerProcessReadsGitRevisionAndRefreshesAfterCommit` |
| Версия Git | `git version 2.33.0.windows.2` |
| Готовность дочерней JVM | **4 924 мс** при бюджете 45 000 мс |
| Общее время теста | 10,010 с |
| Завершение очистки | `gracefulStop=true`, `childServerProcessAlive=false` |
| Docker | не использовался |

Что доказано процессом, а не стабом: настоящий `@EnableConfigServer` в отдельной JVM клонирует временный Git-репозиторий, отдаёт `platform.tracing.policy.sampling.ratio=0.31`, а `config.client.version` оказывается **полным 40-символьным SHA коммита** `4c74cf74d5afa62680cc63b0672fc59210e1c006`. После второго коммита и `ContextRefresher.refresh()` значение становится `0.62`, маркер — `a32b0b52b5943a8397abbeadb7ddc1edebfa7d2a`, а `changedKeys` содержит и `config.client.version`, и сам policy-ключ. При Git-бэкенде `config.client.state` равен `null`, что независимо подтверждает вывод T7: revision присутствует, state опционален.

---

## 9. Сгенерированные артефакты доказательств

Регенерируются командой из §6; в репозиторий не коммитятся:

```text
platform-tracing-e2e-tests/build/test-results/configProvenanceTest/TEST-space.br1440.platform.tracing.e2e.configserver.ConfigServerProvenanceCharacterizationTest.xml
platform-tracing-e2e-tests/build/test-results/configProvenanceTest/TEST-space.br1440.platform.tracing.e2e.configserver.ConfigServerGitProcessCharacterizationTest.xml
platform-tracing-e2e-tests/build/reports/tests/configProvenanceTest/index.html
platform-tracing-e2e-tests/build/reports/config-provenance/evidence.txt
```

HTML-каталог отчёта не коммитится: репозиторной политики, требующей этого, нет.

Коммитятся стабильные текстовые копии:

```text
docs/research/evidence/config-provenance/evidence.txt              полный вывод EvidenceRecorder, 12 записей
docs/research/evidence/config-provenance/test-summary.txt          построчный PASS/FAIL по каждому тесту
docs/research/evidence/config-provenance/dependency-tree.txt       полный разрешённый граф
docs/research/evidence/config-provenance/isolation-scan.txt        доказательство отсутствия утечки
docs/research/evidence/config-provenance/execution-environment.txt окружение и команды
docs/research/evidence/config-provenance/gradle-console.txt        консольный вывод канонического прогона
```

`evidence.txt` содержит ровно 12 записей: `T1`, `T2-local-yaml`, `T2-command-line`, `T3`–`T10`, `Layer-B-real-config-server-git`, `Layer-B-cleanup`.

---

## 10. Решение о владении верификацией

Выбрана **предпочтительная модель**: выделенный явный verification-task.

```groovy
tasks.register('configProvenanceVerify') {
    group = 'verification'
    description = 'Runs the isolated Spring Cloud Config provenance characterization (Step 0B evidence).'
    dependsOn ':platform-tracing-e2e-tests:configProvenanceTest'
}
```

Размещён в корневом `build.gradle` рядом с существующими агрегатными задачами (`pr0StarterDependencySmoke`, `pr1ModuleTaxonomyVerify`, `pr4ArchitectureFitnessVerify`, `pa3FinalAlignmentVerify`), то есть по действующей конвенции репозитория. Нового verification-фреймворка не вводилось.

**Команда:**

```powershell
.\gradlew.bat configProvenanceVerify --no-daemon
```

**Почему не подключено к `check`/`build`:** тест форкает дочернюю JVM Config Server, биндит эфемерные loopback-порты и требует `git` в `PATH`. Репозиторий уже применяет ровно эту политику к сопоставимым внешним тестам — task `test` того же модуля закрыт guard'ом `onlyIf { project.hasProperty('runE2e') }` (`platform-tracing-e2e-tests/build.gradle:303`). Подключение к `check` замедлило бы каждую локальную сборку без выигрыша в сигнале.

**Кто обязан вызывать:** CI-workflow или release-гейт, покрывающий доказательства конфигурации, должен вызывать `configProvenanceVerify` по имени. Пока такой вызов не добавлен, задача исполняется только вручную — это осознанный остаток, а не умолчание. Прежнее состояние (task существует, но не вызывается ничем и никем не документирован) исключено: именно оно давало доказательство, деградация которого прошла бы незаметно.

---

## 11. Отклонения от исходной untracked-реализации

Три изменения. Первые два — в `ConfigServerGitProcessCharacterizationTest`, оба относятся к категории «корректная отчётность о доказательствах» и требуются Phase 5. Ни одно не меняет проверяемое поведение и ни одно не ослабляет утверждение.

| # | Изменение | Обоснование |
|---|---|---|
| 1 | Замер времени готовности дочернего сервера вокруг `awaitServer` и его вывод в запись `Layer-B-…` вместе с бюджетом | Требовалось зафиксировать время старта дочерней JVM; прежде оно нигде не наблюдалось. Ассерты не затронуты |
| 2 | `stop(Process)` возвращает `boolean` вместо `void`; в `finally` добавлена запись `Layer-B-cleanup` с `gracefulStop` и `childServerProcessAlive` | Требовалось зафиксировать факт завершения очистки. Логика остановки прежняя: `destroy`, затем `destroyForcibly` с двумя окнами по 10 с |
| 3 | В **коммитируемой копии** `evidence.txt` имя локальной учётной записи Windows внутри путей `@TempDir` заменено на `<redacted-user>`; добавлен пятистрочный заголовок, описывающий эту замену | Правило репозитория запрещает сырые PII в тест-отчётах. Замена затрагивает 9 вхождений; больше ни один символ не изменён. Файл в `build/` остаётся нередактированным |

Семантика тестов не менялась. Ни один тест не переписан по стилистическим соображениям. Исправлений ради компиляции, детерминизма или совместимости с Windows/JDK 21 не потребовалось — реализация была работоспособна как есть.

---

## 12. Что спайк доказывает

1. Source set `configProvenance`, task `configProvenanceTest`, восемь Java-файлов и изолированный стек Spring Cloud находятся под контролем версий и воспроизводимы из checkout.
2. T1–T10 исполнимы и проходят; результат каждого зафиксирован как PASS, а не как проза.
3. Настоящий процесс Config Server с Git-бэкендом читает временный репозиторий, отдаёт SHA коммита как `config.client.version` и после нового коммита выдаёт обновлённое значение через `ContextRefresher.refresh()`.
4. Кандидатный стек Boot 3.5.6 / Cloud 2025.0.0 / Config Client 4.3.0 / Context 4.3.0 / Config Server 4.3.0 / JGit 6.10.1 разрешается совместно и работоспособен.
5. Ни один артефакт Spring Cloud и ни один артефакт JGit не попадает в production- или обычные тестовые конфигурации.
6. Владение верификацией зафиксировано явно и проверяемо.

Из `UNVERIFIED` в `FACT` переводятся пять выводов, перечисленных в 13a §2.1: доставка полного обновлённого Environment слушателю (T10, вместе с отклонением атаки A-04 отчёта 12), присутствие удалённых ключей в `getKeys()` (T9), разрешимость per-key provenance, прямое чтение revision из marker-source и устойчивость к спуфингу (T8), детекция локального override policy-ключа с fail-closed (T6).

---

## 13. Чего спайк не доказывает

Явно и без смягчений:

```text
Поддержку Config Client в production                       НЕ ДОКАЗАНО
Одобрение версий Boot/Cloud для production                 НЕ ДОКАЗАНО  (S-06)
Реализацию Config Server bridge                            НЕ ДОКАЗАНО  (кода не существует)
Корректность aggregate apply                               НЕ ДОКАЗАНО
Cross-domain атомарность                                   НЕ ДОКАЗАНО  (S-01)
Безопасность приватного MBeanServer                        НЕ ДОКАЗАНО  (S-03)
Интеграцию с readiness приложения                          НЕ ДОКАЗАНО
Реализацию runtime structural drift                        НЕ ДОКАЗАНО
Обработку refresh-storm                                    НЕ ДОКАЗАНО
Показатели M10 / M10c / M10d                               НЕ ДОКАЗАНО
Implementation GO                                          НЕ ВЫДАН
```

Дополнительные границы, вытекающие из конструкции самих тестов:

- Десять из одиннадцати тестов работают против HTTP-стаба, отдающего рукописный JSON. Стаб воспроизводит форму ответа Config Server, но не сам Config Server. Только Layer B использует настоящий сервер, и только он покрывает Git-бэкенд, JGit и `ConfigServiceOrigin`.
- Изолированный `enforcedPlatform` доказывает поведение Config Client, но ничего не говорит о способности репозитория поставлять Config Client потребителям. Это предмет S-06.
- Все прогоны выполнены на одной машине под Windows 10 и Java 21.0.6. Кросс-платформенная воспроизводимость не проверялась.
- Порядок `PropertySource`, зафиксированный в §7.1, характеризует поведение Spring Boot 3.5.6 с Config Data import. Для другого патч-уровня Boot он должен быть перепроверен.

---

## 14. Статус гейта E9

| Условие S-02 | Статус | Доказательство |
|---|---|---|
| Q1 — source set и task в checkout | **PASS** | `git show HEAD:platform-tracing-e2e-tests/build.gradle` содержит `configProvenance` и `configProvenanceTest` после коммита |
| Q2 — все восемь Java-файлов отслеживаются | **PASS** | `git ls-files platform-tracing-e2e-tests/src/configProvenance` возвращает ровно 8 путей |
| Q3 — T1–T10 исполнены | **PASS** | §7; 10 из 10 PASS, T2 даёт две записи доказательств |
| Q4 — Git-backed процессный тест исполнен | **PASS** | §8; PASS за 10,010 с, готовность 4 924 мс, очистка подтверждена |
| Q5 — граф зависимостей зафиксирован | **PASS** | §5 и `dependency-tree.txt` |
| Q6 — прогон на одобренном production-стеке | **NOT_RUN** | Зависит от S-06; ниже |
| Q7 — ограничения перечислены | **PASS** | §13 |

**Гейт E9: CLOSED в части воспроизводимости изолированного Step 0B.** Формулировка гейта в 13a §8 требует прогона «на утверждённом стеке-кандидате»; утверждённого стека пока не существует, поэтому эта составляющая остаётся открытой и передаётся в S-06. Никакая архитектурная зависимость от Config Server больше не опирается на невоспроизводимый артефакт.

---

## 15. Оставшаяся зависимость от S-06

Q6 не может быть закрыт внутри S-02 по построению: спайк доказывает поведение изолированного стека, а не одобренного production-стека, которого ещё нет.

Открыто до S-06:

1. Патч-уровень Spring Boot для production не выбран. S-02 исполнялся на 3.5.6 в изолированном source set; репозиторий закреплён на `springBootVersion=3.5.5` (`gradle.properties:9`).
2. Дефект GOV-1 из 13a §5.3 не исправлен: `platform-tracing-bom/build.gradle:29` продолжает объявлять `spring-cloud-context:4.1.5` как `api`-констрейнт. Версия принадлежит EOL-поезду 2023.0.x и не соответствует поезду, в котором работали эти тесты (4.3.0). Дефект существует независимо от любого решения по Config Server и затрагивает всех потребителей BOM.
3. Consumer-smoke для семи конфигураций S-06 (HELM без Config Client, CONFIG_SERVER без и с Config Client, потребитель опубликованного BOM, утечка в classpath стартера) не исполнялся.

Пока эти три пункта открыты, любое утверждение о production-пригодности Config Client остаётся вне доказательной базы, независимо от результата S-02.
