package com.demo.akh.dto;

import lombok.Data;

// dto/KbQueryDTO.java
@Data
public class KbQueryDTO {
    private String keyword;
    private Integer visibility;
    private Integer pageNo = 1;
    private Integer pageSize = 10;
}

