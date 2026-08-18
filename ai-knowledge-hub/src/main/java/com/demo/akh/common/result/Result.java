package com.demo.akh.common.result;

import lombok.Data;

import java.beans.PropertyEditorSupport;
import java.io.Serializable;

@Data
public class Result <T> implements Serializable {
    private Integer code;
    private String msg;
    private T data;
    private Long timestamp;

    public Result (Integer code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> Result <T> success() {
        return new Result<T>(200,"success",null);
    }

    public static <T> Result <T> success(T data) {
        return new Result<T>(200,"success",data);
    }

    public static <T> Result<T> fail(String msg) {
        return new Result<T>(500,msg,null);
    }
    public static <T> Result<T> fail(Integer code,String msg) {
        return new Result<T>(code,msg,null);
    }
}
