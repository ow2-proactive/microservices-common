/*
 * ProActive Parallel Suite(TM):
 * The Open Source library for parallel and distributed
 * Workflows & Scheduling, Orchestration, Cloud Automation
 * and Big Data Analysis on Enterprise Grids & Clouds.
 *
 * Copyright (c) 2007 - 2017 ActiveEon
 * Contact: contact@activeeon.com
 *
 * This library is free software: you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation: version 3 of
 * the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * If needed, contact us to obtain a release under GPL Version 2 or 3
 * or a different license than the AGPL.
 */
package org.ow2.proactive.microservices.common.exception;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;

import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import lombok.AccessLevel;
import lombok.Getter;


public abstract class ExceptionHandlerAdvice {

    private Logger log;

    @Getter(value = AccessLevel.PUBLIC)
    @Value("${pa.hide.exceptions:true}")
    private Boolean hideExceptions;

    protected ExceptionHandlerAdvice(Logger logger) {
        this.log = logger;
    }

    @ExceptionHandler(ClientException.class)
    public ResponseEntity clientErrorHandler(Exception exception) throws Exception {
        if (!hideExceptions) {
            log.warn("Exception: " + exception.getLocalizedMessage());
        } else {
            // log full exception when it is hidden from the response
            log.warn("Exception: " + exception.getLocalizedMessage(), exception);
        }

        HttpStatus responseStatusCode = resolveAnnotatedResponseStatus(exception);

        return ResponseEntity.status(responseStatusCode)
                             .body(new ErrorResource(responseStatusCode.value(),
                                                     exception.getLocalizedMessage(),
                                                     getStackTrace(exception)));
    }

    /**
     * Automatically catch unhandled exceptions and return a server error to the user
     * @param e the exception caught
     * @return a server error
     */
    @ExceptionHandler(Exception.class)
    public @ResponseBody ResponseEntity<Object> serverErrorHandler(Exception e) {
        final HttpStatus httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        log.error("Server exception: " + e.getMessage(), e);
        return new ResponseEntity(new ErrorResource(httpStatus.value(), httpStatus.getReasonPhrase(), getStackTrace(e)),
                                  httpStatus);
    }

    /**
     * Check if the binding error is a client error due to a bad session
     * @param e the exception caught
     * @return unauthorized or server error
     */
    @ExceptionHandler(ServletRequestBindingException.class)
    public @ResponseBody ResponseEntity<Object> badSessionIdHandler(ServletRequestBindingException e) {
        //checking sessionID is specific to the catalog
        if (e.getMessage().contains("sessionid") || e.getMessage().contains("sessionID")) {
            final HttpStatus httpStatus = HttpStatus.UNAUTHORIZED;
            return new ResponseEntity(new ErrorResource(httpStatus.value(),
                                                        httpStatus.getReasonPhrase(),
                                                        getStackTrace(e)),
                                      httpStatus);
        } else {
            return serverErrorHandler(e);
        }
    }

    /**
     *  Automatically catch HttpMessageNotReadableException thrown by Spring and return bad request
     *  Here we assume that the user provide an ill-formed json
     * @param e the exception caught
     * @return a bad request error
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public @ResponseBody ResponseEntity<Object> springErrorHandler(HttpMessageNotReadableException e) {
        log.info("HttpMessageNotReadableException caught by ExceptionHandlerAdvice", e);
        return createClientErrorResponseEntity(HttpStatus.BAD_REQUEST,
                                               new ClientException("ill formed body: " + e.getMessage()));
    }

    /**
     *  Automatically catch MethodArgumentTypeMismatchException thrown by Spring and return bad request
     *  Here we assume that the user provide a string instead of a numerical value
     * @param e the exception caught
     * @return a bad request error
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public @ResponseBody ResponseEntity<Object> springErrorHandler(MethodArgumentTypeMismatchException e) {
        log.info("MethodArgumentTypeMismatchException caught by ExceptionHandlerAdvice", e);
        return createClientErrorResponseEntity(HttpStatus.BAD_REQUEST,
                                               new ClientException("wrong type parameter " + e.getMessage()));
    }

    /**
     *  Automatically catch MethodArgumentTypeMismatchException thrown by Spring and return bad request
     *  Here we assume that the user didn't provide a required parameter
     * @param e the exception caught
     * @return a bad request error
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public @ResponseBody ResponseEntity<Object> springErrorHandler(MissingServletRequestParameterException e) {
        log.info("MissingServletRequestParameterException caught by ExceptionHandlerAdvice", e);
        return createClientErrorResponseEntity(HttpStatus.BAD_REQUEST,
                                               new ClientException("missing parameter: " + e.getMessage()));
    }

    /**
     *  Automatically catch HttpMediaTypeNotSupportedException thrown by Spring and return not supported media type
     *  Here we assume that the user didn't provide a correct body
     * @param e the exception caught
     * @return a not supported media type error
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public @ResponseBody ResponseEntity<Object> springErrorHandler(HttpMediaTypeNotSupportedException e) {
        log.info("HttpMediaTypeNotSupportedException caught by ExceptionHandlerAdvice", e);
        return createClientErrorResponseEntity(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                                               new ClientException("body does not have a correct value"));
    }

    private ResponseEntity createClientErrorResponseEntity(HttpStatus httpStatus, Exception e) {
        String errorMessage = httpStatus.getReasonPhrase() +
                              Optional.ofNullable(e.getMessage()).map(message -> ", " + message).orElse("");
        if (!hideExceptions) {
            log.info("Client exception: " + errorMessage);
        } else {
            // log full exception when it is hidden from the response
            log.info("Client exception: " + errorMessage, e);
        }

        return new ResponseEntity(new ErrorResource(httpStatus.value(), errorMessage, getStackTrace(e)), httpStatus);
    }

    private HttpStatus resolveAnnotatedResponseStatus(Exception exception) throws Exception {
        ResponseStatus annotation = AnnotationUtils.findAnnotation(exception.getClass(), ResponseStatus.class);
        if (annotation != null) {
            return annotation.code();
        } else
            throw exception;
    }

    private String getStackTrace(final Throwable throwable) {
        if (!hideExceptions) {
            final StringWriter sw = new StringWriter();
            final PrintWriter pw = new PrintWriter(sw, true);
            throwable.printStackTrace(pw);
            return sw.getBuffer().toString();
        } else {
            return null;
        }
    }

}
