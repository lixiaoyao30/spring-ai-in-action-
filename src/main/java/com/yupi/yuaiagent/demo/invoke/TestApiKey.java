package com.yupi.yuaiagent.demo.invoke;

/**
 * SDK 直接调用时使用的密钥常量（用于 SdkAiTextInvoke、HttpAiInvoke 等 demo）
 *
 * ⚠️ 注意：
 *   API_KEY 与 application-local.yml 中 spring.ai.dashscope.api-key 是同一个值
 *   两处需保持一致，更换 key 时记得同步修改
 *
 * 生产环境建议通过环境变量注入，不要硬编码在代码里
 */
public interface TestApiKey {

    // 阿里云百炼 DashScope API Key（替换为你自己的 key，从 https://bailian.console.aliyun.com/ 获取）
    String API_KEY = "your-dashscope-api-key-here";

    // DashScope API 完整地址（替换为你自己的 WorkspaceId）
    String BASE_URL = "https://your-workspace-id.cn-beijing.maas.aliyuncs.com/api/v1";

    // DashScope SDK 专用：不含 https:// 前缀（Generation 双参数构造函数使用）
    String DASHSCOPE_BASE_URL = "your-workspace-id.cn-beijing.maas.aliyuncs.com/api/v1";
}
