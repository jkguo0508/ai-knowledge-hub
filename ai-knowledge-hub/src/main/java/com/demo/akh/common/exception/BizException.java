package com.demo.akh.common.exception;

import lombok.Getter;

@Getter
public class BizException extends RuntimeException{
    private final Integer code;

    public BizException(String msg){
        super(msg);
        this.code=500;
    }

    public BizException(Integer code,String msg){
        super(msg);
        this.code = code;
    }
}
