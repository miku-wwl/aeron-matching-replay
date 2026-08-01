package io.github.mikuwwl.matchingreplay.api;

import io.github.mikuwwl.matchingreplay.application.ReplayCapacityException;
import io.github.mikuwwl.matchingreplay.application.ReplayConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler
{
    @ExceptionHandler(ReplayNotFoundException.class)
    ProblemDetail notFound(final ReplayNotFoundException ex)
    {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ReplayConflictException.class)
    ProblemDetail conflict(final ReplayConflictException ex)
    {
        return problem(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(ReplayCapacityException.class)
    ProblemDetail unavailable(final ReplayCapacityException ex)
    {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class
    })
    ProblemDetail badRequest(final Exception ex)
    {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    private static ProblemDetail problem(final HttpStatus status, final String detail)
    {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        return problem;
    }
}
