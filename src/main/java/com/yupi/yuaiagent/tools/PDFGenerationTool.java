package com.yupi.yuaiagent.tools;

import com.yupi.yuaiagent.constant.FileConstant;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * PDF 生成工具 —— 根据内容生成 PDF 文件并保存到本地
 *
 * 触发场景：用户要求"生成 PDF"、"导出文档"等
 * 实现：Apache PDFBox + macOS 系统中文字体（.ttc 需用 TrueTypeCollection 提取）
 *
 * ═══════════════════════════════════════════════════════════════
 * 【进阶知识一】大模型怎么知道怎么调这个工具？—— ToolDefinition 说明书
 * ═══════════════════════════════════════════════════════════════
 *
 * 大模型看不懂 Java 方法，Spring AI 把 @Tool 方法翻译成 JSON 说明书发给它：
 *
 *   {
 *     "name": "generatePDF",
 *     "description": "Generate a PDF file with the given content",
 *     "parameters": {
 *       "properties": {
 *         "fileName": { "type": "string" },
 *         "content":  { "type": "string" }
 *       },
 *       "required": ["fileName", "content"]
 *     }
 *   }
 *
 * 大模型收到后：靠 description 决定什么时候调，靠 parameters 知道传什么参数。
 *
 * @Tool 背后 Spring AI 自动做了三件事：
 *   ① JsonSchemaGenerator   → 解析方法签名，生成上面的 JSON 说明书
 *   ② ToolCallResultConverter → 把返回值（任意类型）统一转成 String 发给大模型
 *   ③ MethodToolCallback    → 把方法包装成 ToolCallback 接口实现
 * 类比：你只写"锤子功能"，Spring AI 自动生成"锤子说明书+翻译器+包装盒"。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【进阶知识二】ToolContext —— 偷偷传参，大模型看不见
 * ═══════════════════════════════════════════════════════════════
 *
 * 场景：用户说"帮我退款"，工具需要知道当前登录的 userId，但不能让用户手输。
 * 解法：调用侧把 userId 塞进 ToolContext，工具方法多一个参数接收，大模型全程不知道。
 *
 * 调用侧：
 *   chatClient.prompt("帮我退款")
 *             .toolContext(Map.of("userId", getCurrentUserId()))  // 塞进去
 *             .call().content();
 *
 * 工具侧：
 *   @Tool(description = "Process refund")
 *   String processRefund(String orderId, ToolContext ctx) {  // 多一个 ctx 参数
 *       String userId = (String) ctx.get("userId");          // 取出来用
 *       return refundService.refund(userId, orderId);
 *   }
 *
 * 核心：ToolContext 本质是 Map，发给大模型的 HTTP 请求里完全没有它，只在 Java 层流转。
 * 适用：登录凭证、租户ID、请求追踪ID 等不能暴露给大模型的内部信息。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【进阶知识三】returnDirect —— 省掉一次多余的大模型调用
 * ═══════════════════════════════════════════════════════════════
 *
 * 正常流程（2 次 HTTP 请求）：
 *   用户问 → 大模型①返回工具意图 → 执行工具 → 结果发给大模型② → 大模型②回复用户
 *
 * returnDirect = true（1 次 HTTP 请求）：
 *   用户问 → 大模型①返回工具意图 → 执行工具 → 结果直接返回用户（跳过大模型②）
 *
 * 用法：@Tool(description = "...", returnDirect = true)
 * 适合：生成文件、数据库写操作等结果已经明确、无需大模型再解释的场景。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【进阶知识四】ToolCallingManager —— 工具执行的幕后调度员
 * ═══════════════════════════════════════════════════════════════
 *
 * 你只写了 .tools(allTools).call()，背后 DefaultToolCallingManager 自动完成：
 *   大模型返回 toolCalls → 解析工具名+参数 → 找到对应 ToolCallback → 执行 → 结果拼回对话 → 发第二次请求
 *
 * 两种执行模式：
 *   ① 框架控制（默认）：全自动，.call() 一行搞定，适合绝大多数场景。
 *   ② 用户控制：设 internalToolExecutionEnabled(false)，自己写 while 循环，
 *      可在工具执行前后插入进度显示、日志记录、重试逻辑等自定义代码。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【进阶知识五】异常处理 & 可观测性
 * ═══════════════════════════════════════════════════════════════
 *
 * 异常处理两种策略（通过 ToolExecutionExceptionProcessor 配置）：
 *   ① 默认：错误信息返回给大模型，大模型自行降级处理 → 用户体验好
 *   ② alwaysThrow=true：直接抛异常中断 → 适合支付等不能绕过的关键操作
 *
 * 开启 DEBUG 日志可看清完整调用链路（学习必开，生产必关）：
 *   logging.level.org.springframework.ai: DEBUG
 */
public class PDFGenerationTool {

    private static final String PDF_DIR = FileConstant.FILE_SAVE_DIR + "/pdf";

    // 字体路径：优先用系统宋体，macOS 兼容路径
    private static final String[] FONT_PATHS = {
            "/System/Library/Fonts/STHeiti Light.ttc",
            "/System/Library/Fonts/Supplemental/Songti.ttc",
            "/Library/Fonts/Arial Unicode MS.ttf"
    };

    @Tool(description = "Generate a PDF file with the given content. Use this when the user wants to export content as a PDF document.")
    public String generatePDF(
            @ToolParam(description = "The file name for the PDF, e.g. plan.pdf") String fileName,
            @ToolParam(description = "The text content to include in the PDF") String content) {
        String filePath = PDF_DIR + "/" + fileName;
        new File(PDF_DIR).mkdirs();

        try (PDDocument doc = new PDDocument()) {
            PDType0Font font = loadChineseFont(doc);
            if (font == null) {
                return "Error: 未找到可用的中文字体，无法生成 PDF";
            }

            float fontSize = 12;
            float margin = 50;
            float leading = fontSize * 1.5f;
            PDRectangle pageSize = PDRectangle.A4;
            float maxWidth = pageSize.getWidth() - 2 * margin;

            // 按行切割内容，每页自动换页
            String[] lines = splitLines(content, font, fontSize, maxWidth);

            PDPage page = new PDPage(pageSize);
            doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            cs.beginText();
            cs.setFont(font, fontSize);
            cs.newLineAtOffset(margin, pageSize.getHeight() - margin);
            cs.setLeading(leading);

            float y = pageSize.getHeight() - margin;
            for (String line : lines) {
                if (y - leading < margin) {
                    // 当前页写满，新开一页
                    cs.endText();
                    cs.close();
                    page = new PDPage(pageSize);
                    doc.addPage(page);
                    cs = new PDPageContentStream(doc, page);
                    cs.beginText();
                    cs.setFont(font, fontSize);
                    cs.newLineAtOffset(margin, pageSize.getHeight() - margin);
                    cs.setLeading(leading);
                    y = pageSize.getHeight() - margin;
                }
                cs.showText(line);
                cs.newLine();
                y -= leading;
            }
            cs.endText();
            cs.close();

            doc.save(filePath);
            return "PDF generated successfully to: " + filePath;
        } catch (IOException e) {
            return "Error generating PDF: " + e.getMessage();
        }
    }

    private PDType0Font loadChineseFont(PDDocument doc) {
        // TTC 文件需要用 TrueTypeCollection 提取第一个字体
        for (String path : FONT_PATHS) {
            File f = new File(path);
            if (!f.exists()) continue;
            try {
                if (path.endsWith(".ttc")) {
                    try (TrueTypeCollection ttc = new TrueTypeCollection(f)) {
                        TrueTypeFont[] holder = new TrueTypeFont[1];
                        ttc.processAllFonts(font -> {
                            if (holder[0] == null) holder[0] = font;
                        });
                        if (holder[0] != null) {
                            return PDType0Font.load(doc, holder[0], true);
                        }
                    }
                } else {
                    return PDType0Font.load(doc, f);
                }
            } catch (IOException ignored) {}
        }
        // 再尝试 classpath 内嵌字体
        try (InputStream is = getClass().getResourceAsStream("/fonts/NotoSansSC-Regular.ttf")) {
            if (is != null) {
                return PDType0Font.load(doc, is);
            }
        } catch (IOException ignored) {}
        return null;
    }

    /** 将长文本按最大宽度切成多行 */
    private String[] splitLines(String content, PDType0Font font, float fontSize, float maxWidth) throws IOException {
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String paragraph : content.split("\n")) {
            if (paragraph.isEmpty()) {
                result.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < paragraph.length(); i++) {
                line.append(paragraph.charAt(i));
                float w = font.getStringWidth(line.toString()) / 1000 * fontSize;
                if (w > maxWidth) {
                    // 超宽，退一个字符换行
                    result.add(line.substring(0, line.length() - 1));
                    line = new StringBuilder(String.valueOf(paragraph.charAt(i)));
                }
            }
            if (line.length() > 0) result.add(line.toString());
        }
        return result.toArray(new String[0]);
    }
}
