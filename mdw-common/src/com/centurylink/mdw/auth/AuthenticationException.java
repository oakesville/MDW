package com.centurylink.mdw.auth;

public class AuthenticationException extends MdwSecurityException {

    public AuthenticationException(String message) {
        super(message);
    }

    public AuthenticationException(String message, Throwable cause) {
      super(message, cause);
    }
}
