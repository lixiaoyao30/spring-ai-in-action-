package com.yupi.yuaiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════
 * 恋爱知识库文档加载器
 * ═══════════════════════════════════════════════════════
 *
 * 【这个类的职责 - 一句话】
 *   把 src/main/resources/documents/ 目录下的所有 .md 文件
 *   读进来，变成 Spring AI 能处理的 Document 对象列表。
 *
 * 【为什么需要它？】
 *   RAG 的第一步是"把文档喂给 AI 知识库"，但文件是磁盘上的原始文本，
 *   Spring AI 的向量数据库只认识 Document 对象，所以需要一个"翻译官"
 *   把文件 → Document，这个类就是这个翻译官。
 *
 * 【整体 RAG 流程中的位置】
 *
 *   ┌─────────────────────────────────────────────┐
 *   │  离线准备阶段（程序启动时执行一次）           │
 *   │                                             │
 *   │  .md 文件                                   │
 *   │     │                                       │
 *   │     ▼  ← 【本类负责这一步】                  │
 *   │  List<Document>（文档对象列表）               │
 *   │     │                                       │
 *   │     ▼  ← LocalMemoryVectorStoreConfig 负责       │
 *   │  Embedding 向量化 → 存入 VectorStore          │
 *   └─────────────────────────────────────────────┘
 *
 *   ┌─────────────────────────────────────────────┐
 *   │  在线查询阶段（每次用户提问时）               │
 *   │                                             │
 *   │  用户问题 → 向量检索 → 找到相关文档块         │
 *   │         → 拼进 Prompt → AI 回答              │
 *   └─────────────────────────────────────────────┘
 *
 * 【文档目录结构】
 *
 *   src/main/resources/
 *     └── documents/
 *           ├── 恋爱常见问题和回答+-+单身篇.md    ← 单身状态的问答
 *           ├── 恋爱常见问题和回答+-+恋爱篇.md    ← 恋爱状态的问答
 *           └── 恋爱常见问题和回答+-+已婚篇.md    ← 已婚状态的问答
 *
 * 【Document 对象是什么？】
 *   Document 是 Spring AI 统一的文档数据结构，包含两部分：
 *     - content（正文）：文档的实际文字内容
 *     - metadata（元信息）：描述这个文档的额外信息，比如文件名、来源
 *   例：
 *     content  = "如何提升自身魅力？提升自身魅力需从多方面入手..."
 *     metadata = { "filename": "恋爱常见问题和回答+-+单身篇.md" }
 *
 * 【为什么用 Markdown 格式的文档？】
 *   Markdown 有结构（标题、段落），MarkdownDocumentReader 能按标题自动切割，
 *   每个问答对变成独立的 Document 块，检索时更精准，不会把不相关的问答混在一起。
 */
@Slf4j
@Component
public class LoveAppDocumentLoader {

    /**
     * ResourcePatternResolver：Spring 提供的资源扫描工具
     *
     * 作用：根据通配符路径（比如 "classpath:documents/*.md"）
     *       扫描出所有匹配的文件，返回 Resource[] 数组。
     *
     * 为什么不用 new File("...")?
     *   因为打包成 jar 后，文件在 jar 包内部，File 找不到。
     *   ResourcePatternResolver 兼容"磁盘文件"和"jar 包内文件"两种情况。
     *
     * 由 Spring 自动注入，不需要手动创建。
     */
    private final ResourcePatternResolver resourcePatternResolver;

    // Spring 通过构造方法注入 ResourcePatternResolver
    public LoveAppDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    /**
     *  LoveAppDocumentLoader = 把 .md 文件打包成 Document 对象的工具类，是 RAG 流程的第一步入口，
     *  没有它后面的向量化和检索都没有原料。如果换 PDF，只需把里面的 MarkdownDocumentReader
     *   换成 TikaDocumentReader，其他不变。
     * 加载所有 Markdown 文档，返回 Document 列表。
     *
     * 调用时机：
     *   LocalMemoryVectorStoreConfig.localMemoryVectorStore() 初始化时调用本方法，
     *   把返回的 Document 列表存入向量数据库。程序启动时执行一次，之后不再重复。
     *
     * 返回值：
     *   List<Document>，每个 Document 对应文档中的一个片段（一问一答）。
     *   MarkdownDocumentReader 按 Markdown 标题（####）自动切割，
     *   一个问答对 = 一个 Document。
     *
     * @return 所有文档切割后的 Document 列表，供 VectorStore 向量化存储
     */
    public List<Document> loadMarkdowns() {
        // 用于收集所有文件的 Document，最终合并返回
        List<Document> allDocuments = new ArrayList<>();

        try {
            // ── RAG 离线阶段 · 第1步：文档收集 ────────────────────────
            // 扫描 classpath:documents/*.md，返回所有 .md 文件的 Resource 数组
            // 用 ResourcePatternResolver 而非 new File()，兼容 jar 包内路径
            // 第1行：扫描所有 .md 文件
            Resource[] resources = resourcePatternResolver.getResources("classpath:documents/*.md");

            log.info("[DocumentLoader] 扫描到 {} 个 Markdown 文件", resources.length);

            // ── RAG 离线阶段 · 第2步：文档切割（Chunking）────────────
            //
            // 【切割方式】按 Markdown 标题（####）自动切割
            //   每个 #### 标题 + 其下内容 = 一个 Document（一问一答）
            //
            // 【为什么按标题切，而不是按字数切？】
            //   我们的文档天然就是"一问一答"结构，每个问答是独立话题。
            //   按标题切 → 每块只含一个话题 → 向量语义纯粹 → 检索精准
            //   若按固定字数切，可能把一个问答劈成两半，语义残缺，检索变差。
            //
            // 【切割结果举例】
            //   单身篇.md（5个问答）→ 5个 Document
            //   恋爱篇.md（5个问答）→ 5个 Document
            //   已婚篇.md（5个问答）→ 5个 Document
            //   合计：15个 Document，每块约200字，语义完整
            //
            // 【切完之后去哪？】
            //   → LocalMemoryVectorStoreConfig 拿到这15个 Document
            //   → 调 Embedding 模型把每块变成向量数字
            //   → 存入 VectorStore，等待用户提问时检索
            for (Resource resource : resources) {
                String fileName = resource.getFilename(); // 例："恋爱常见问题和回答+-+单身篇.md"

                // 文件名格式：xxx+-+单身篇.md，用 "+-+" 切一刀取后半部分再去掉".md"
                // "恋爱常见问题和回答+-+单身篇.md".split("\\+-\\+")[1] → "单身篇.md"
                // replace(".md","") → "单身篇"
                // contains("单身") → status = "单身"
                String status = "未知";
                if (fileName != null) {
                    String suffix = fileName.split("\\+-\\+").length > 1
                            ? fileName.split("\\+-\\+")[1].replace(".md", "")
                            : fileName;
                    if (suffix.contains("单身")) status = "单身";
                    else if (suffix.contains("恋爱")) status = "恋爱";
                    else if (suffix.contains("已婚")) status = "已婚";
                }

                // 第2行：循环每个文件，配置切割规则
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        // true = 遇到水平分割线（---）也切一刀（双重切割保障）
                        .withHorizontalRuleCreateDocument(true)
                        // false = 不把代码块内容纳入 Document（文档无代码，排除干扰）
                        .withIncludeCodeBlock(false)
                        // false = 不把引用块（> 开头）纳入 Document
                        .withIncludeBlockquote(false)
                        // 元信息：记录每块来自哪个文件，便于调试和按文件过滤检索
                        // ★ 给每块贴标签：记录来自哪个文件
                        // 例：{ "filename": "单身篇.md" }
                        // 作用：检索时可以按文件名过滤，"只搜已婚篇"
                        .withAdditionalMetadata("filename", fileName != null ? fileName : "unknown")
                        //  status = 单身 / 恋爱 / 已婚，检索时可按用户状态过滤
                        //  缺的是检索时加过滤条件，目前代码里检索没有用 status
                        //  过滤，所以已婚/单身/恋爱的内容会混在一起返回。要用的话需要在调用 similaritySearch 时传
                        //  FilterExpressionBuilder，但那是另一步的事，现在先不用急。
                        //                Document {
                        //                    content  = "如何提升魅力需从多方面..."
                        //                    metadata = {
                        //                    title    : "如何提升自身魅力？"   ← 自动提取
                        //                    category : "header_4"            ← 自动提取
                        //                    filename : "单身篇.md"            ← 你手动加的
                        //                    status   : "单身"                 ← 你手动加的
                        //    }
                        //                }  这些metadata 会存入到云向量数据库
                        .withAdditionalMetadata("status", status)
                        .build();

                // MarkdownDocumentReader：读取单个 Markdown 文件
                // .get() 触发读取，返回 List<Document>
                // 内部按标题（####）自动切割：每个问答对 → 一个 Document
                // 第3行：执行切割
                MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
                // .get() 触发读取
                // 按 #### 标题自动切割：
                //   #### 如何提升魅力？    → 第1块
                //   #### 怎么找到对象？    → 第2块
                //   #### 表白技巧是什么？  → 第3块
                // 单身篇5个问答 → 5个Document块
                List<Document> docs = reader.get();

                log.info("[DocumentLoader] 文件 {} 切割出 {} 个文档块", fileName, docs.size());

                // 把这个文件的所有 Document 加入总列表
                allDocuments.addAll(docs);
            }

        } catch (IOException e) {
            // 文件读取失败（目录不存在、文件损坏等）
            // 打印错误日志但不抛异常，返回空列表，避免整个应用启动失败
            log.error("[DocumentLoader] Markdown 文档加载失败，知识库将为空", e);
        }

        log.info("[DocumentLoader] 全部加载完成，共 {} 个文档块", allDocuments.size());
        return allDocuments;

        // 返回后的下一步（由 LocalMemoryVectorStoreConfig 负责）：
        //   allDocuments → EmbeddingModel 向量化 → 存入 VectorStore
        //   用户提问时，VectorStore.similaritySearch() 从中检索最相关的块
    }
}
