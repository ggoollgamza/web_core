@Slf4j
@RequiredArgsConstructor
public class EsAggHelper {
  private final ElasticsearchClient client;
  private final ElasticsearchAsyncClient asyncClient;
  private final Executor esQueryExecutor;
  
  /**
   * 특정 IP에 대해 품질 이상 또는 상태 이상을 step 으로 단순화하여 시간대별(1분)로 표시하기 위한 데이터(타임라인 슬라이더 차트용)
   *
   * @param entryKey: 반환 키
   * @param ip:       대상 IP
   * @param is24H:    인덱스 범위 (true -> 현재로부터 24시간 동안, false -> 당일의 0시부터 현재까지)
   * @param from:     iat_max gte
   * @param to:       iat_max lte
   * @return [{ time: "0000-00-00 00:00", step: 0~2 }]
   */
  public CompletableFuture<Map.Entry<String, List<Map<String, Object>>>> anomalyTrace(String entryKey, String ip,
                                                                                      boolean is24H,
                                                                                      int from, int to,
                                                                                      EsIndexType indexType) {
    final EsIndexType type = resolveType(indexType);
    Map<String, Object> targetRange = type.range(is24H);

    ZoneId zone = ZoneId.of("Asia/Seoul");
    DateTimeFormatter iso = DateTimeFormatter.ofPattern(is24H ? DATETIME_FORMAT : DATETIME_FORMAT4);
    ZonedDateTime lteZdt = ZonedDateTime.now(zone).withSecond(0).withNano(0);
    ZonedDateTime gteZdt = lteZdt.minusHours(24);

    String gte = is24H ? gteZdt.format(iso) : String.valueOf(targetRange.get("gte"));
    String lte = is24H ? lteZdt.minusMinutes(1).format(iso) : String.valueOf(targetRange.get("lte"));

    Query ipFilter = Query.of(q -> q.term(t -> t.field(type.ipField()).value(ip)));
    Query rangeQuery = Query.of(q -> q.range(r -> r
        .field(type.timeField())
        .gte(JsonData.of(gte))
        .lte(JsonData.of(lte))
        .format(is24H ? DATETIME_FORMAT : DATETIME_FORMAT3)
        .timeZone(TIME_ZONE)
    ));
    Query existsIatMax = Query.of(q -> q.exists(e -> e.field("iat_max")));

    DateHistogramAggregation dhAgg = DateHistogramAggregation.of(dh -> dh
        .field(type.timeField())
        .fixedInterval(Time.of(t -> t.time("1m")))
        .timeZone(TIME_ZONE)
        .format(is24H ? DATETIME_FORMAT : DATETIME_FORMAT4)
        .minDocCount(0)
        .hardBounds(hb -> hb
            .min(FieldDateMath.of(fdm -> fdm.expr(gte)))
            .max(FieldDateMath.of(fdm -> fdm.expr(lte)))
        )
    );

    Aggregation perMinuteAgg = Aggregation.of(a -> a
        .dateHistogram(dhAgg)
        .aggregations("step_2", a2 -> a2
            .filter(f -> f.range(r -> r.field("iat_max").gte(JsonData.of(to))))
        )
        .aggregations("step_1", a2 -> a2.filter(f -> f.bool(b -> b
            .filter(f2 -> f2.range(r -> r.field("iat_max").gte(JsonData.of(from))))
            .filter(f2 -> f2.range(r -> r.field("iat_max").lt(JsonData.of(to))))
        )))
    );

    List<String> indices = type.indices(is24H);
    SearchRequest request = SearchRequest.of(s -> s
        .index(indices)
        .size(0)
        .timeout("8s")
        .trackTotalHits(t -> t.enabled(false))
        .allowNoIndices(true)
        .ignoreUnavailable(true)
        .query(q -> q.bool(b -> b
            .filter(ipFilter)
            .filter(rangeQuery)
            .filter(existsIatMax)
        ))
        .aggregations("per_min", perMinuteAgg)
    );

    Function<SearchResponse<Void>, Map.Entry<String, List<Map<String, Object>>>> parser = res -> {
      List<Map<String, Object>> rows = new ArrayList<>(1440);

      Aggregate a = res.aggregations() == null ? null : res.aggregations().get("per_min");
      if (a == null || a.dateHistogram() == null) {
        return Map.entry(entryKey, rows);
      }

      for (DateHistogramBucket b : a.dateHistogram().buckets().array()) {
        long epochMillis = Long.parseLong(b.key());
        String display = Instant.ofEpochMilli(epochMillis)
            .atZone(KST)
            .format(DateTimeFormatter.ofPattern(DATETIME_FORMAT3));

        long cnt2 = 0L, cnt1 = 0L;
        Aggregate step2 = b.aggregations().get("step_2");
        if (step2 != null && step2.filter() != null) {
          cnt2 = step2.filter().docCount();
        }
        Aggregate step1 = b.aggregations().get("step_1");
        if (step1 != null && step1.filter() != null) {
          cnt1 = step1.filter().docCount();
        }

        int step = cnt2 > 0 ? 2 : (cnt1 > 0 ? 1 : 0);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", display);
        row.put("step", step);
        rows.add(row);
      }

      if (rows.size() > 1440) {
        rows = rows.subList(rows.size() - 1440, rows.size());
      }
      return Map.entry(entryKey, rows);
    };

    return asyncClient.search(request, Void.class).thenApplyAsync(parser, esQueryExecutor);
  }
}