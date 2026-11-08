package com.yupi.yuaiagent.rag;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 关键词提取器 — 用大模型给每个文档块自动打关键词标签
 *
 * 【解决什么问题】
 *   向量检索靠"语义相似度"，但有时用户问的词和文档里的词差异很大：
 *     用户问："怎么让自己更有吸引力"
 *     文档里："提升自身魅力需从多方面入手..."
 *   词不一样但意思相同，向量能搜到。
 *
 *   但某些精确词搜不到：
 *     用户问："穿搭技巧"   文档里没有"穿搭"二字，向量相似度低 → 搜不到
 *   加了关键词后：
 *     metadata.keywords = ["穿搭", "着装", "形象", "魅力"]
 *     用户搜"穿搭" → 命中关键词 → 搜到
 *
 * 【企业真实场景举例】
 *   电商平台商品知识库：
 *     文档："本款采用高支棉面料，亲肤透气，适合夏季穿着"
 *     AI提取关键词：["棉", "透气", "夏季", "面料", "亲肤"]
 *     用户搜"夏天穿什么凉快" → 命中"夏季"关键词 → 精准推荐
 *
 *   法律文档知识库：
 *     文档：一段合同违约条款
 *     AI提取关键词：["违约金", "赔偿", "解除合同", "期限"]
 *     律师搜"合同解除" → 直接命中关键词
 *
 * 【原理】
 *   把每个 Document 的 content 发给大模型，让它提取5个关键词，
 *   写入 metadata.keywords，存入向量库。
 *   检索时向量搜索 + 关键词匹配双重命中，精准度更高。
 *
 * 【存入后 metadata 长这样】
 *   {
 *     "title"    : "如何提升自身魅力？",
 *     "status"   : "单身",
 *     "filename" : "单身篇.md",
 *     "keywords" : "穿搭, 魅力, 形象, 社交, 自信"   ← 本类新增
 *   }
 */
@Slf4j
@Component
public class MyKeywordEnricher {

    // 用大模型提取关键词，注入阿里云 DashScope 的 ChatModel
    @Resource
    private ChatModel dashScopeChatModel;

    /**
     * 给文档块批量打关键词标签
     * keywordCount=5：每块提取5个关键词，太多冗余，太少不够用
     *
     * @param documents 待处理的文档块列表（来自 LoveAppDocumentLoader 或切割后的块）
     * @return 每个 Document 的 metadata 里新增了 keywords 字段
     */
    public List<Document> enrichDocuments(List<Document> documents) {
        try {
            KeywordMetadataEnricher enricher = new KeywordMetadataEnricher(dashScopeChatModel, 5);
            return enricher.apply(documents);
        } catch (Exception e) {
            // 关键词增强失败时降级：直接返回原始文档，不影响向量库初始化
            // 常见原因：模型不支持该调用格式（如 qwen3.x 系列）
            log.warn("[KeywordEnricher] 关键词增强失败，降级为无关键词模式: {}", e.getMessage());
            return documents;
        }
    }
}
