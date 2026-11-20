package com.shortener.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shortener.analytics.AgentClass;
import com.shortener.analytics.ClickRepository.DailyCount;
import com.shortener.analytics.ClickRepository.ReferrerCount;
import com.shortener.analytics.LinkStats;
import com.shortener.analytics.LinkStatsService;
import com.shortener.config.AppConfig;
import com.shortener.link.InvalidRequestException;
import com.shortener.link.LinkNotFoundException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract of GET /api/v1/links/{code}/stats. */
@WebMvcTest(controllers = LinkStatsController.class)
@Import(AppConfig.class)
class LinkStatsWebTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    LinkStatsService service;

    @Test
    void returnsStatsShape() throws Exception {
        when(service.stats("Ab3xY9z", 2)).thenReturn(new LinkStats("Ab3xY9z", 12,
                Instant.parse("2026-11-19T20:00:00Z"), LocalDate.parse("2026-11-18"), LocalDate.parse("2026-11-19"), 5,
                List.of(new DailyCount(LocalDate.parse("2026-11-18"), 0), new DailyCount(LocalDate.parse("2026-11-19"), 5)),
                List.of(new ReferrerCount("t.co", 3)), 2,
                Map.of(AgentClass.DESKTOP, 3L, AgentClass.MOBILE, 1L, AgentClass.BOT, 1L, AgentClass.UNKNOWN, 0L)));

        mvc.perform(get("/api/v1/links/Ab3xY9z/stats?days=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("Ab3xY9z"))
                .andExpect(jsonPath("$.totalClicks").value(12))
                .andExpect(jsonPath("$.lastClickedAt").value("2026-11-19T20:00:00Z"))
                .andExpect(jsonPath("$.window.from").value("2026-11-18"))
                .andExpect(jsonPath("$.window.to").value("2026-11-19"))
                .andExpect(jsonPath("$.window.clicks").value(5))
                .andExpect(jsonPath("$.daily[1].date").value("2026-11-19"))
                .andExpect(jsonPath("$.daily[1].clicks").value(5))
                .andExpect(jsonPath("$.topReferrers[0].host").value("t.co"))
                .andExpect(jsonPath("$.withoutReferrer").value(2))
                .andExpect(jsonPath("$.agents.DESKTOP").value(3))
                // Internal ids never leak through the stats endpoint either.
                .andExpect(jsonPath("$.linkId").doesNotExist());
    }

    @Test
    void unknownLinkIs404Problem() throws Exception {
        when(service.stats(eq("nope123"), any())).thenThrow(new LinkNotFoundException("nope123"));

        mvc.perform(get("/api/v1/links/nope123/stats"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("LINK_NOT_FOUND"));
    }

    @Test
    void badDaysIs400() throws Exception {
        when(service.stats("Ab3xY9z", 0)).thenThrow(new InvalidRequestException("days", "must be between 1 and 365"));

        mvc.perform(get("/api/v1/links/Ab3xY9z/stats?days=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("days"));
        mvc.perform(get("/api/v1/links/Ab3xY9z/stats?days=lots"))
                .andExpect(status().isBadRequest());
    }
}
