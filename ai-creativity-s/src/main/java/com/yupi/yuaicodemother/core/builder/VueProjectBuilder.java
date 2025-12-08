package com.yupi.yuaicodemother.core.builder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 构建 Vue 项目（Windows 专项优化版）
 * <p>
 * 优化点：
 * 1. Windows 下通过 cmd /c 执行 .cmd 命令，避免路径空格断裂
 * 2. 使用 ProcessBuilder 替代 RuntimeUtil，支持环境变量继承与重定向
 * 3. 进程树销毁（Windows 使用 taskkill /F /T）
 * 4. 实时捕获 stdout/stderr 输出，构建失败时可定位原因
 * 5. npm install 支持重试（网络抖动容错）
 * 6. 设置 UTF-8 编码，避免 Windows GBK 乱码
 * </p>
 */
@Slf4j
@Component
public class VueProjectBuilder {

    /** npm install 超时（秒） */
    private static final int NPM_INSTALL_TIMEOUT = 300;
    /** npm run build 超时（秒） */
    private static final int NPM_BUILD_TIMEOUT = 180;
    /** npm install 最大重试次数 */
    private static final int NPM_INSTALL_MAX_RETRIES = 2;
    /** npm install 重试间隔（毫秒） */
    private static final long NPM_INSTALL_RETRY_INTERVAL_MS = 3000;

    // ======================== 对外接口 ========================

    /**
     * 异步构建 Vue 项目（虚拟线程）
     */
    public void buildProjectAsync(String projectPath) {
        Thread.ofVirtual().name("vue-builder-" + System.currentTimeMillis())
                .start(() -> {
                    try {
                        buildProject(projectPath);
                    } catch (Exception e) {
                        log.error("异步构建 Vue 项目时发生异常: {}", e.getMessage(), e);
                    }
                });
    }

    /**
     * 构建 Vue 项目
     *
     * @param projectPath 项目根目录路径
     * @return 是否构建成功
     */
    public boolean buildProject(String projectPath) {
        File projectDir = new File(projectPath);

        // 1. 基础校验
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            log.error("项目目录不存在：{}", projectPath);
            return false;
        }
        File packageJsonFile = new File(projectDir, "package.json");
        if (!packageJsonFile.exists()) {
            log.error("项目目录中没有 package.json 文件：{}", projectPath);
            return false;
        }

        log.info("开始构建 Vue 项目：{}", projectPath);

        // 2. npm install（带重试）
        if (!executeNpmInstallWithRetry(projectDir)) {
            log.error("npm install 执行失败（已重试 {} 次）：{}", NPM_INSTALL_MAX_RETRIES, projectPath);
            return false;
        }

        // 3. npm run build
        if (!executeNpmBuild(projectDir)) {
            log.error("npm run build 执行失败：{}", projectPath);
            return false;
        }

        // 4. 验证 dist 目录
        File distDir = new File(projectDir, "dist");
        if (!distDir.exists() || !distDir.isDirectory()) {
            log.error("构建完成但 dist 目录未生成：{}", projectPath);
            return false;
        }

        log.info("Vue 项目构建成功，dist 目录：{}", distDir.getAbsolutePath());
        return true;
    }

    // ======================== npm 命令执行 ========================

    /**
     * 带重试的 npm install
     */
    private boolean executeNpmInstallWithRetry(File projectDir) {
        for (int attempt = 1; attempt <= NPM_INSTALL_MAX_RETRIES + 1; attempt++) {
            log.info("执行 npm install（第 {}/{} 次）...", attempt, NPM_INSTALL_MAX_RETRIES + 1);
            if (executeNpmInstall(projectDir)) {
                return true;
            }
            if (attempt <= NPM_INSTALL_MAX_RETRIES) {
                log.warn("npm install 失败，{} 秒后重试...", NPM_INSTALL_RETRY_INTERVAL_MS / 1000);
                try {
                    Thread.sleep(NPM_INSTALL_RETRY_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 执行 npm install
     */
    private boolean executeNpmInstall(File projectDir) {
        return executeNpmCommand(projectDir, "install", NPM_INSTALL_TIMEOUT);
    }

    /**
     * 执行 npm run build
     */
    private boolean executeNpmBuild(File projectDir) {
        return executeNpmCommand(projectDir, "run build", NPM_BUILD_TIMEOUT);
    }

    /**
     * 执行 npm 命令（跨平台）
     */
    private boolean executeNpmCommand(File projectDir, String npmArgs, int timeoutSeconds) {
        try {
            ProcessBuilder pb = createProcessBuilder(projectDir, npmArgs);
            log.info("在目录 [{}] 中执行: {}", projectDir.getAbsolutePath(), String.join(" ", pb.command()));

            Process process = pb.start();

            // 异步消费 stdout 和 stderr，防止缓冲区满导致进程阻塞
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            AtomicBoolean running = new AtomicBoolean(true);

            Thread stdoutReader = Thread.ofVirtual().name("npm-stdout").start(() ->
                    readStream(process.getInputStream(), stdout, running));
            Thread stderrReader = Thread.ofVirtual().name("npm-stderr").start(() ->
                    readStream(process.getErrorStream(), stderr, running));

            // 等待进程结束（带超时）
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                log.error("命令执行超时（{}秒），强制终止进程树", timeoutSeconds);
                destroyProcessTree(process);
                running.set(false);
                return false;
            }

            running.set(false);
            stdoutReader.join(5000);
            stderrReader.join(5000);

            int exitCode = process.exitValue();

            if (exitCode == 0) {
                log.info("npm {} 执行成功", npmArgs);
                logTail(stdout.toString(), "npm " + npmArgs + " stdout", 10);
                return true;
            } else {
                log.error("npm {} 执行失败，退出码: {}", npmArgs, exitCode);
                log.error(">>> stderr 输出:\n{}", truncate(stderr.toString(), 2000));
                log.error(">>> stdout 尾部:\n{}", truncate(stdout.toString(), 1000));
                return false;
            }

        } catch (IOException e) {
            log.error("启动 npm 进程失败: {}，请确认系统已安装 Node.js 并配置 PATH", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("等待 npm 进程时被中断");
            return false;
        }
    }

    // ======================== ProcessBuilder 构建 ========================

    /**
     * 创建跨平台的 ProcessBuilder
     * <p>
     * Windows: cmd /d /c npm install（解决 .cmd 无法直接 exec 的问题）
     * Linux/Mac: /bin/sh -c npm install
     * </p>
     */
    private ProcessBuilder createProcessBuilder(File workingDir, String npmArgs) {
        ProcessBuilder pb;

        if (isWindows()) {
            // ✅ 修复：/d 必须在 /c 之前
            // /d = 禁用 AutoRun 注册表项
            // /c = 执行后面的命令字符串并退出
            pb = new ProcessBuilder("cmd.exe", "/d", "/c", "npm " + npmArgs);
        } else {
            pb = new ProcessBuilder("/bin/sh", "-c", "npm " + npmArgs);
        }

        pb.directory(workingDir);

        // 继承当前环境变量，并补充关键变量
        var env = pb.environment();
        // 强制 npm 输出不使用颜色（避免 ANSI 转义码污染日志）
        env.put("NO_COLOR", "1");
        env.put("FORCE_COLOR", "0");
        // 设置 npm 日志级别，减少无关输出
        env.put("npm_config_loglevel", "warn");
        // Windows 专项：确保 APPDATA 存在（npm 缓存目录依赖）
        if (isWindows() && !env.containsKey("APPDATA")) {
            String userProfile = env.getOrDefault("USERPROFILE",
                    System.getProperty("user.home"));
            env.put("APPDATA", userProfile + "\\AppData\\Roaming");
        }

        pb.redirectErrorStream(false);

        return pb;
    }

    // ======================== 进程管理 ========================

    /**
     * 销毁进程树
     * <p>
     * Windows 上 process.destroyForcibly() 只杀父进程，
     * 子进程（node.exe）会残留。需使用 taskkill /F /T /PID 递归终止。
     * </p>
     */
    private void destroyProcessTree(Process process) {
        long pid = process.pid();
        if (isWindows()) {
            try {
                new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid))
                        .start()
                        .waitFor(10, TimeUnit.SECONDS);
                log.info("已通过 taskkill 终止进程树，PID: {}", pid);
            } catch (Exception e) {
                log.warn("taskkill 失败，回退到 destroyForcibly: {}", e.getMessage());
                process.destroyForcibly();
            }
        } else {
            try {
                new ProcessBuilder("kill", "-9", "-" + pid)
                        .start()
                        .waitFor(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                process.destroyForcibly();
            }
        }
    }

    // ======================== 流读取工具 ========================

    /**
     * 读取进程输出流（防止缓冲区满导致死锁）
     */
    private void readStream(InputStream inputStream, StringBuilder buffer, AtomicBoolean running) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                buffer.append(line).append("\n");
                if (line.contains("ERR!") || line.contains("WARN")) {
                    log.warn("[npm] {}", line);
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                log.debug("读取进程输出流异常: {}", e.getMessage());
            }
        }
    }

    // ======================== 日志工具 ========================

    /**
     * 打印日志尾部（避免构建日志过长）
     */
    private void logTail(String content, String label, int tailLines) {
        if (content == null || content.isBlank()) {
            return;
        }
        String[] lines = content.split("\n");
        int start = Math.max(0, lines.length - tailLines);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        log.info("{} (最后 {} 行):\n{}", label, lines.length - start, sb);
    }

    /**
     * 截断字符串
     */
    private String truncate(String content, int maxLength) {
        if (content == null) return "";
        return content.length() <= maxLength ? content : content.substring(0, maxLength) + "\n... (truncated)";
    }

    // ======================== 系统检测 ========================

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }
}