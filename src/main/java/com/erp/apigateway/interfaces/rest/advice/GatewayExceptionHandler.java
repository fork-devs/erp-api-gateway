package com.erp.apigateway.interfaces.rest.advice;

import com.erp.apigateway.domain.exception.BulkheadExceededException;
import com.erp.apigateway.domain.exception.CircuitOpenException;
import com.erp.apigateway.domain.exception.GatewayException;
import com.erp.apigateway.domain.exception.InvalidTenantFilterException;
import com.erp.apigateway.domain.exception.InvalidTokenException;
import com.erp.apigateway.domain.exception.RateLimitExceededException;
import com.erp.apigateway.domain.exception.TargetServiceUnavailableException;
import com.erp.apigateway.domain.exception.TenantForbiddenException;
import com.erp.apigateway.domain.exception.TenantStatusUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;

@RestControllerAdvice
public class GatewayExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ProblemDetail> handleInvalidToken(InvalidTokenException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setTitle("Autenticación Requerida o Inválida");
        problem.setType(URI.create("https://errors.erp-automotive.pe/gateway/unauthorized"));
        problem.setProperty("errorCode", ex.getErrorCode());
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(TenantForbiddenException.class)
    public ResponseEntity<ProblemDetail> handleTenantForbidden(TenantForbiddenException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Acceso de Taller Prohibido");
        problem.setType(URI.create("https://errors.erp-automotive.pe/gateway/tenant-forbidden"));
        problem.setProperty("errorCode", ex.getErrorCode());
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimitExceeded(RateLimitExceededException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        problem.setTitle("Límite de Peticiones Excedido");
        problem.setType(URI.create("https://errors.erp-automotive.pe/gateway/rate-limit-exceeded"));
        problem.setProperty("errorCode", ex.getErrorCode());
        problem.setProperty("retryAfterSeconds", ex.getRetryAfterSeconds());
        problem.setProperty("timestamp", Instant.now());

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).headers(headers).body(problem);
    }

    @ExceptionHandler(BulkheadExceededException.class)
    public ResponseEntity<ProblemDetail> handleBulkheadExceeded(BulkheadExceededException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle("Capacidad de Concurrencia Saturada (Bulkhead)");
        problem.setType(URI.create("https://errors.erp-automotive.pe/gateway/bulkhead-exceeded"));
        problem.setProperty("errorCode", ex.getErrorCode());
        problem.setProperty("timestamp", Instant.now());

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "5");

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).headers(headers).body(problem);
    }

    @ExceptionHandler(TenantStatusUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleTenantStatusUnavailable(TenantStatusUnavailableException ex) {
        // El estado del taller no se ha podido determinar: se falla CERRADO.
        // Un taller suspendido no puede volver a operar porque el servicio de
        // talleres no responda (hallazgo 6.31).
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle("Estado del Taller No Determinable");
        problem.setType(URI.create("https://errors.erp-automotive.pe/gateway/tenant-status-unavailable"));
        problem.setProperty("errorCode", ex.getErrorCode());
        problem.setProperty("timestamp", Instant.now());

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "5");

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).headers(headers).body(problem);
    }

    @ExceptionHandler(InvalidTenantFilterException.class)
    public ResponseEntity<ProblemDetail> handleInvalidTenantFilter(InvalidTenantFilterException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Filtro de Taller Inválido");
        problem.setType(URI.create("https://errors.erp-automotive.pe/gateway/invalid-tenant-filter"));
        problem.setProperty("errorCode", ex.getErrorCode());
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(CircuitOpenException.class)
    public ResponseEntity<ProblemDetail> handleCircuitOpen(CircuitOpenException ex) {
        // El circuito del destino esta abierto: no se le llama. No es lo mismo
        // que un fallo durante la llamada, y el cliente lo distingue por el
        // codigo de error y por el Retry-After (hallazgo 6.57).
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle("Servicio Destino no Disponible Temporalmente");
        problem.setType(URI.create("https://errors.erp-automotive.pe/gateway/circuit-open"));
        problem.setProperty("errorCode", ex.getErrorCode());
        problem.setProperty("timestamp", Instant.now());

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "5");

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).headers(headers).body(problem);
    }

    @ExceptionHandler(TargetServiceUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleTargetServiceUnavailable(TargetServiceUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
        problem.setTitle("Servicio Destino No Disponible");
        problem.setType(URI.create("https://errors.erp-automotive.pe/gateway/bad-gateway"));
        problem.setProperty("errorCode", ex.getErrorCode());
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problem);
    }

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ProblemDetail> handleGenericGateway(GatewayException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Error de Pasarela");
        problem.setType(URI.create("https://errors.erp-automotive.pe/gateway/generic-error"));
        problem.setProperty("errorCode", ex.getErrorCode());
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneralException(Exception ex) {
        log.error("Error no controlado en el Gateway: ", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno en el Gateway");
        problem.setTitle("Error Interno de Pasarela");
        problem.setType(URI.create("https://errors.erp-automotive.pe/gateway/internal-server-error"));
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }
}
