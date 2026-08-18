package com.demo.akh.vo;


import lombok.Data;

import java.util.List;

// vo/PageVO.java
@Data
public class PageVO<T> {
    private Long total;
    private List<T> records;

    public static <T> PageVO<T> of(Long total, List<T> records) {
        PageVO<T> vo = new PageVO<>();
        vo.setTotal(total);
        vo.setRecords(records);
        return vo;
    }
}