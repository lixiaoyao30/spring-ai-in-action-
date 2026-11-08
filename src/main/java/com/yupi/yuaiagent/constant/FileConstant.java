package com.yupi.yuaiagent.constant;

public class FileConstant {

    // 文件保存根目录：项目运行目录下的 /tmp 文件夹
    // System.getProperty("user.dir") = JVM 启动时的工作目录（即项目根目录）
    public static final String FILE_SAVE_DIR = System.getProperty("user.dir") + "/tmp";
}
