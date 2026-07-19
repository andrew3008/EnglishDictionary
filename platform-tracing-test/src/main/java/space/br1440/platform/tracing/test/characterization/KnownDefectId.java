package space.br1440.platform.tracing.test.characterization;

/**
 * Закрытый реестр подтверждённых дефектов, поведение которых фиксируется зелёными тестами.
 *
 * @param id    идентификатор архитектурной находки
 * @param owner слайс, владеющий исправлением
 */
public enum KnownDefectId {

    UNBOUNDED_REMOTE_SERVICE_MIRROR("ALIGN-10", "Slice H"),
    LEGACY_REQUEST_ID_CORRELATION_KEY("IDENT-1/IDENT-5", "Slice M");

    private final String id;
    private final String owner;

    KnownDefectId(String id, String owner) {
        this.id = id;
        this.owner = owner;
    }

    public String id() {
        return id;
    }

    public String owner() {
        return owner;
    }
}
