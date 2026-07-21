package space.br1440.platform.tracing.test.characterization;

/**
 * Закрытый реестр подтверждённых дефектов, поведение которых фиксируется зелёными тестами.
 *
 * @param id    идентификатор архитектурной находки
 * @param owner слайс, владеющий исправлением
 */
public enum KnownDefectId {

    ;

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
