package com.yupi.yuaiagent.demo.invoke;

/**
 * 【纯文本对话 - Generation 类】
 *  接入方式： DashScope SDK
 *
 * 适用场景：只发送/接收文字的对话，比如：
 *   - 问答、写作、代码生成、翻译、摘要
 *
 * 对应 HTTP 路径：/services/aigc/text-generation/generation
 * SDK 类：com.alibaba.dashscope.aigc.generation.Generation
 *
 * 可用模型（纯文本）：
 *   qwen-max      最强，适合复杂推理
 *   qwen-plus     均衡，性价比高
 *   qwen-turbo    最快最便宜，简单任务
 *
 * 官方文档：https://help.aliyun.com/zh/model-studio/developer-reference/use-qwen-by-calling-api
 * 模型列表：https://help.aliyun.com/zh/model-studio/getting-started/models
 */
import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.Constants;
import com.alibaba.dashscope.utils.JsonUtils;

import java.util.Arrays;

public class SdkAiTextInvoke {

    static {
        // 设置私有化部署的 workspace 地址，需要完整 https:// 前缀
        // {WorkspaceId} 已替换为真实值：ws-wh2whu9gc711wu6e
        Constants.baseHttpApiUrl = TestApiKey.BASE_URL;
    }

    public static GenerationResult callWithMessage() throws ApiException, NoApiKeyException, InputRequiredException {
        Generation gen = new Generation(); // 纯文本对话客户端

        Message systemMsg = Message.builder()
                .role(Role.SYSTEM.getValue())
                .content("You are a helpful assistant.")
                .build();

        Message userMsg = Message.builder()
                .role(Role.USER.getValue())
                .content("你好，我是Java程序员，请介绍一下你自己")
                .build();

        GenerationParam param = GenerationParam.builder()
                .apiKey(TestApiKey.API_KEY)
                .model("qwen-max")                                    // 纯文本模型
                .messages(Arrays.asList(systemMsg, userMsg))
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)   // 推荐 message 格式，方便多轮对话
                .build();

        return gen.call(param);
    }

    public static void main(String[] args) {
        try {
            GenerationResult result = callWithMessage();
            System.out.println(JsonUtils.toJson(result));
        } catch (ApiException | NoApiKeyException | InputRequiredException e) {
            System.err.println("调用失败: " + e.getMessage());
        }
        System.exit(0);
    }
}
