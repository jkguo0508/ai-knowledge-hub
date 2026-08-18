package com.demo.akh.controller;

import com.demo.akh.common.result.Result;
import com.demo.akh.dto.KbQueryDTO;
import com.demo.akh.dto.KnowledgeBaseCreateDTO;
import com.demo.akh.entity.KnowledgeBase;
import com.demo.akh.service.KnowledgeBaseService;
import com.demo.akh.vo.PageVO;
import com.github.xiaoymin.knife4j.spring.annotations.EnableKnife4j;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "知识库管理")
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;//final表示必须对其进行初始化


    @Operation(summary = "创建知识库")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody KnowledgeBaseCreateDTO dto) {
        return Result.success(kbService.createKb(dto));
    }

    @Operation(summary = "分页查询")
    @GetMapping("/page")
    public Result<PageVO<KnowledgeBase>> page(KbQueryDTO query) {
        return Result.success(kbService.pageQuery(query));
    }

    @Operation(summary = "详情")
    @GetMapping("/{id}")
    public Result<KnowledgeBase> detail(@PathVariable Long id) {
        return Result.success(kbService.getById(id));
    }

    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        kbService.removeKb(id);
        return Result.success();
    }
}