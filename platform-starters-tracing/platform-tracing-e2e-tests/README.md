# End-to-End тесты для платформенного решения по трейсингу

Модуль проверяет не отдельные классы, а фактически поставляемую связку:
исполняемый Spring Boot JAR, controlled Java agent и упакованное расширение.
Основной прогон запускает отдельные герметичные JVM-процессы и сохраняет
машиночитаемые evidence в `build/reports/packaged-certification`.

Проверяются:
- идентичность controlled agent и встроенного extension JAR;
- generated JMX schema, OpenType и метаданные MBean;
- состояние readiness и аттестация classloader boundary;
- локальное применение и точное чтение настроек через production JMX client;
- повторяемое чтение всех observation snapshots;
- реальный RMI/JMX round-trip через loopback/ephemeral connector в отдельной packaged JVM;
- инъекция чужого custom DTO через отдельный test-only endpoint настоящего platform MBeanServer,
  его отклонение production `JmxReadExecutor` с диагностикой `JMX_WIRE_TYPE_VIOLATION` и
  подтверждение сохранности platform-owned observation endpoint;
- fail-closed сценарии отсутствия, повреждения и конфликта артефактов;
- точный разбор machine-readable конверта отказа `runtimeState/reason/lifecycle`
  без зачёта общих фрагментов stack trace;
- отдельно версионируемый synthetic stale-topology binary fixture с ожидаемым
  `EXTENSION_LINKAGE_ERROR`;
- несовместимый synthetic OTel API 0.9.0 и application-копия extension-private класса:
  READY подтверждает, что обе class identity остаются разделёнными;
- пятисекундный зависший JMX getter на refresh-потоке и отдельные raw/budget
  значения latency in-process request/span probe;
- отсутствие частичной регистрации runtime MBeans при коллизии владельца;
- сравнительные показатели старта, loaded classes и metaspace относительно packaged baseline без Java agent.

Remote connector создаётся только отдельным test-only Java agent внутри forked packaged JVM и
владеет loopback listener. Этот fixture не добавляет connector в production extension и не
подменяет внешнюю аттестацию deployment-owned listener, TLS, authentication и admission controls.

Production extension сегодня не устанавливает default-deny forwarder на platform MBeanServer —
единственный код-уровневый контроль remote JMX — fail-closed exposure gate
(`RemoteJmxMutationRegistrationGate`), который отклоняет регистрацию mutation endpoint при
любом непрочитанном или несертифицированном remote-listener evidence. Профиль
`PLATFORM_OWNED_CERTIFIED_CONNECTOR` (с forwarder-attestation) в текущей реализации недостижим,
пока платформа не поставляет собственный connector.

Forked JVM всегда стартует с `-XX:+DisableAttachMechanism` и точной тройкой
`platform.tracing.jmx.remote.*` startup arguments. В остальных сценариях явно объявляется
`REMOTE_PROHIBITED`.

Этот lane подтверждает неизменяемость startup declaration после запуска JVM, но не выдаёт
криптографическую admission/supply-chain аттестацию и не доказывает, что произвольный код
приложения никогда не создаст второй программный connector. Эти свойства остаются обязательными
внешними release evidence.

Synthetic stale fixture детерминированно удаляет метод topology-ABI из копии текущего
extension JAR и присваивает ей отдельную версию. Он проверяет точную классификацию
`EXTENSION_LINKAGE_ERROR`, но не заменяет бинарник фактически выпущенной исторической версии.
Пятисекундный latency gate является локальной проверкой изоляции и не заменяет длительный soak
или performance-прогон на стабильной host cohort.
Непокрытые сценарии явно перечисляются в итоговом evidence, чтобы локальный
PASS нельзя было интерпретировать как доказательство этих свойств.
