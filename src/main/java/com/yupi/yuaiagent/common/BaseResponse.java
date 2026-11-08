package com.yupi.yuaiagent.common;

import lombok.Data;

@Data
public class BaseResponse<T> {

    private int code;
    private String message;
    private T data;

    public static <T> BaseResponse<T> success(T data) {
        BaseResponse<T> response = new BaseResponse<>();
        response.code = 200;
        response.message = "ok";
        response.data = data;
        return response;
    }

    public static <T> BaseResponse<T> error(int code, String message) {
        BaseResponse<T> response = new BaseResponse<>();
        response.code = code;
        response.message = message;
        response.data = null;
        return response;
    }
}
