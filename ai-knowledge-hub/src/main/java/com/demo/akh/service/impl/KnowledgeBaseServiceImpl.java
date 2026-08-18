package com.demo.akh.service.impl;

import cn.hutool.core.lang.UUID;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.demo.akh.common.exception.BizException;
import com.demo.akh.dto.KbQueryDTO;
import com.demo.akh.dto.KnowledgeBaseCreateDTO;
import com.demo.akh.entity.KnowledgeBase;
import com.demo.akh.mapper.KnowledgeBaseMapper;
import com.demo.akh.service.KnowledgeBaseService;
import com.demo.akh.util.RedisUtil;
import com.demo.akh.vo.PageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl
        extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase>
        implements KnowledgeBaseService {



    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createKb(KnowledgeBaseCreateDTO dto) {
        // 同名校验
        Long exists = lambdaQuery().eq(KnowledgeBase::getName, dto.getName()).count();
        if (exists != null && exists > 0) {
            throw new BizException(4002, "知识库名称已存在：" + dto.getName());
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(dto.getName());
        kb.setDescription(dto.getDescription());
        kb.setVisibility(dto.getVisibility());
        kb.setDocCount(0);
        kb.setCreateBy("system");
        save(kb);                       // MyBatis-Plus 自带
        log.info("创建知识库成功, id={}, name={}", kb.getId(), kb.getName());
        return kb.getId();              // 插入后主键会回填到对象
    }

    @Override
    public PageVO<KnowledgeBase> pageQuery(KbQueryDTO query) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<KnowledgeBase>()
                .like(StringUtils.hasText(query.getKeyword()), KnowledgeBase::getName, query.getKeyword())
                .eq(query.getVisibility() != null, KnowledgeBase::getVisibility, query.getVisibility())
                .orderByDesc(KnowledgeBase::getCreateTime);

        Page<KnowledgeBase> page = page(new Page<>(query.getPageNo(), query.getPageSize()), wrapper);

//        lambdaQuery().like(StringUtils.hasText(query.getKeyword()), KnowledgeBase::getName, query.getKeyword())
//                .eq(query.getVisibility() != null, KnowledgeBase::getVisibility, query.getVisibility())
//                .orderByDesc(KnowledgeBase::getCreateTime).page(new Page<>(query.getPageNo(), query.getPageSize()));

        return PageVO.of(page.getTotal(), page.getRecords());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeKb(Long id) {
        KnowledgeBase kb = getById(id);
        if (kb == null) {
            throw new BizException(4004, "知识库不存在");
        }
        removeById(id);   // 因为有 @TableLogic，实际执行 UPDATE ... SET deleted=1
    }

    private static final String KB_CACHE_KEY = "akh:kb:detail:";
    private static final String KB_LOCK_KEY  = "akh:kb:lock:";

    private final RedisUtil redisUtil;   // 构造注入（@RequiredArgsConstructor 自动处理）

    public KnowledgeBase getDetailWithCache(Long id) {
        String key = KB_CACHE_KEY + id;

        // 1. 先查缓存
        KnowledgeBase cached = redisUtil.get(key, KnowledgeBase.class);
        if (cached != null) {
            return cached;
        }

        // 2. 防击穿：加分布式锁，只让一个线程去查库
        String lockKey = KB_LOCK_KEY + id;
        String requestId = UUID.randomUUID().toString();
        try {
            if (!redisUtil.tryLock(lockKey, requestId, 10)) {
                Thread.sleep(50);
                return getDetailWithCache(id);        // 简单重试
            }
            // 双重检查
            cached = redisUtil.get(key, KnowledgeBase.class);
            if (cached != null) {
                return cached;
            }

            KnowledgeBase kb = getById(id);

            // 3. 防穿透：查不到也缓存一个空值（短过期）
            if (kb == null) {
                redisUtil.set(key, new KnowledgeBase(), 60, TimeUnit.SECONDS);
                throw new BizException(4004, "知识库不存在");
            }

            // 4. 防雪崩：过期时间加随机扰动
            long ttl = 600 + ThreadLocalRandom.current().nextInt(120);
            redisUtil.set(key, kb, ttl, TimeUnit.SECONDS);
            return kb;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("查询被中断");
        } finally {
            redisUtil.unlock(lockKey, requestId);
        }
    }
}