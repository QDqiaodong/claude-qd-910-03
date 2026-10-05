package com.shipyard.dock.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shipyard.dock.common.ApiResponse;
import com.shipyard.dock.domain.Block;
import com.shipyard.dock.dto.BlockRequest;
import com.shipyard.dock.service.BlockService;

/**
 * 模块三：船体分段。
 *
 * <p>页面形态为「卡片墙 + 状态徽章」：一张卡一个分段，点卡在工作区里新开一个分页详情标签。
 * 分段编码不在这里拼，由 Hibernate 的 {@code @ValueGenerationType} 生成器在插入前生成。</p>
 */
@RestController
@RequestMapping("/api/block")
public class BlockController {

    private final BlockService service;

    public BlockController(BlockService service) {
        this.service = service;
    }

    @GetMapping("/board")
    public ApiResponse<Map<String, Object>> board() {
        return ApiResponse.success(service.board());
    }

    @GetMapping("/list")
    public ApiResponse<List<Block>> list() {
        return ApiResponse.success(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Block> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @GetMapping("/{id}/detail")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping
    public ApiResponse<Block> create(@RequestBody BlockRequest request) {
        Block block = service.create(request);
        return ApiResponse.success("分段已建档，编码 " + block.getBlockCode(), block);
    }

    @PutMapping("/{id}")
    public ApiResponse<Block> update(@PathVariable Long id, @RequestBody BlockRequest request) {
        return ApiResponse.success("分段已更新", service.update(id, request));
    }

    /** 推进一级分段状态：预制 → 涂装 → 搭载 → 合拢 → 交验 */
    @PostMapping("/{id}/advance")
    public ApiResponse<Block> advance(@PathVariable Long id) {
        return ApiResponse.success("分段状态已推进", service.advance(id));
    }

    /** 涂装报完工 */
    @PostMapping("/{id}/paint")
    public ApiResponse<Block> finishPaint(@PathVariable Long id) {
        return ApiResponse.success("分段涂装已报完工", service.finishPaint(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.success("分段已删除", null);
    }
}
