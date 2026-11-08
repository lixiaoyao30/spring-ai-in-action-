package com.yupi.yuaiagent.demo.invoke;

/**
 * 【多模态对话 - MultiModalConversation 类】
 *。接入方式： DashScope SDK
 * 适用场景：除文字外，还能发送图片、视频、音频，比如：
 *   - 看图回答问题（"这张图里有什么？"）
 *   - 识别图片中的文字（OCR）
 *   - 分析视频内容
 *
 * 对应 HTTP 路径：/services/aigc/multimodal-generation/generation
 * SDK 类：com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation
 *
 * 可用模型（多模态，需要 workspace 开通对应路由）：
 *   qwen-vl-max      视觉理解，最强
 *   qwen-vl-plus     视觉理解，均衡
 *   qwen3-vl-plus    Qwen3 视觉版，支持文字+图片+视频
 *
 * 注意：当前 workspace（ws-wh2whu9gc711wu6e）只开通了 text-generation 路由，
 *       multimodal-generation 路由暂未开通，运行会报 400 url error。
 *       需在阿里云百炼控制台申请多模态模型权限后才能使用。
 *
 * 官方文档：https://help.aliyun.com/zh/model-studio/developer-reference/use-qwen-by-calling-api
 * 多模态模型列表：https://help.aliyun.com/zh/model-studio/getting-started/models
 * 控制台（申请开通）：https://bailian.console.aliyun.com
 */
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.alibaba.dashscope.utils.Constants;
import com.alibaba.dashscope.utils.JsonUtils;

import java.util.Arrays;
import java.util.Collections;

public class SdkAiMultiModalInvoke {

    static {
        // 设置私有化部署的 workspace 地址，需要完整 https:// 前缀
        Constants.baseHttpApiUrl = TestApiKey.BASE_URL;
    }

    public static MultiModalConversationResult callWithMessage()
            throws ApiException, NoApiKeyException, InputRequiredException, UploadFileException {
        MultiModalConversation conv = new MultiModalConversation(); // 多模态对话客户端

        // 消息内容用 Map 格式：{"text": "..."} 或 {"image": "url"} 或 {"video": "url"}
        MultiModalMessage systemMsg = MultiModalMessage.builder()
                .role(Role.SYSTEM.getValue())
                .content(Arrays.asList(Collections.singletonMap("text", "You are a helpful assistant.")))
                .build();

        MultiModalMessage userMsg = MultiModalMessage.builder()
                .role(Role.USER.getValue())
                .content(Arrays.asList(Collections.singletonMap("text", "你是谁？")))
                .build();

        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .apiKey(TestApiKey.API_KEY)
                .model("qwen3-vl-plus")   // 多模态模型，需 workspace 已开通 multimodal-generation 路由
                .messages(Arrays.asList(systemMsg, userMsg))
                .build();

        return conv.call(param);
    }

    public static void main(String[] args) {
        try {
            MultiModalConversationResult result = callWithMessage();
            System.out.println(JsonUtils.toJson(result));
        } catch (ApiException | NoApiKeyException | InputRequiredException | UploadFileException e) {
            System.err.println("An error occurred while calling the generation service: " + e.getMessage());
        }
        System.exit(0);
    }
}
