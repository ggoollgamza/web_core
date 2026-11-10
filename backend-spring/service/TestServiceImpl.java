@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = {@Autowired})
public class TestServiceImpl implements TestService {
  public Map<String, Object> testTrace(Map<String, Object> params) {
    Map<String, Object> resultMap = new HashMap<>();

    String testIp = String.valueOf(params.get("testIp"));

    LocalDate today = LocalDate.now();
    LocalDate yesterday = LocalDate.now().minusDays(1);
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    String todayIndex = "packet-" + today.format(formatter);
    String yesterdayIndex = "packet-" + yesterday.format(formatter);

    Map<String, Object> thresholds = confDao.anomalyThreshold();
    final int qualityBound = Integer.parseInt(String.valueOf(thresholds.get("qualityIat")));
    final int statusBound = Integer.parseInt(String.valueOf(thresholds.get("statusIat")));
    final int networkBound = Integer.parseInt(String.valueOf(thresholds.get("networkIat")));

    int[] iatBoundArr = new int[]{qualityBound, statusBound, networkBound};

    CompletableFuture<Map.Entry<String, List<Map<String, Object>>>> f1 = FutureUtil.safeFuture(() -> ... );
    CompletableFuture<Map.Entry<String, List<Map<String, Object>>>> f2 = FutureUtil.safeFuture(() -> ... );
    CompletableFuture<Map.Entry<String, List<Map<String, Object>>>> f3 = FutureUtil.safeFuture(() -> ... );
    CompletableFuture<Map.Entry<String, List<Map<String, Object>>>> f4 = FutureUtil.safeFuture(() ->
        esAggHelper.anomalyTrace("anomalyTrace", testIp, true, qualityBound, statusBound, EsIndexType.PACKET), "anomalyTrace"
    );

    Map.Entry<String, List<Map<String, Object>>> e1 = f1.join();
    List<Map<String, Object>> groupIpList = testDao.testIpListWithSameGroup(params);

    Set<String> groupIpSet = groupIpList.stream()
        .map(m -> Objects.toString(m.get("testIp"), ""))
        .filter(s -> !s.isEmpty()).collect(Collectors.toSet());

    List<Map<String, Object>> filteredE1 = e1.getValue().stream()
        .filter(m -> {
          Object ipObj = m.get("testIp");
          if (ipObj == null) {
            return false;
          }
          String ip = ipObj.toString();
          return groupIpSet.contains(ip);
        })
        .collect(Collectors.toList());

    resultMap.put(e1.getKey(), filteredE1);
    resultMap.put("testInfo", ...);
    resultMap.put("useOd", ...);
    resultMap.put("thresholds", thresholds);
    
    resultMap.putAll(FutureUtil.allToMap(f2, f3, f4).join());

    return HttpUtil.getResult(HttpStatusCode.SUCCESS, resultMap);
  }
}