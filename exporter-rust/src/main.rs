use axum::{
    Json, Router,
    body::Body,
    extract::{Query, State},
    http::{HeaderMap, HeaderValue, StatusCode, header},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use elasticsearch::{
    Elasticsearch, SearchParts,
    auth::Credentials,
    http::{
        Url,
        transport::{SingleNodeConnectionPool, TransportBuilder},
    },
};
use rust_xlsxwriter::{Color, Format, FormatAlign, FormatBorder, Workbook};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{env, sync::Arc};
use tokio::net::TcpListener;

const BATCH_SIZE: usize = 5_000;
const MAX_EXPORT_LIMIT: usize = 100_000;

/// onDownload 요청 파라미터 구조체
#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct ExportRequest {
    ip: Option<String>,
    start_time: String,
    end_time: String,
    #[serde(default = "default_limit")]
    page_size: usize,
}

fn default_limit() -> usize {
    10_000
}

#[derive(Debug, Deserialize, Serialize)]
struct PacketLogSource {
    #[serde(default)]
    time: String,
    #[serde(default)]
    ip: String,
    #[serde(default)]
    client: String,
    #[serde(default)]
    app: String,
    #[serde(default)]
    iat_max: f64,
    #[serde(default)]
    idle_time: f64,
    #[serde(default)]
    bps: u64,
    #[serde(default)]
    cps: u64,
    #[serde(default)]
    sync_wait: u64,
    #[serde(default)]
    sync_flow_count: u64,
}

#[derive(Debug, Deserialize)]
struct EsHit {
    _source: PacketLogSource,
    sort: Option<Vec<Value>>,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            env::var("RUST_LOG").unwrap_or_else(|_| "es_log_exporter=info,axum=info".into()),
        )
        .init();

    let es_url = env::var("ES_URL").unwrap_or_else(|_| "http://127.0.0.0:9200".into());
    let es_user = env::var("ES_USER").unwrap_or_else(|_| "test".into());
    let es_pass = env::var("ES_PASS").unwrap_or_else(|_| "testPw".into());

    let url = Url::parse(&es_url)?;
    let conn_pool = SingleNodeConnectionPool::new(url);
    let transport = TransportBuilder::new(conn_pool)
        .auth(Credentials::Basic(es_user, es_pass))
        .build()?;

    let client = Arc::new(Elasticsearch::new(transport));

    let app = Router::new()
        .route("/health", get(health))
        // onDownload 연동용 엑셀(.xlsx) 엔드포인트
        .route("/v2/analysis/download/excel", post(download_excel))
        // 대용량용 CSV 다운로드 엔드포인트
        .route("/v2/analysis/download/csv", get(download_csv_stream))
        .with_state(client);

    let bind_addr = env::var("BIND_ADDR").unwrap_or_else(|_| "0.0.0.0:3000".into());
    let listener = TcpListener::bind(&bind_addr).await?;
    tracing::info!("Rust Exporter Server listening on {}", bind_addr);
    axum::serve(listener, app).await?;

    Ok(())
}

async fn health() -> &'static str {
    "OK"
}

async fn download_excel(
    State(client): State<Arc<Elasticsearch>>,
    Json(req): Json<ExportRequest>,
) -> Result<Response, (StatusCode, String)> {
    let target_limit = req.page_size.min(MAX_EXPORT_LIMIT);
    let mut all_rows: Vec<PacketLogSource> = Vec::with_capacity(target_limit.min(BATCH_SIZE * 2));
    let mut search_after: Option<Vec<Value>> = None;

    // [개선 3] search_after 커서 루프를 통해 10,000건 한계 없이 안전하게 배치 조회
    while all_rows.len() < target_limit {
        let remaining = target_limit - all_rows.len();
        let fetch_size = remaining.min(BATCH_SIZE);

        let hits = fetch_es_batch(&client, &req, fetch_size, search_after.as_ref())
            .await
            .map_err(|e| {
                tracing::error!("ES batch query failed: {:?}", e);
                (StatusCode::INTERNAL_SERVER_ERROR, "ES Query Failed".into())
            })?;

        if hits.is_empty() {
            break;
        }

        search_after = hits.last().and_then(|h| h.sort.clone());
        let fetched_count = hits.len();

        for hit in hits {
            all_rows.push(hit._source);
        }

        if fetched_count < fetch_size || search_after.is_none() {
            break;
        }
    }

    tracing::info!(
        "Fetched {} rows from ES. Starting XLSX generation...",
        all_rows.len()
    );

    let excel_buffer = tokio::task::spawn_blocking(move || build_excel_bytes(&all_rows))
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let mut headers = HeaderMap::new();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        ),
    );
    headers.insert(
        header::CONTENT_DISPOSITION,
        HeaderValue::from_static("attachment; filename=\"network_analysis_export.xlsx\""),
    );

    Ok((headers, excel_buffer).into_response())
}

async fn download_csv_stream(
    State(client): State<Arc<Elasticsearch>>,
    Query(req): Query<ExportRequest>,
) -> impl IntoResponse {
    let target_limit = req.page_size.min(MAX_EXPORT_LIMIT);

    let stream = async_stream::stream! {
        // UTF-8 BOM 및 CSV 헤더 전송 (엑셀에서 한글 깨짐 방지)
        yield Ok::<_, std::io::Error>(
            "\u{FEFF}Time,Device IP,Client IP,Application,IAT Max(s),Idle Time(s),BPS,CPS,Sync Wait\n".to_string()
        );

        let mut search_after: Option<Vec<Value>> = None;
        let mut total_sent = 0usize;

        while total_sent < target_limit {
            let fetch_size = (target_limit - total_sent).min(BATCH_SIZE);
            match fetch_es_batch(&client, &req, fetch_size, search_after.as_ref()).await {
                Ok(hits) if !hits.is_empty() => {
                    search_after = hits.last().and_then(|h| h.sort.clone());
                    let count = hits.len();
                    let mut chunk = String::with_capacity(count * 128);

                    for hit in hits {
                        let r = hit._source;
                        let line = format!(
                            "{},{},{},{},{},{},{},{},{}\n",
                            format_timestamp(&r.time),
                            r.ip,
                            r.client,
                            r.app,
                            format_scaled_float(r.iat_max, 1_000_000.0),
                            format_scaled_float(r.idle_time, 1_000.0),
                            format_large_number(r.bps),
                            format_large_number(r.cps),
                            format_wait_ratio(r.sync_wait, r.sync_flow_count)
                        );
                        chunk.push_str(&line);
                    }

                    total_sent += count;
                    yield Ok(chunk);

                    if count < fetch_size || search_after.is_none() {
                        break;
                    }
                }
                _ => break,
            }
        }
    };

    let mut headers = HeaderMap::new();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("text/csv; charset=utf-8"),
    );
    headers.insert(
        header::CONTENT_DISPOSITION,
        HeaderValue::from_static("attachment; filename=\"network_analysis_stream.csv\""),
    );

    (headers, Body::from_stream(stream))
}

async fn fetch_es_batch(
    client: &Elasticsearch,
    req: &ExportRequest,
    size: usize,
    search_after: Option<&Vec<Value>>,
) -> anyhow::Result<Vec<EsHit>> {
    let mut filters = vec![json!({
        "range": {
            "time": {
                "gte": req.start_time,
                "lte": req.end_time
            }
        }
    })];

    if let Some(ip) = &req.ip
        && !ip.is_empty()
    {
        filters.push(json!({ "term": { "ip": ip } }));
    }

    let mut body = json!({
        "size": size,
        "track_total_hits": false,        
        "query": {
            "bool": { "filter": filters }
        }
    });

    if let Some(after) = search_after {
        body["search_after"] = json!(after);
    }

    let response = client
        .search(SearchParts::Index(&["index-2026-11-06"]))
        .body(body)
        .send()
        .await?;

    let json_body: Value = response.json().await?;
    let hits_val = json_body["hits"]["hits"].clone();
    let hits: Vec<EsHit> = serde_json::from_value(hits_val).unwrap_or_default();

    Ok(hits)
}

fn build_excel_bytes(rows: &[PacketLogSource]) -> anyhow::Result<Vec<u8>> {
    let mut workbook = Workbook::new();
    let worksheet = workbook.add_worksheet();
    worksheet.set_name("cctv_analysis")?;

    let header_format = Format::new()
        .set_bold()
        .set_font_size(13)
        .set_align(FormatAlign::Center)
        .set_background_color(Color::RGB(0xD9D9D9))
        .set_border(FormatBorder::Thin);

    let left_white = base_body_format(false, FormatAlign::Left);
    let left_gray = base_body_format(true, FormatAlign::Left);
    let center_white = base_body_format(false, FormatAlign::Center);
    let center_gray = base_body_format(true, FormatAlign::Center);
    let right_white = base_body_format(false, FormatAlign::Right);
    let right_gray = base_body_format(true, FormatAlign::Right);

    let headers = [
        ("Time", 22.0),
        ("Device IP", 16.0),
        ("Client IP", 16.0),
        ("App", 14.0),
        ("IAT Max (s)", 14.0),
        ("Idle Time (s)", 14.0),
        ("BPS", 12.0),
        ("CPS", 12.0),
        ("Sync Wait", 16.0),
    ];

    for (col_idx, (title, width)) in headers.iter().enumerate() {
        let col = col_idx as u16;
        worksheet.set_column_width(col, *width)?;
        worksheet.write_string_with_format(0, col, *title, &header_format)?;
    }

    for (i, r) in rows.iter().enumerate() {
        let row_num = (i + 1) as u32;
        let is_even = row_num % 2 == 0;

        let f_left = if is_even { &left_gray } else { &left_white };
        let f_center = if is_even { &center_gray } else { &center_white };
        let f_right = if is_even { &right_gray } else { &right_white };

        worksheet.write_string_with_format(row_num, 0, &format_timestamp(&r.time), f_center)?;
        worksheet.write_string_with_format(row_num, 1, &r.ip, f_left)?;
        worksheet.write_string_with_format(row_num, 2, &r.client, f_left)?;
        worksheet.write_string_with_format(row_num, 3, &r.app, f_left)?;
        worksheet.write_string_with_format(
            row_num,
            4,
            &format_scaled_float(r.iat_max, 1_000_000.0),
            f_right,
        )?;
        worksheet.write_string_with_format(
            row_num,
            5,
            &format_scaled_float(r.idle_time, 1_000.0),
            f_right,
        )?;
        worksheet.write_string_with_format(row_num, 6, &format_large_number(r.bps), f_right)?;
        worksheet.write_string_with_format(row_num, 7, &format_large_number(r.cps), f_right)?;
        worksheet.write_string_with_format(
            row_num,
            8,
            &format_wait_ratio(r.sync_wait, r.sync_flow_count),
            f_center,
        )?;
    }

    let buf = workbook.save_to_buffer()?;
    Ok(buf)
}

fn base_body_format(is_gray: bool, align: FormatAlign) -> Format {
    let mut fmt = Format::new()
        .set_font_size(11)
        .set_align(align)
        .set_border(FormatBorder::Thin)
        .set_border_color(Color::RGB(0xE6E6E6));
    if is_gray {
        fmt = fmt.set_background_color(Color::RGB(0xF9F9F9));
    }
    fmt
}

fn format_timestamp(raw: &str) -> String {
    if raw.is_empty() || raw == "0" {
        return "0".to_string();
    }
    let s = raw.replace('Z', "").replace('T', " ");
    let s = s.split('+').next().unwrap_or(&s);
    s.split('.').next().unwrap_or(s).to_string()
}

fn format_scaled_float(val: f64, divisor: f64) -> String {
    if val == 0.0 {
        "0".to_string()
    } else {
        format!("{:.2}", val / divisor)
    }
}

fn format_large_number(num: u64) -> String {
    if num == 0 {
        "0".to_string()
    } else if num >= 1_000_000_000 {
        format!("{:.2}G", num as f64 / 1_000_000_000.0)
    } else if num >= 1_000_000 {
        format!("{:.2}M", num as f64 / 1_000_000.0)
    } else if num >= 1_000 {
        format!("{:.2}K", num as f64 / 1_000.0)
    } else {
        num.to_string()
    }
}

fn format_wait_ratio(dividend: u64, divisor: u64) -> String {
    if divisor == 0 {
        return dividend.to_string();
    }
    let percent = if dividend == 0 {
        0
    } else {
        ((dividend as f64 / divisor as f64) * 100.0) as u64
    };
    format!("{} ({}%)", dividend, percent)
}