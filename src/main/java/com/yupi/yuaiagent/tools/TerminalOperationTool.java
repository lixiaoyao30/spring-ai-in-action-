package com.yupi.yuaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * 终端操作工具 —— 执行系统命令并返回输出
 *
 * 触发场景：用户要求"执行脚本"、"运行命令"等
 * macOS/Linux 用 Runtime.exec()；Windows 需改为 ProcessBuilder("cmd.exe", "/c", command)
 */
public class TerminalOperationTool {

    @Tool(description = "Execute a command in the terminal and return the output. Use this when the user wants to run a script or system command.")
    public String executeTerminalCommand(
            @ToolParam(description = "The command to execute, e.g. python3 script.py or ls -l") String command) {
        StringBuilder output = new StringBuilder();
        try {
            Process process = Runtime.getRuntime().exec(command);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                output.append("Command exited with code: ").append(exitCode);
            }
        } catch (IOException | InterruptedException e) {
            return "Error executing command: " + e.getMessage();
        }
        return output.toString();
    }
}
