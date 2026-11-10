public enum EsIndexType {
  SERVER("server", TIME_A, IP),
  PACKET("packet", TIME, PACKET_INDEX_IP);

  private final String prefix;
  private final String timeField;
  private final String ipField;

  EsIndexType(String prefix, String timeField, String ipField) {
    this.prefix = prefix;
    this.timeField = timeField;
    this.ipField = ipField;
  }

  public String prefix() {
    return prefix;
  }

  public String timeField() {
    return timeField;
  }

  public String ipField() {
    return ipField;
  }

  public boolean isPacket() {
    return this == PACKET;
  }

  public static EsIndexType of(String value) {
    if (value == null || value.isBlank()) {
      return SERVER;
    }
    String v = value.trim().toLowerCase();
    if (v.startsWith("packet")) {
      return PACKET;
    }
    return SERVER;
  }

  public static EsIndexType fromIndexName(String indexName) {
    return of(indexName);
  }

  public List<String> indices(boolean is24H) {
    List<String> base = objToStringList(targetRange(is24H).get("indices"));
    if (this == PACKET) {
      return base.stream().map(m -> m.replace("server", "packet")).collect(Collectors.toList());
    }
    return base;
  }

  public Map<String, Object> range(boolean is24H) {
    Map<String, Object> range = new java.util.HashMap<>(targetRange(is24H));
    range.put("indices", indices(is24H));
    return range;
  }

  public String indexName(LocalDate date) {
    return prefix + "-" + date.format(DateTimeFormatter.ofPattern(DATE_FORMAT));
  }

  public String indexName(LocalDateTime dateTime) {
    return indexName(dateTime.toLocalDate());
  }

  public String indexPatternYear(int year) {
    return prefix + "-" + year + "*";
  }
}
