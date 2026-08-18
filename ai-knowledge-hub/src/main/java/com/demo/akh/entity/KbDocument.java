package com.demo.akh.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("kb_document")
public class KbDocument {
    @TableId(type = IdType.AUTO)
    private Integer id;

    private Integer kbId;
    private String fileName;
    private String filePath;
    private String fileType;
    private Long fileSize;
    private Integer chunkCount;
    private Short status;
    private String errorMsg;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Boolean deleted;

}
