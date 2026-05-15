package space.br1440.platform.errorhandling.model.error_detail;

/**
 * Маркерный {@code sealed}-интерфейс для всех структурированных типов деталей ошибки.
 *
 * <p>Точка расширения реализована через {@link CustomErrorDetail} ({@code non-sealed}):
 * downstream-сервисы реализуют этот интерфейс, не трогая саму иерархию.
 */
public sealed interface ErrorDetail
        permits BadRequestDetail, PreconditionFailureDetail, ErrorInfoDetail,
                ResourceInfoDetail, HelpDetail, RetryInfoDetail, RequestInfoDetail,
                CustomErrorDetail {
}
