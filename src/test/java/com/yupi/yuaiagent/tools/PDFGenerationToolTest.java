package com.yupi.yuaiagent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
public class PDFGenerationToolTest {

    @Test
    public void testGeneratePDF() {
        PDFGenerationTool tool = new PDFGenerationTool();
        String result = tool.generatePDF("springai.pdf", "编程导航原创项目 https://www.codefather.cn");
        System.out.println("生成结果：" + result);
        assertNotNull(result);
    }
}
