package com.yupi.yuimagesearchmcpserver.tools;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * ImageSearchTool 单元测试
 *
 * 运行前置条件：
 *   1. 把 ImageSearchTool 里的 API_KEY 替换成你自己的 Pixabay Key
 *      获取地址：https://pixabay.com/api/docs/
 *   2. 确保本机能访问 https://pixabay.com（需要网络）
 *
 * 测试目的：
 *   在接入 MCP Client（yu-ai-agent）之前，先单独验证工具方法本身是否正常工作
 *   相当于"先测零件，再测整机"，方便快速定位问题
 *
 * 为什么用 sse profile：
 *   stdio profile 会关闭 Web 容器（web-application-type: none），
 *   测试时需要完整的 Spring 上下文，所以切换到 sse profile 启动
 */
@SpringBootTest
@ActiveProfiles("sse")
class ImageSearchToolTest {

    @Resource
    private ImageSearchTool imageSearchTool;

    /**
     * 测试搜索图片是否返回有效结果
     *
     * 预期行为：
     *   输入关键词 "computer" → 调用 Pixabay API → 返回逗号分隔的图片 URL 字符串
     *   例如："https://cdn.pixabay.com/photo/xxx_640.jpg,https://cdn.pixabay.com/photo/yyy_640.jpg,..."
     *
     * 如果测试失败，可能的原因：
     *   - API_KEY 未替换或填写错误 → 检查 ImageSearchTool.API_KEY
     *   - 网络不通 → 检查是否能访问 pixabay.com
     *   - Pixabay 限流（100次/60秒）→ 稍等片刻再试
     */
    @Test
    void searchImage() {
        String result = imageSearchTool.searchImage("computer");
        System.out.println("搜索结果：" + result);
        Assertions.assertNotNull(result);
        Assertions.assertFalse(result.startsWith("Error"), "接口调用出错：" + result);
    }
}
