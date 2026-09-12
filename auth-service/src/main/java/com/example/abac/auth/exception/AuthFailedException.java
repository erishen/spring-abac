package com.example.abac.auth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class AuthFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuthFailedException(String message) {
        super(message);
    }
}
