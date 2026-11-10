@Slf4j
@Configuration
public class EsAsyncConfig {
  @Bean(name = "esQueryExecutor")
  public Executor esQueryExecutor() {
    int cores = Math.max(2, Runtime.getRuntime().availableProcessors());

    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(cores);
    executor.setMaxPoolSize(cores);
    executor.setQueueCapacity(200);
    executor.setKeepAliveSeconds(60);
    executor.setThreadNamePrefix("es-parser-pool-");

    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(15);

    executor.setRejectedExecutionHandler((r, exec) -> {
      log.error("[esQueryExecutor] Thread pool queue exhausted (active={}, queue={}). Triggering Fail-Fast.",
                exec.getActiveCount(), exec.getQueue().size());
      throw new ThreadPoolExecutor.AbortPolicy().rejectedExecution(r, exec);
    });

    executor.initialize();
    return executor;
  }
}