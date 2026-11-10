@Slf4j
public final class FutureUtil {
  private static final int DEFAULT_TIMEOUT_SECONDS = 8;

  private FutureUtil() {}

  public static <T> CompletableFuture<Map.Entry<String, List<T>>> safeFuture(Supplier<CompletableFuture<Map.Entry<String, List<T>>>> supplier,
                                                                             String key) {
    return safeFuture(supplier, key, DEFAULT_TIMEOUT_SECONDS);
  }

  public static <T> CompletableFuture<Map.Entry<String, List<T>>> safeFuture(Supplier<CompletableFuture<Map.Entry<String, List<T>>>> supplier,
                                                                            String key, int timeout) {
    long startedAt = System.currentTimeMillis();
    try {
      return supplier.get()
          .orTimeout(timeout, TimeUnit.SECONDS)
          .handle((res, ex) -> {
            long elapsed = System.currentTimeMillis() - startedAt;
            if (ex != null) {
              handleException(key, elapsed, timeout, ex);
              return Map.entry(key, Collections.emptyList());
            }

            int size = (res == null || res.getValue() == null) ? -1 : res.getValue().size();
            if (size == 0) {
              log.warn("[{}] SUCCESS_EMPTY after {}ms - ES returned empty rows", key, elapsed);
            } else {
              log.info("[{}] SUCCESS after {}ms, rows={}", key, elapsed, size);
            }
            return res != null ? res : Map.entry(key, Collections.emptyList());
          });
    } catch (RejectedExecutionException rex) {
      // 스레드 풀 큐(200) 포화 시 서블릿 스레드를 블로킹하지 않고 즉시 빈 결과로 Fallback
      log.error("[{}] REJECTED by Bulkhead thread pool immediately - returning emptyList", key, rex);
      return CompletableFuture.completedFuture(Map.entry(key, Collections.emptyList()));
    } catch (Exception e) {
      log.error("[{}] UNEXPECTED synchronous failure - returning emptyList", key, e);
      return CompletableFuture.completedFuture(Map.entry(key, Collections.emptyList()));
    }
  }

  @SafeVarargs
  public static CompletableFuture<Map<String, Object>> allToMap(CompletableFuture<? extends Map.Entry<String, ? extends List<?>>>... futures) {
    return CompletableFuture.allOf(futures).thenApply(v -> {
      Map<String, Object> resultMap = new HashMap<>(futures.length);
      for (CompletableFuture<? extends Map.Entry<String, ? extends List<?>>> f : futures) {
        Map.Entry<String, ? extends List<?>> entry = f.join();
        resultMap.put(entry.getKey(), entry.getValue());
      }
      return resultMap;
    });
  }

  private static void handleException(String key, long elapsed, int timeout, Throwable ex) {
    Throwable root = unwrap(ex);
    if (root instanceof TimeoutException) {
      log.error("[{}] TIMEOUT after {}ms (limit={}s) - returning emptyList", key, elapsed, timeout);
    } else {
      log.error("[{}] FAILED after {}ms - returning emptyList", key, elapsed);
      logExceptionDeep(key, root);
    }
  }

  private static void logExceptionDeep(String key, Throwable t) {
    if (t instanceof ElasticsearchException esEx) {
      ErrorCause err = esEx.error();
      if (err != null) {
        log.error("[{}] ES error type={}, reason={}", key, err.type(), err.reason());
        if (err.rootCause() != null && !err.rootCause().isEmpty()) {
          err.rootCause().forEach(rc ->
              log.error("[{}] ES root_cause type={}, reason={}", key, rc.type(), rc.reason())
          );
        }
        return;
      }
    }
    log.error("[{}] root_cause class={}, message={}", key, t.getClass().getSimpleName(), t.getMessage(), t);
  }

  private static Throwable unwrap(Throwable ex) {
    Throwable t = ex;
    while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
      t = t.getCause();
    }
    return t;
  }

  public static <T> T getAs(Map<String, Object> resultMap, String key, TypeReference<T> typeRef) {
    Object value = resultMap.get(key);
    return JsonConvertUtil.convert(value, typeRef);
  }

  public static <T> T getAs(Map<String, Object> resultMap, String key, Class<T> clazz) {
    Object value = resultMap.get(key);
    return JsonConvertUtil.convert(value, clazz);
  }
}
