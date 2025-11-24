package com.yupi.yuaicodemother.core;

import cn.hutool.json.JSONUtil;
import com.yupi.yuaicodemother.ai.AiCodeGeneratorService;
import com.yupi.yuaicodemother.ai.AiCodeGeneratorServiceFactory;
import com.yupi.yuaicodemother.ai.model.HtmlCodeResult;
import com.yupi.yuaicodemother.ai.model.MultiFileCodeResult;
import com.yupi.yuaicodemother.ai.model.message.AiResponseMessage;
import com.yupi.yuaicodemother.ai.model.message.ToolExecutedMessage;
import com.yupi.yuaicodemother.core.parser.CodeParserExecutor;
import com.yupi.yuaicodemother.core.saver.CodeFileSaverExecutor;
import com.yupi.yuaicodemother.exception.BusinessException;
import com.yupi.yuaicodemother.exception.ErrorCode;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;

import java.io.File;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 代码生成门面类
 * <p>
 * 组合代码生成、流式传输、文件保存功能，并提供统一的限流与重试机制。
 * </p>
 *
 * @author yupi
 */
@Service
@Slf4j
public class AiCodeGeneratorFacade {

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    /**
     * 并发控制信号量：限制同时进行的流式请求数
     * <p>根据 API Burst Rate 限额调整，免费额度建议设为 1~2</p>
     */
    private static final Semaphore STREAM_SEMAPHORE = new Semaphore(1);

    /** 最大重试次数 */
    private static final int MAX_RETRIES = 3;

    /** 初始退避时间（毫秒） */
    private static final long INITIAL_BACKOFF_MS = 5000L;

    /** 最大退避时间（毫秒） */
    private static final long MAX_BACKOFF_MS = 60000L;

    /**
     * 统一入口：根据类型生成并保存代码（同步）
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型枚举
     * @param appId           应用 ID
     * @return 保存的代码目录
     * @throws BusinessException 参数为空或类型不支持时抛出
     */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成类型不能为空");
        }

        AiCodeGeneratorService service = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);

        return switch (codeGenTypeEnum) {
            case HTML -> {
                HtmlCodeResult result = service.generateHtmlCode(userMessage);
                yield CodeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                MultiFileCodeResult result = service.generateMultiFileCode(userMessage);
                yield CodeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "不支持的生成类型：" + codeGenTypeEnum.getValue());
        };
    }

    /**
     * 统一入口：根据类型生成并保存代码（流式 SSE）
     * <p>
     * 内部自动处理限流、指数退避重试及文件保存。
     * 返回的 Flux 已包含错误兜底，不会向客户端抛出非 SSE 格式异常。
     * </p>
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型枚举
     * @param appId           应用 ID
     * @return SSE 格式的 JSON 字符串流
     * @throws BusinessException 参数为空或类型不支持时抛出
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成类型不能为空");
        }

        AiCodeGeneratorService service = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);

        Flux<String> stream = switch (codeGenTypeEnum) {
            case HTML -> {
                // ✅ 使用 defer 延迟创建，确保 retryWhen 重订阅时能获取全新流
                Flux<String> deferredStream = Flux.defer(() -> service.generateHtmlCodeStream(userMessage));
                yield processCodeStream(deferredStream, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                Flux<String> deferredStream = Flux.defer(() -> service.generateMultiFileCodeStream(userMessage));
                yield processCodeStream(deferredStream, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            case VUE_PROJECT -> processVueProjectStreamWithRetry(service, appId, userMessage);
            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "不支持的生成类型：" + codeGenTypeEnum.getValue());
        };

        // ✅ 全局 SSE 错误兜底，防止非 SSE 异常导致 HttpMediaTypeNotAcceptableException
        return stream.onErrorResume(e -> {
            log.error("流式生成最终异常（已耗尽重试）: {}", e.getMessage(), e);
            String errorMsg = JSONUtil.toJsonStr(new AiResponseMessage("代码生成失败，请稍后重试"));
            return Flux.just(errorMsg);
        });
    }

    // ======================== Vue 项目流式生成（带信号量 + 重试） ========================

    /**
     * 带并发控制 + 指数退避重试的 Vue 项目流式生成
     *
     * @param service     AI 代码生成服务
     * @param appId       应用 ID
     * @param userMessage 用户提示词
     * @return 可重试的 SSE 流
     */
    private Flux<String> processVueProjectStreamWithRetry(AiCodeGeneratorService service, Long appId, String userMessage) {
        return Flux.<String>defer(() -> {
                    try {
                        if (!STREAM_SEMAPHORE.tryAcquire(60, TimeUnit.SECONDS)) {
                            return Flux.<String>error(new BusinessException(ErrorCode.SYSTEM_ERROR, "系统繁忙，请稍后重试"));
                        }
                        log.info("获取流式请求许可成功，当前可用许可数：{}", STREAM_SEMAPHORE.availablePermits());

                        TokenStream tokenStream = service.generateVueProjectCodeStream(appId, userMessage);
                        return processTokenStream(tokenStream)
                                .doFinally(signal -> {
                                    STREAM_SEMAPHORE.release();
                                    log.info("释放流式请求许可，信号类型：{}", signal);
                                });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return Flux.<String>error(new BusinessException(ErrorCode.SYSTEM_ERROR, "请求被中断"));
                    }
                })
                .retryWhen(buildBurstRateRetry());
    }

    // ======================== TokenStream → Flux 转换 ========================

    /**
     * 将 LangChain4j TokenStream 转换为 Reactor Flux
     * <p>
     * 使用 multicast sink 支持重试场景下的安全重订阅。
     * 注意：此方法每次调用都会创建新的 sink 和绑定新的 tokenStream，
     * 因此必须配合 Flux.defer 使用以保证重试时获取全新实例。
     * </p>
     *
     * @param tokenStream LangChain4j 令牌流（一次性资源）
     * @return SSE 格式的 JSON 字符串流
     */
    private Flux<String> processTokenStream(TokenStream tokenStream) {
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
        AtomicInteger chunkCount = new AtomicInteger(0);

        tokenStream
                .onPartialResponse(partialResponse -> {
                    chunkCount.incrementAndGet();
                    AiResponseMessage msg = new AiResponseMessage(partialResponse);
                    sink.tryEmitNext(JSONUtil.toJsonStr(msg));
                })
                .onToolExecuted(toolExecution -> {
                    ToolExecutedMessage msg = new ToolExecutedMessage(toolExecution);
                    sink.tryEmitNext(JSONUtil.toJsonStr(msg));
                })
                .onCompleteResponse(response -> {
                    log.info("流式生成完成，共收到 {} 个片段", chunkCount.get());
                    sink.tryEmitComplete();
                })
                .onError(error -> {
                    log.error("TokenStream 流式响应异常（已收到 {} 个片段）: {}",
                            chunkCount.get(), error.getMessage());
                    sink.tryEmitError(error);
                })
                .start();

        return sink.asFlux();
    }

    // ======================== HTML / MULTI_FILE 流式处理 ========================

    /**
     * 通用流式代码处理方法（带文件保存 + Burst Rate 重试）
     * <p>
     * 要求传入的 codeStream 必须是可重订阅的（如通过 Flux.defer 创建），
     * 否则 retryWhen 会因 Unicast Sink 限制而失败。
     * </p>
     *
     * @param codeStream   可重订阅的代码流
     * @param codeGenType  代码生成类型
     * @param appId        应用 ID
     * @return 带重试和保存逻辑的 SSE 流
     */
    private Flux<String> processCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenType, Long appId) {
        StringBuilder codeBuilder = new StringBuilder();

        return codeStream
                .doOnNext(chunk -> codeBuilder.append(chunk))
                .doOnComplete(() -> {
                    try {
                        String completeCode = codeBuilder.toString();
                        Object parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenType);
                        File saveDir = CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, appId);
                        log.info("代码保存成功，目录：{}", saveDir.getAbsolutePath());
                    } catch (Exception e) {
                        log.error("代码保存失败: {}", e.getMessage(), e);
                    }
                })
                .retryWhen(Retry.backoff(MAX_RETRIES, Duration.ofMillis(INITIAL_BACKOFF_MS))
                        .maxBackoff(Duration.ofMillis(MAX_BACKOFF_MS))
                        .multiplier(2.0)
                        .filter(this::isBurstRateLimitError)
                        .doBeforeRetry(signal -> {
                            // ✅ 重试前清空已收集内容，避免重复拼接
                            codeBuilder.setLength(0);
                            log.warn("Flux 流触发 Burst Rate 限流，第 {} 次重试...", signal.totalRetries() + 1);
                        })
                );
    }

    // ======================== 公共工具方法 ========================

    /**
     * 构建 Burst Rate 限流专用的指数退避重试策略
     *
     * @return Retry 配置对象
     */
    private Retry buildBurstRateRetry() {
        return Retry.backoff(MAX_RETRIES, Duration.ofMillis(INITIAL_BACKOFF_MS))
                .maxBackoff(Duration.ofMillis(MAX_BACKOFF_MS))
                .multiplier(2.0)
                .filter(this::isBurstRateLimitError)
                .doBeforeRetry(signal -> log.warn(
                        "触发 Burst Rate 限流，第 {} 次重试，等待 {} 秒后重试...",
                        signal.totalRetries() + 1,
                        signal.totalRetries() < MAX_RETRIES
                                ? (INITIAL_BACKOFF_MS * (long) Math.pow(2, signal.totalRetries())) / 1000
                                : MAX_BACKOFF_MS / 1000
                ));
    }

    /**
     * 判断异常是否为 Burst Rate 限流错误
     * <p>递归检查异常链中的 RateLimitException</p>
     *
     * @param throwable 待检查的异常
     * @return true 表示是 burst rate 限流，应触发重试
     */
    private boolean isBurstRateLimitError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof RateLimitException) {
                String message = current.getMessage();
                return message != null && message.contains("limit_burst_rate");
            }
            current = current.getCause();
        }
        return false;
    }
}