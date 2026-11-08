package com.yupi.yuaiagent.demo.invoke;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;

/**
 * 【接入方式二：HTTP 直接调用（Hutool）】
 *
 * ─────────────────────────────────────────────
 * 为什么有 SDK 还要学 HTTP 方式？
 * ─────────────────────────────────────────────
 * SDK 本质上也是在帮你发 HTTP 请求，只是封装好了。
 * 直接用 HTTP 的优势：
 *   1. 语言无限制 —— PHP/Go/Ruby 没有官方 SDK，只能走 HTTP
 *   2. 更灵活     —— 想用哪个参数就用哪个，不受 SDK 版本限制
 *   3. 便于排查   —— 可以先用 curl 测通，再翻译成代码
 *
 * 所以：有 SDK → 优先用 SDK（省事）
 *       没 SDK → 用 HTTP（万能）
 * ─────────────────────────────────────────────
 *
 * 本文件等价于这条 curl 命令：
 *
 *   curl --location "https://{WorkspaceId}...text-generation/generation" \
 *     --header "Authorization: Bearer $API_KEY" \
 *     --header "Content-Type: application/json" \
 *     --data '{"model":"qwen-max","input":{...},"parameters":{...}}'
 *
 * 官方文档：https://help.aliyun.com/zh/model-studio/developer-reference/use-qwen-by-calling-api
 */
public class HttpAiInvoke {

    // ① 完整的 API 地址（WorkspaceId 已填入，无需再拼接）
    //    text-generation      → 纯文本模型（qwen-max / qwen-plus）
    //    multimodal-generation → 多模态模型（qwen-vl-max，需 workspace 开通）
    private static final String API_URL =
            "https://ws-wh2whu9gc711wu6e.cn-beijing.maas.aliyuncs.com/api/v1" +
            "/services/aigc/text-generation/generation";

    public static String callWithMessage() {
        // ② 构建请求体（对应 curl 的 --data 部分）
        JSONObject body = new JSONObject()
                .set("model", "qwen-max")
                .set("input", new JSONObject()
                        .set("messages", new JSONArray()
                                .set(msg("system", "You are a helpful assistant."))
                                .set(msg("user",   "你是谁？"))))
                .set("parameters", new JSONObject()
                        .set("result_format", "message")); // message 格式方便多轮对话

        // ③ 发送请求（对应 curl 的 --header 和 --location 部分）
        return HttpRequest.post(API_URL)
                .header("Authorization", "Bearer " + TestApiKey.API_KEY) // API Key 鉴权
                .header("Content-Type", "application/json")
                .body(body.toString())
                .execute()
                .body();
    }

    // 辅助方法：构建一条消息，避免重复代码
    private static JSONObject msg(String role, String content) {
        return new JSONObject().set("role", role).set("content", content);
    }

    public static void main(String[] args) {
        String result = callWithMessage();

        // ④ 解析响应，取出模型回复的文字
        //    响应结构：{ "output": { "choices": [ { "message": { "content": "..." } } ] } }
        String content = new JSONObject(result)
                .getJSONObject("output")
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getStr("content");

        System.out.println("模型回复：" + content);
    }
}
