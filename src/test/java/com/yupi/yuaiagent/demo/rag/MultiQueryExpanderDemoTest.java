package com.yupi.yuaiagent.demo.rag;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.rag.Query;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * MultiQueryExpanderDemo 测试
 *
 * 运行后看控制台输出，验证：
 *   1. AI 是否把原始问题扩展成多个子问题
 *   2. 扩展出的问题是否语义相关、表达不同
 */
@SpringBootTest
class MultiQueryExpanderDemoTest {

    @Resource
    private MultiQueryExpanderDemo multiQueryExpanderDemo;

    @Test
    void expand() {
        String originalQuery = "单身怎么提升？请回答我哈哈哈哈";

        List<Query> queries = multiQueryExpanderDemo.expand(originalQuery);

        // 验证扩展结果不为空
        assertNotNull(queries);
        assertFalse(queries.isEmpty());

        // 打印每个扩展后的子问题（控制台可见）
        System.out.println("-------- 断言通过，扩展结果如下 --------");
        for (int i = 0; i < queries.size(); i++) {
            System.out.println("子问题[" + (i + 1) + "]: " + queries.get(i).text());
        }
        // 单身怎么提升？请回答我哈哈哈哈
        // 单身人士如何系统性提升自我价值与综合能力？
        // 单身状态下有哪些科学验证的个人成长路径和实践方法？
        // 针对单身群体，心理学/社会学/职业发展领域推荐的提升策略有哪些？
        System.out.println("共扩展出 " + queries.size() + " 个问题");
    }
}
