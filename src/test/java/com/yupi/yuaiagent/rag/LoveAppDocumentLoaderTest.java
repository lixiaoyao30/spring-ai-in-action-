package com.yupi.yuaiagent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LoveAppDocumentLoader 测试
 *
 * 验证三件事：
 *   ① 能扫到 documents/ 目录下的 .md 文件
 *   ② 按 #### 标题切割后，Document 数量 > 0
 *   ③ 每个 Document 有正文内容 + filename 元信息
 *
 * 运行方式：直接右键 → Run，不需要启动 AI 服务
 */
@SpringBootTest
class LoveAppDocumentLoaderTest {

    @Autowired
    private LoveAppDocumentLoader loader;

    @Test
    void testLoadMarkdowns() {
        List<Document> docs = loader.loadMarkdowns();

        // ① 至少加载出1个文档块（3个文件每个至少5个问答 = 至少15块）
        assertFalse(docs.isEmpty(), "文档列表不应为空，检查 documents/ 目录是否有 .md 文件");
        System.out.println("共切割出 " + docs.size() + " 个 Document 块");

        // ② 每个 Document 必须有正文内容
        for (Document doc : docs) {
            assertNotNull(doc.getText(), "Document 正文不能为 null");
            assertFalse(doc.getText().isBlank(), "Document 正文不能为空白");
        }

        // ③ 每个 Document 必须有 filename 元信息
        for (Document doc : docs) {
            Object filename = doc.getMetadata().get("filename");
            assertNotNull(filename, "Document 应包含 filename 元信息");
        }

        // 打印第一块内容，直观看切割效果
        Document first = docs.get(0);
        System.out.println("── 第1块预览 ──");
        System.out.println("来源文件：" + first.getMetadata().get("filename"));
        System.out.println("正文前100字：" + first.getText().substring(0, Math.min(100, first.getText().length())));

        // 按文件名统计每个文件切出了几块
        System.out.println("── 各文件切块数量 ──");
        docs.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        d -> String.valueOf(d.getMetadata().get("filename")),
                        java.util.stream.Collectors.counting()
                ))
                .forEach((file, count) -> System.out.println(file + " → " + count + " 块"));
    }
}
