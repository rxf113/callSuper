package com.rxf113;

/**
 * 自定义异常，标记是在对MustCallSuper注解处理时抛的异常
 *
 * @author rxf113
 */
public class MustCallSuperException extends RuntimeException {
    public MustCallSuperException(String message) {
        super("MustCallSuper annotation process exception, msg: " + message);
    }

    public MustCallSuperException(String message, Throwable cause) {
        super("MustCallSuper annotation process exception, msg: " + message, cause);
    }
}
