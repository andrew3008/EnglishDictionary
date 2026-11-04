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
