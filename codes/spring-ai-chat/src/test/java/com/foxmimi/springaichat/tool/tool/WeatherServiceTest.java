package com.foxmimi.springaichat.tool.tool;

import com.foxmimi.springaichat.tool.client.WeatherClient;
import com.foxmimi.springaichat.tool.dto.WeatherDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeatherServiceTest {

    @Test
    void mapsInternalWeatherDataToSanitizedView() {
        WeatherClient weatherClient = mock(WeatherClient.class);
        WeatherDto internalWeather = new WeatherDto(
                "北京", 26.6, 65, "雷阵雨", "东北风", "微风", 29.3,
                "CMA", 116.47, 39.81, "中国, 北京, 北京"
        );
        when(weatherClient.fetchWeather("北京")).thenReturn(internalWeather);
        WeatherService weatherService = new WeatherService(weatherClient);

        var view = weatherService.getWeather("北京");

        assertThat(view.city()).isEqualTo("北京");
        assertThat(view.tempC()).isEqualTo(26.6);
        assertThat(view.humidity()).isEqualTo(65);
        assertThat(view.condition()).isEqualTo("雷阵雨");
        assertThat(view.windDirection()).isEqualTo("东北风");
        assertThat(view.windScale()).isEqualTo("微风");
        assertThat(view.feelst()).isEqualTo(29.3);
        assertThat(view.getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("internalSource", "longitude", "latitude", "path");
    }
}
