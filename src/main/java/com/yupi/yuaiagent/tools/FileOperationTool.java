package com.yupi.yuaiagent.tools;

import com.yupi.yuaiagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 文件操作工具类 —— Function Calling 工具的典型示例
 *
 * ══ 这个类在 Function Calling 里扮演什么角色？══
 *
 * Function Calling 的核心流程是：
 *   ① 程序把"工具清单"提前告诉大模型（工具名 + 描述 + 参数）
 *   ② 用户提问 → 大模型判断"我需要用哪个工具，传什么参数"
 *   ③ 大模型返回 JSON 意图（不是答案！）：{"tool":"readFile","params":{"fileName":"xxx"}}
 *   ④ Spring AI 框架拦截到意图 → 自动调用本类对应方法（@Tool 标注的方法）
 *   ⑤ 方法执行完返回结果 → 框架把结果塞回给大模型
 *   ⑥ 大模型基于真实结果生成最终回答 → 返回给用户
 *
 * ══ @Tool 注解的作用？══
 *   标记这个方法是"可供大模型调用的工具"
 *   description = 告诉大模型这个工具能干什么（大模型读 description 来决定要不要用它）
 *   这是关键！description 写得越清晰，大模型选择工具越准确
 *
 * ══ @ToolParam 注解的作用？══
 *   描述方法参数的含义，让大模型知道应该传什么值进来
 *   大模型从用户问题中提取对应的参数值
 *
 * ══ 大模型自己会执行这个方法吗？══
 *   不会！大模型只是"提出需求"，Spring AI 框架才是真正执行者
 *   这样设计是为了安全：所有操作都在你自己的程序里受控执行
 */
public class FileOperationTool {
    private final String FILE_DIR = FileConstant.FILE_SAVE_DIR + "/file";

    /**
     * 工具① 读取文件内容
     *
     * 触发场景举例：
     *   用户说"读一下我的恋爱档案" → 大模型选择此工具，fileName 传用户提到的文件名
     *
     * @param fileName 要读取的文件名（不含路径，只需文件名，如 "love_diary.txt"）
     * @return 文件内容字符串，读取失败时返回错误描述（大模型会基于此生成友好提示）
     */
    @Tool(description = "Read the content of a file. Use this when the user wants to view a saved file.")
    public String readFile(
            @ToolParam(description = "The name of the file to read, e.g. love_diary.txt") String fileName) {
        // 拼接完整路径：FILE_SAVE_DIR/fileName
        Path filePath = Paths.get(FILE_DIR, fileName);
        try {
            // Files.readString：Java 11+ 一行读整个文件，返回 UTF-8 字符串
            return Files.readString(filePath);
        } catch (IOException e) {
            // 返回错误描述而不是抛异常，让大模型能继续处理并告知用户
            return "读取文件失败：" + e.getMessage();
        }
    }

    /**
     * 工具② 写入文件内容
     *
     * 触发场景举例：
     *   用户说"帮我把恋爱档案保存为文件" → 大模型选择此工具
     *   用户说"生成七夕计划并保存"        → 大模型先生成内容，再调此工具保存
     *
     * @param fileName 要保存的文件名（如 "love_plan.txt"）
     * @param content  要写入的内容（大模型生成的文字或用户提供的内容）
     * @return 操作结果描述，大模型会把这个结果告知用户
     */
    @Tool(description = "Write content to a file. Use this when the user wants to save content to a file.")
    public String writeFile(
            @ToolParam(description = "The name of the file to save, e.g. love_plan.txt") String fileName,
            @ToolParam(description = "The content to write into the file") String content) {
        Path filePath = Paths.get(FILE_DIR, fileName);
        try {
            // 目录不存在则自动创建（第一次使用时 /tmp 可能还不存在）
            Files.createDirectories(filePath.getParent());
            // CREATE：不存在则创建；TRUNCATE_EXISTING：已存在则覆盖（而非追加）
            Files.writeString(filePath, content,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            return "文件已成功保存到：" + filePath.toAbsolutePath();
        } catch (IOException e) {
            return "写入文件失败：" + e.getMessage();
        }
    }
}
