package com.yupi.yuaiagent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

// 不需要 @SpringBootTest，FileOperationTool 无 Spring 依赖，直接 new 即可
class FileOperationToolTest {

    private final FileOperationTool tool = new FileOperationTool();

    @Test
    void writeFile() {
        String result = tool.writeFile("test.txt", "我喜欢你");
        System.out.println(result);
        assertTrue(result.contains("成功"));
    }

    @Test
    void readFile() {
        // 先写再读，验证内容一致
        tool.writeFile("test.txt", "我喜欢你");
        String content = tool.readFile("test.txt");
        System.out.println(content);
        assertTrue(content.contains("我喜欢你"));
    }
}
