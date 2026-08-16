package com.foxmimi.springaichat.tool.tool;

import com.foxmimi.springaichat.tool.client.WeatherClient;
import com.foxmimi.springaichat.tool.dto.WeatherDto;
import com.foxmimi.springaichat.tool.dto.WeatherViewDto;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

/**
 * 天气工具服务
 * <p>
 * 对模型暴露的唯一工具方法 {@link #getWeather(String)}。Spring AI 在
 * {@code ChatClient.builder().defaultTools(weatherService)} 时扫描
 * {@link Tool @Tool} 标注的方法，自动注入工具 Schema 供模型决策调用。
 * </p>
 * <p>
 * 内部委托 {@link WeatherClient#fetchWeather(String)} 完成 CMA 两步 HTTP 调用，
 * 拿到含 provider 标记 / location 元数据的 {@link WeatherDto} 后映射为
 * {@link WeatherViewDto}（字段白名单消毒）回喂模型。
 * </p>
 */
@Service
public class WeatherService {

    private final WeatherClient weatherClient;

    public WeatherService(WeatherClient weatherClient) {
        this.weatherClient = weatherClient;
    }

    /**
     * 查询指定城市的当前天气
     * <p>
     * 模型决策调用此工具并填参 city；返回值会作为工具结果回喂模型，
     * 故只包含 {@link WeatherViewDto} 字段白名单（剥离
     * {@code internalSource}/{@code longitude}/{@code latitude}/{@code path}）。
     * </p>
     *
     * @param city 城市名
     * @return 消毒后的天气视图 DTO
     */
    @Tool(description = "查询指定城市的当前天气")
    public WeatherViewDto getWeather(String city) {
        WeatherDto dto = weatherClient.fetchWeather(city);
        return new WeatherViewDto(
                dto.city(),
                dto.tempC(),
                dto.humidity(),
                dto.condition(),
                dto.windDirection(),
                dto.windScale(),
                dto.feelst()
        );
    }
}
