package com.yupi.yuaiagent.tools;

import cn.hutool.http.HttpUtil;
import com.yupi.yuaiagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.File;

/**
 * 资源下载工具 —— 从 URL 下载文件到本地
 *
 * 触发场景：用户要求"下载图片"、"保存文件到本地"等
 * 依赖 Hutool HttpUtil，自动处理重定向和二进制写入
 */
public class ResourceDownloadTool {

    private static final String DOWNLOAD_DIR = FileConstant.FILE_SAVE_DIR + "/download";

    @Tool(description = "Download a file from a URL and save it locally. Use this when the user wants to download an image, document, or any file from the internet.")
    public String downloadResource(
            @ToolParam(description = "The URL of the resource to download") String url,
            @ToolParam(description = "The file name to save as, e.g. logo.png") String fileName) {
        String filePath = DOWNLOAD_DIR + "/" + fileName;
        try {
            new File(DOWNLOAD_DIR).mkdirs();
            HttpUtil.downloadFile(url, new File(filePath));
            return "Downloaded successfully to: " + filePath;
        } catch (Exception e) {
            return "Error downloading resource: " + e.getMessage();
        }
    }
}
