package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Долгоживущий держатель заменяемого владельца cumulative-состояния, переживающий сами
 * заменяемые экземпляры (создаётся один раз в составе control-класса, а не внутри
 * заменяемого владельца). Аллокация generation и публикация владельца — одна атомарная
 * операция внутри {@link AtomicReference#updateAndGet(java.util.function.UnaryOperator)}.
 *
 * <p>Раздельные «выдать generation» и «опубликовать владельца» создают гонку: поток с более
 * старой generation может опубликовать холдер последним, и опубликованная generation
 * уменьшится — bridge на стороне декодера увидит новое состояние со старой identity,
 * фантомную дельту между разными владельцами или ложную backward-аномалию. Поэтому только
 * этот класс имеет право увеличивать generation, публиковать владельца и переводить
 * present ↔ absent; внешний код никогда не передаёт готовую generation в {@code set}.
 *
 * @param <T> тип заменяемого владельца
 */
public final class OwnerRegistrationSlot<T> {

    private final AtomicReference<OwnerRegistrationState<T>> current;

    public OwnerRegistrationSlot() {
        this.current = new AtomicReference<>(new OwnerRegistrationState.Absent<>(0L));
    }

    /**
     * Публикует нового владельца с generation, строго большей предыдущей — независимо от того,
     * был ли предыдущий владелец присутствующим или {@code Absent}.
     */
    public OwnerRegistrationState<T> replace(T newOwner) {
        Objects.requireNonNull(newOwner, "newOwner");
        return current.updateAndGet(previous ->
                new OwnerRegistrationState.Present<>(newOwner, Math.incrementExact(previous.generation())));
    }

    /**
     * Переводит слот в {@code Absent}, сохраняя текущую generation — reattachment
     * впоследствии получит строго большую, а не начнёт заново с нуля.
     */
    public OwnerRegistrationState<T> remove() {
        return current.updateAndGet(previous -> new OwnerRegistrationState.Absent<>(previous.generation()));
    }

    /** Единственная точка чтения: capture читает слот ровно один раз и берёт из него и значения, и identity. */
    public OwnerRegistrationState<T> capture() {
        return current.get();
    }
}
