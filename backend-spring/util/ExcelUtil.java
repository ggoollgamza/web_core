@Slf4j
public final class ExcelUtil {
  private static final String CONTENT_TYPE_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  private static final int MAX_DOWNLOAD_COUNT = 100_000;
  private static final int STREAMING_WINDOW_SIZE = 500;
  private static final String DEFAULT_FONT_NAME = "맑은 고딕";

  // [개선 1] 루프 내부에서 매번 컴파일되던 정규식을 사전 컴파일(Pre-compile)하여 CPU 부하 제거
  private static final Pattern CONTROL_CHAR_PATTERN = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]");
  private static final Pattern RIGHT_ALIGN_KEY_PATTERN = Pattern.compile(".*(rtt|srt|cps|tps|pps|bps|ups).*");
  private static final Pattern LARGE_NUM_KEY_PATTERN = Pattern.compile("(cps|max|aps|tran_cnt|user|bps|bpsmax).*");

  private static final Set<String> LEFT_ALIGN_KEYS = Set.of("app", "serverIp", "clientIp", "ip", "client");
  private static final Set<String> TIME_KEYS = Set.of("time", "startTime", "endTime", "@timestamp");

  // 메타데이터 배열 (기존 ExcelColumnMeta 연동 유지)
  private static final String[] CCTV_KEYS = ExcelColumnMeta.keys(ExcelColumnMeta.CCTV_COLUMNS);
  private static final String[] CCTV_TITLES = ExcelColumnMeta.titlesWithDummy(ExcelColumnMeta.CCTV_COLUMNS);
  private static final int[] CCTV_CELL = ExcelColumnMeta.widthsWithDummy(ExcelColumnMeta.CCTV_COLUMNS);

  private static final String[] NVR_KEYS = ExcelColumnMeta.keys(ExcelColumnMeta.NVR_COLUMNS);
  private static final String[] NVR_TITLES = ExcelColumnMeta.titlesWithDummy(ExcelColumnMeta.NVR_COLUMNS);
  private static final int[] NVR_CELL = ExcelColumnMeta.widthsWithDummy(ExcelColumnMeta.NVR_COLUMNS);

  private static final String[] CCTV_APP_KEYS = ExcelColumnMeta.keys(ExcelColumnMeta.CCTV_APP_COLUMNS);
  private static final String[] CCTV_APP_TITLES = ExcelColumnMeta.titlesWithDummy(ExcelColumnMeta.CCTV_APP_COLUMNS);
  private static final int[] CCTV_APP_CELL = ExcelColumnMeta.widthsWithDummy(ExcelColumnMeta.CCTV_APP_COLUMNS);

  // 유틸리티 클래스 인스턴스화 방지
  private ExcelUtil() {}

  // [개선 2] static 필드로 공유되던 CellStyle을 워크북 단위 불변 객체로 격리 (Thread-Safe 보장)
  private record StylePalette(CellStyle titleStyle, CellStyle leftWhite, CellStyle centerWhite, CellStyle rightWhite,
                              CellStyle leftGray, CellStyle centerGray, CellStyle rightGray) {
    CellStyle resolve(HorizontalAlignment align, boolean isEvenRow) {
      return switch (align) {
        case LEFT -> isEvenRow ? leftGray : leftWhite;
        case RIGHT -> isEvenRow ? rightGray : rightWhite;
        default -> isEvenRow ? centerGray : centerWhite;
      };
    }
  }

  // [개선 3] 20만 번의 셀 루프 안에서 수행하던 키 변환 및 정렬 판별을 루프 밖에서 컬럼 단위로 1회만 사전 계산
  private record ColumnContext(
      String rawKey,
      String lookupKey,
      HorizontalAlignment alignment,
      boolean isTimeField,
      boolean isIatOrIdle,
      boolean isIatPrefix,
      boolean isLargeNumber,
      boolean isRiskField,
      boolean isWaitField,
      String flowCountKey
  ) {}

  public static void writeExcel(Map<String, Object> dataMap, HttpServletResponse response) {
    final String sheetName = String.valueOf(dataMap.getOrDefault("sheetName", "cctv"));
    final String fileName = String.valueOf(dataMap.getOrDefault("fileName", "export"));
    final boolean keepSnake = "true".equalsIgnoreCase(String.valueOf(dataMap.getOrDefault("keepSnake", "false")));

    try (SXSSFWorkbook workbook = new SXSSFWorkbook(STREAMING_WINDOW_SIZE)) {
      workbook.setCompressTempFiles(true);

      StylePalette palette = createStylePalette(workbook);
      Sheet sheet = initSheet(workbook, sheetName);

      String[] titles = getDataArr(sheetName, true);
      String[] keys = getDataArr(sheetName, false);
      int[] cellSizes = resolveColumnWidths(sheetName);

      // 헤더 생성
      Row header = sheet.createRow(0);
      header.setHeight((short) 640);
      for (int i = 0; i < titles.length - 1; i++) {
        sheet.setColumnWidth(i, cellSizes[i]);
        Cell cell = header.createCell(i, CellType.STRING);
        cell.setCellValue(titles[i]);
        cell.setCellStyle(palette.titleStyle());
      }

      // 컬럼별 메타데이터 사전 계산 (20만 회 반복 연산 -> 컬럼 수만큼만 1회 연산)
      ColumnContext[] columnContexts = buildColumnContexts(keys, keepSnake);
      List<Map<String, Object>> dataList = JsonConvertUtil.convert(
          dataMap.getOrDefault("data", List.of()),
          new TypeReference<>() {}
      );
      int rowLimit = Math.min(dataList.size(), MAX_DOWNLOAD_COUNT);

      // 데이터 행 렌더링
      for (int i = 0; i < rowLimit; i++) {
        Row row = sheet.createRow(i + 1);
        row.setHeight((short) 520);

        boolean isEven = (i + 1) % 2 == 0;
        Map<String, Object> rowData = dataList.get(i);

        for (int j = 0; j < columnContexts.length; j++) {
          ColumnContext col = columnContexts[j];
          Object rawValue = rowData.get(col.lookupKey());
          String value = formatCellValue(col, rawValue, rowData);

          if (value != null && !value.isEmpty()) {
            value = CONTROL_CHAR_PATTERN.matcher(value).replaceAll("");
          }

          Cell cell = row.createCell(j, CellType.STRING);
          cell.setCellValue(value == null ? "" : value);
          cell.setCellStyle(palette.resolve(col.alignment(), isEven));
        }
      }

      // 응답 헤더 설정 및 스트림 출력
      String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
      response.setContentType(CONTENT_TYPE_XLSX);
      response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encodedFileName + ".xlsx");

      workbook.write(response.getOutputStream());
      response.getOutputStream().flush();

    } catch (IOException e) {
      log.error("[ExcelUtil] Failed to write excel stream for sheet={}", sheetName, e);
      throw new IllegalStateException("Excel generation failed", e);
    }
  }

  private static ColumnContext[] buildColumnContexts(String[] keys, boolean keepSnake) {
    ColumnContext[] contexts = new ColumnContext[keys.length];
    for (int i = 0; i < keys.length; i++) {
      String key = keys[i];
      String lowerKey = key.toLowerCase();
      String lookupKey = keepSnake ? key : toCamelCase(key);

      HorizontalAlignment alignment;
      if (LEFT_ALIGN_KEYS.contains(key)) {
        alignment = HorizontalAlignment.LEFT;
      } else if (RIGHT_ALIGN_KEY_PATTERN.matcher(lowerKey).matches()) {
        alignment = HorizontalAlignment.RIGHT;
      } else {
        alignment = HorizontalAlignment.CENTER;
      }

      boolean isWait = lowerKey.contains("syncwait") || lowerKey.contains("rspwait");
      contexts[i] = new ColumnContext(
          key,
          lookupKey,
          alignment,
          TIME_KEYS.contains(key),
          key.equals("idle_time") || key.startsWith("iat"),
          key.startsWith("iat"),
          LARGE_NUM_KEY_PATTERN.matcher(lowerKey).matches(),
          key.contains("RISK"),
          isWait,
          isWait ? key.replace("Wait", "FlowCount") : null
      );
    }
    return contexts;
  }

  private static String formatCellValue(ColumnContext col, Object rawValue, Map<String, Object> rowData) {
    String value = (rawValue == null) ? "0" : String.valueOf(rawValue);
    if ("null".equalsIgnoreCase(value)) {
      value = "0";
    }

    if (col.isTimeField() && !"0".equals(value)) {
      value = value.replace("Z", "").replace("T", " ");
      int plusIdx = value.indexOf('+');
      if (plusIdx > 0) value = value.substring(0, plusIdx);
      int dotIdx = value.indexOf('.');
      if (dotIdx > 0) value = value.substring(0, dotIdx);
    }

    if (col.isIatOrIdle()) {
      try {
        double doubleV = Double.parseDouble(value);
        value = (doubleV == 0) ? "0" : String.format("%.2f", doubleV / (col.isIatPrefix() ? 1_000_000d : 1_000d));
      } catch (NumberFormatException e) {
        log.warn("[ExcelUtil] Invalid number format for key={}, value={}", col.rawKey(), value);
      }
    }

    if (col.isLargeNumber()) {
      try {
        value = formatLargeNumber(Long.parseLong(value));
      } catch (NumberFormatException ignored) {}
    }

    if (col.isRiskField()) {
      value = value + "%";
    }

    if (col.isWaitField() && col.flowCountKey() != null) {
      try {
        long dividend = Long.parseLong(value);
        Object divisorObj = rowData.get(col.flowCountKey());
        if (divisorObj != null) {
          long divisor = Long.parseLong(String.valueOf(divisorObj));
          int percent = (dividend == 0 || divisor == 0) ? 0 : (int) ((float) dividend / divisor * 100);
          value = value + " (" + percent + "%)";
        }
      } catch (NumberFormatException ignored) {}
    }

    return value;
  }

  private static Sheet initSheet(SXSSFWorkbook workbook, String sheetName) {
    Sheet sheet = workbook.createSheet(sheetName);
    sheet.setFitToPage(true);
    sheet.setZoom(90);
    sheet.setAutobreaks(true);
    sheet.setMargin(PageMargin.LEFT, 0.5);
    sheet.setHorizontallyCenter(true);
    return sheet;
  }

  private static StylePalette createStylePalette(SXSSFWorkbook sxssfWorkbook) {
    XSSFWorkbook xssfWorkbook = sxssfWorkbook.getXSSFWorkbook();

    Font titleFont = xssfWorkbook.createFont();
    titleFont.setFontHeightInPoints((short) 13);
    titleFont.setFontName(DEFAULT_FONT_NAME);
    titleFont.setBold(true);

    CellStyle titleStyle = xssfWorkbook.createCellStyle();
    titleStyle.setFont(titleFont);
    titleStyle.setAlignment(HorizontalAlignment.CENTER);
    titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);
    titleStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
    titleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    titleStyle.setBorderTop(BorderStyle.THIN);
    titleStyle.setBorderBottom(BorderStyle.THIN);
    titleStyle.setBorderLeft(BorderStyle.THIN);
    titleStyle.setBorderRight(BorderStyle.THIN);

    // 본문 폰트 역시 스타일마다 매번 생성하지 않고 워크북 당 1개만 생성하여 공유
    Font bodyFont = xssfWorkbook.createFont();
    bodyFont.setFontHeightInPoints((short) 12);
    bodyFont.setFontName(DEFAULT_FONT_NAME);

    return new StylePalette(
        titleStyle,
        createBodyCellStyle(xssfWorkbook, bodyFont, false, HorizontalAlignment.LEFT),
        createBodyCellStyle(xssfWorkbook, bodyFont, false, HorizontalAlignment.CENTER),
        createBodyCellStyle(xssfWorkbook, bodyFont, false, HorizontalAlignment.RIGHT),
        createBodyCellStyle(xssfWorkbook, bodyFont, true, HorizontalAlignment.LEFT),
        createBodyCellStyle(xssfWorkbook, bodyFont, true, HorizontalAlignment.CENTER),
        createBodyCellStyle(xssfWorkbook, bodyFont, true, HorizontalAlignment.RIGHT)
    );
  }

  private static XSSFCellStyle createBodyCellStyle(XSSFWorkbook workbook, Font bodyFont,
                                                  boolean isGray, HorizontalAlignment alignment) {
    XSSFCellStyle style = workbook.createCellStyle();
    if (isGray) {
      style.setFillForegroundColor(getColor(249, 249, 249));
      style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    }
    XSSFColor borderColor = getColor(230, 230, 230);
    style.setVerticalAlignment(VerticalAlignment.CENTER);
    style.setAlignment(alignment);
    style.setBorderBottom(BorderStyle.THIN);
    style.setBorderLeft(BorderStyle.THIN);
    style.setBorderRight(BorderStyle.THIN);
    style.setBottomBorderColor(borderColor);
    style.setLeftBorderColor(borderColor);
    style.setRightBorderColor(borderColor);
    style.setFont(bodyFont);
    return style;
  }

  private static String toCamelCase(String key) {
    if (key == null || key.isEmpty() || key.startsWith("@")) {
      return key;
    }
    return CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, key);
  }

  private static String[] getDataArr(String type, boolean isTitle) {
    return switch (type) {
      case "nvr" -> isTitle ? NVR_TITLES : NVR_KEYS;
      case "cctv_app" -> isTitle ? CCTV_APP_TITLES : CCTV_APP_KEYS;
      default -> isTitle ? CCTV_TITLES : CCTV_KEYS;
    };
  }

  private static int[] resolveColumnWidths(String sheetName) {
    return switch (sheetName) {
      case "nvr" -> NVR_CELL;
      case "cctv_app" -> CCTV_APP_CELL;
      default -> CCTV_CELL;
    };
  }

  private static String formatLargeNumber(long number) {
    if (number == 0) {
      return "0";
    }
    if (number >= 1_000_000_000L) {
      return String.format("%.2fG", number / 1_000_000_000.0);
    }
    if (number >= 1_000_000L) {
      return String.format("%.2fM", number / 1_000_000.0);
    }
    if (number >= 1_000L) {
      return String.format("%.2fK", number / 1_000.0);
    }
    return String.valueOf(number);
  }

  private static XSSFColor getColor(int r, int g, int b) {
    return new XSSFColor(new byte[]{(byte) r, (byte) g, (byte) b}, null);
  }
}
