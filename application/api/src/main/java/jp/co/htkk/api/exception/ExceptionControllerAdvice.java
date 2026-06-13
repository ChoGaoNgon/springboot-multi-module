package jp.co.htkk.api.exception;

import jakarta.servlet.http.HttpServletRequest;
import jp.co.htkk.framework.exception.DefaultRestExceptionControllerAdvice;
import jp.co.htkk.framework.exception.handler.IExceptionHandler;
import jp.co.htkk.framework.exception.model.ErrorCode;
import jp.co.htkk.framework.exception.model.ErrorResponse;
import jp.co.htkk.framework.exception.type.NoDataFoundException;
import jp.co.htkk.framework.exception.type.ServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.List;

@ControllerAdvice
public class ExceptionControllerAdvice extends DefaultRestExceptionControllerAdvice {

    public ExceptionControllerAdvice(IExceptionHandler<ServiceException> serviceExceptionHandler,
                                     IExceptionHandler<BindException> bindExceptionHandler,
                                     IExceptionHandler<MethodArgumentNotValidException> methodArgumentNotValidExceptionIExceptionHandler,
                                     IExceptionHandler<Exception> unknownExceptionHandler,
                                     IExceptionHandler<NoDataFoundException> noDataFoundExceptionIExceptionHandler) {
        super(serviceExceptionHandler,
                bindExceptionHandler,
                methodArgumentNotValidExceptionIExceptionHandler,
                unknownExceptionHandler,
                noDataFoundExceptionIExceptionHandler);
    }

    /**
     * Method-security ({@code @PreAuthorize}) denials throw {@link AccessDeniedException} from inside the
     * controller invocation, so they are resolved by MVC here — not by the security filter chain's
     * AccessDeniedHandler. Map them to a 403 with the project's error envelope; without this they would
     * fall through to the inherited {@code Exception.class} handler and surface as 500.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        ErrorResponse body = ErrorResponse.of(HttpStatus.FORBIDDEN, "Forbidden",
                List.of(ErrorCode.EACCES.getErrorCode()));
        return new ResponseEntity<>(body, HttpStatus.FORBIDDEN);
    }

    /**
     * Bad credentials at {@code /auth/login} surface as an {@link AuthenticationException} thrown from the
     * controller, so they reach MVC here rather than the security entry point. Map them to 401.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex, HttpServletRequest req) {
        ErrorResponse body = ErrorResponse.of(HttpStatus.UNAUTHORIZED, "Unauthorized",
                List.of(ErrorCode.EUNAUTHORIZED.getErrorCode()));
        return new ResponseEntity<>(body, HttpStatus.UNAUTHORIZED);
    }
}
