package space.br1440.platform.errorhandling.http;

import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.exception.AbstractErrorResponseException;
import space.br1440.platform.errorhandling.exception.integration.ExternalServiceException;
import space.br1440.platform.errorhandling.exception.httpstatus.BadGatewayException;
import space.br1440.platform.errorhandling.exception.httpstatus.BadRequestException;
import space.br1440.platform.errorhandling.exception.httpstatus.ConflictException;
import space.br1440.platform.errorhandling.exception.httpstatus.ForbiddenException;
import space.br1440.platform.errorhandling.exception.httpstatus.GatewayTimeoutException;
import space.br1440.platform.errorhandling.exception.httpstatus.NotFoundException;
import space.br1440.platform.errorhandling.exception.httpstatus.NotImplementedException;
import space.br1440.platform.errorhandling.exception.httpstatus.ServiceUnavailableException;
import space.br1440.platform.errorhandling.exception.httpstatus.TooManyRequestsException;
import space.br1440.platform.errorhandling.exception.httpstatus.UnauthorizedException;
import space.br1440.platform.errorhandling.exception.httpstatus.UnprocessableException;

/**
 * Платформенная реализация {@link HttpStatusResolver}.
 * <p>Регистрируется стартером через {@code @ConditionalOnMissingBean(HttpStatusResolver.class)}.
 */
public class DefaultHttpStatusResolver implements HttpStatusResolver {

    @Override
    public HttpStatus resolve(AbstractErrorResponseException exception) {
        if (exception instanceof ExternalServiceException ext) {
            HttpStatus proxied = ext.resolveProxiedHttpStatus();
            if (proxied != null) {
                return proxied;
            }
        }

        if (exception instanceof BadRequestException) {
            return HttpStatus.BAD_REQUEST;
        }

        if (exception instanceof UnauthorizedException) {
            return HttpStatus.UNAUTHORIZED;
        }

        if (exception instanceof ForbiddenException) {
            return HttpStatus.FORBIDDEN;
        }

        if (exception instanceof NotFoundException) {
            return HttpStatus.NOT_FOUND;
        }

        if (exception instanceof ConflictException) {
            return HttpStatus.CONFLICT;
        }

        if (exception instanceof UnprocessableException) {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }

        if (exception instanceof TooManyRequestsException) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }

        if (exception instanceof NotImplementedException) {
            return HttpStatus.NOT_IMPLEMENTED;
        }

        if (exception instanceof BadGatewayException) {
            return HttpStatus.BAD_GATEWAY;
        }

        if (exception instanceof ServiceUnavailableException) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }

        if (exception instanceof GatewayTimeoutException) {
            return HttpStatus.GATEWAY_TIMEOUT;
        }

        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
