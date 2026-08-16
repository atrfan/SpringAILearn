package com.foxmimi.springaichat.tool.client;

import com.foxmimi.springaichat.tool.dto.CmaAutocompleteResponse;
import com.foxmimi.springaichat.tool.dto.CmaDaily;
import com.foxmimi.springaichat.tool.dto.CmaLocation;
import com.foxmimi.springaichat.tool.dto.CmaNow;
import com.foxmimi.springaichat.tool.dto.CmaResponse;
import com.foxmimi.springaichat.tool.dto.CmaWeatherData;
import com.foxmimi.springaichat.tool.dto.WeatherDto;
import com.foxmimi.springaichat.tool.exception.ToolExecutionException;
import com.foxmimi.springaichat.tool.exception.ToolExecutionException.FailureCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

/**
 * 调真实中国气象局 CMA 接口的下游客户端
 * <p>
 * 两步走封装（模型只看到一个 {@code getWeather(city)}）：
 * <ol>
 *   <li>{@code /api/autocomplete?q={city}&limit=1} → 解析 {@code data[0].split("|")[0]}
 *       得 {@code stationId}；</li>
 *   <li>{@code /api/weather/view?stationid={id}} → 拿 location + now + daily + alarm，
 *       映射为 {@link WeatherDto}。</li>
 * </ol>
 * <p>
 * <b>双层成功校验</b>：先查 {@code Content-Type} 含 {@code application/json}
 * （非 JSON 抛 {@link FailureCause#NOT_JSON}，多为 WAF 跳转人机验证页），再查
 * {@code code === 0}（非 0 抛 {@link FailureCause#BUSINESS_ERROR}）。
 * </p>
 * <p>
 * <b>异常分类</b>：超时 → {@link FailureCause#TIMEOUT}；403 → {@link FailureCause#BLOCKED}；
 * 429 → {@link FailureCause#RATE_LIMITED}；其余 4xx → {@link FailureCause#CLIENT_4XX}；
 * 5xx → {@link FailureCause#SERVER_5XX}，统一包成 {@link ToolExecutionException} 供
 * {@link com.foxmimi.springaichat.handler.GlobalExceptionHandler} 映射。
 * </p>
 */
@Component
public class WeatherClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(WeatherClient.class);
    private static final String INTERNAL_SOURCE = "CMA";

    private final RestClient weatherRestClient;

    public WeatherClient(RestClient weatherRestClient) {
        this.weatherRestClient = weatherRestClient;
    }

    /**
     * 按城市名搜索 CMA 气象站编号
     *
     * @param city 城市名关键词
     * @return 第一个匹配站点的 stationId
     * @throws ToolExecutionException 无结果 / 业务错误时
     */
    public String searchStationId(String city) {
        CmaAutocompleteResponse resp = getJson("/api/autocomplete?q={q}&limit=1",
                Map.of("q", city), CmaAutocompleteResponse.class);
        List<String> data = resp.data();
        if (data == null || data.isEmpty()) {
            throw new ToolExecutionException(FailureCause.BUSINESS_ERROR,
                    "未找到城市对应的气象站: " + city);
        }
        String first = data.get(0);
        String[] parts = first.split("\\|");
        if (parts.length == 0 || parts[0].isBlank()) {
            throw new ToolExecutionException(FailureCause.BUSINESS_ERROR,
                    "城市搜索结果格式异常: " + first);
        }
        return parts[0];
    }

    /**
     * 拉取指定城市的实况 + 预报（两步走）
     *
     * @param city 城市名
     * @return 含 provider 标记与 location 元数据的 {@link WeatherDto}
     */
    public WeatherDto fetchWeather(String city) {
        String stationId = searchStationId(city);
        LOGGER.debug("CMA 城市搜索 {} -> stationId={}", city, stationId);
        CmaResponse resp = getJson("/api/weather/view?stationid={id}",
                Map.of("id", stationId), CmaResponse.class);
        CmaWeatherData data = resp.data();
        if (data == null) {
            throw new ToolExecutionException(FailureCause.BUSINESS_ERROR,
                    "CMA 返回 data 为空, stationId=" + stationId);
        }
        return mapToDto(city, data);
    }

    /**
     * 通用 GET + 双层成功校验 + 异常分类
     * <p>
     * 用 {@code exchange} 而非 {@code retrieve}，以便在抛异常前自己掌控状态码 /
     * Content-Type / body 的判定，避免 RestClient 默认对 4xx/5xx 直接抛
     * {@code RestClientResponseException} 导致 403/429/5xx 无法区分。
     * </p>
     */
    private <T> T getJson(String uriTemplate, Map<String, ?> uriVars, Class<T> type) {
        try {
            return weatherRestClient.get()
                    .uri(uriTemplate, uriVars)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 403) {
                            throw new ToolExecutionException(FailureCause.BLOCKED,
                                    "CMA 下游 403（WAF 拦截）");
                        }
                        if (status == 429) {
                            throw new ToolExecutionException(FailureCause.RATE_LIMITED,
                                    "CMA 下游 429（限流）");
                        }
                        if (status >= 500) {
                            throw new ToolExecutionException(FailureCause.SERVER_5XX,
                                    "CMA 下游 5xx: " + status);
                        }
                        if (status >= 400) {
                            throw new ToolExecutionException(FailureCause.CLIENT_4XX,
                                    "CMA 下游 4xx: " + status);
                        }
                        MediaType contentType = response.getHeaders().getContentType();
                        if (contentType == null || !contentType.includes(MediaType.APPLICATION_JSON)) {
                            throw new ToolExecutionException(FailureCause.NOT_JSON,
                                    "CMA 下游返回非 JSON, Content-Type=" + contentType);
                        }
                        return response.bodyTo(type);
                    });
        } catch (ToolExecutionException e) {
            throw e;
        } catch (ResourceAccessException e) {
            // SimpleClientHttpRequestFactory 的连接 / 读超时均抛 SocketTimeoutException
            if (e.getCause() instanceof SocketTimeoutException) {
                throw new ToolExecutionException(FailureCause.TIMEOUT,
                        "CMA 下游超时: " + e.getMessage(), e);
            }
            throw new ToolExecutionException(FailureCause.SERVER_5XX,
                    "CMA 下游 IO 错误: " + e.getMessage(), e);
        } catch (Exception e) {
            // 兜底：反序列化失败等无法分类的故障，按下游 5xx 对待
            throw new ToolExecutionException(FailureCause.SERVER_5XX,
                    "CMA 下游调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 CMA 原始数据映射为应用内部 {@link WeatherDto}
     * <p>
     * {@code condition} 取自 {@code daily[0].dayText}（今日白天天气描述），
     * 缺失时降级为 {@code nightText} 再降级为空串。数值字段缺失按 0 兜底。
     * </p>
     */
    private WeatherDto mapToDto(String city, CmaWeatherData data) {
        CmaLocation loc = data.location();
        CmaNow now = data.now();

        String condition = "";
        List<CmaDaily> daily = data.daily();
        if (daily != null && !daily.isEmpty()) {
            CmaDaily today = daily.get(0);
            if (today != null) {
                condition = today.dayText();
                if (condition == null || condition.isBlank()) {
                    condition = today.nightText();
                }
                if (condition == null) {
                    condition = "";
                }
            }
        }

        double tempC = now != null && now.temperature() != null ? now.temperature() : 0.0;
        int humidity = now != null && now.humidity() != null
                ? (int) Math.round(now.humidity()) : 0;
        String windDirection = now != null && now.windDirection() != null
                ? now.windDirection() : "";
        String windScale = now != null && now.windScale() != null
                ? now.windScale() : "";
        double feelst = now != null && now.feelst() != null ? now.feelst() : 0.0;

        String name = loc != null && loc.name() != null ? loc.name() : city;
        Double longitude = loc != null ? loc.longitude() : null;
        Double latitude = loc != null ? loc.latitude() : null;
        String path = loc != null ? loc.path() : null;

        return new WeatherDto(name, tempC, humidity, condition, windDirection, windScale, feelst,
                INTERNAL_SOURCE, longitude, latitude, path);
    }
}
