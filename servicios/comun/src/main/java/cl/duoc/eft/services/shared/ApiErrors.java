package cl.duoc.eft.services.shared;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.client.RestClientException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(BusinessException.class)
    public ProblemDetail business(BusinessException ex) { return ProblemDetail.forStatusAndDetail(ex.status,ex.getMessage()); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail invalid(Exception ex) { return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"Solicitud invalida; revise campos y formatos"); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail duplicate(Exception ex) { return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,"Identificador o documento existente, o restriccion de integridad"); }
    @ExceptionHandler({TransientDataAccessException.class, RestClientException.class,
            org.springframework.security.oauth2.core.OAuth2AuthorizationException.class,
            org.springframework.jdbc.CannotGetJdbcConnectionException.class})
    public ProblemDetail unavailable(Exception ex) { return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,"Dependencia no disponible; reintente conservando el identificador de solicitud"); }
}
