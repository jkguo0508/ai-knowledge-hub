package com.demo.akh.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class KnowledgeBaseCreateDTO {

    @NotBlank(message = "知识库名称不能为空")
    @Size(max = 64, message = "知识库名称最长 64 字")
    private String name;

    @Size(max = 255, message = "描述最长 255 字")
    private String description;

    @NotNull(message = "可见性不能为空")
    @Min(value = 0, message = "可见性只能是 0 或 1")
    @Max(value = 1, message = "可见性只能是 0 或 1")
    private Integer visibility;
}