package com.demo.akh.service;


import com.baomidou.mybatisplus.spring.service.IService;
import com.demo.akh.dto.KnowledgeBaseCreateDTO;
import com.demo.akh.dto.KbQueryDTO;
import com.demo.akh.entity.KnowledgeBase;
import com.demo.akh.vo.PageVO;

public interface KnowledgeBaseService extends IService<KnowledgeBase> {
    Long createKb(KnowledgeBaseCreateDTO dto);
    PageVO<KnowledgeBase> pageQuery(KbQueryDTO query);
    void removeKb(Long id);
}