package com.shortener.api;

import com.shortener.analytics.LinkStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Click statistics (Phase 2). A separate controller rather than new fields on
 * {@link LinkController}'s responses: existing endpoints keep their exact contract and cost,
 * and clients that never ask for stats never pay for the aggregation queries (ADR-0007).
 */
@RestController
public class LinkStatsController {

    private final LinkStatsService service;

    public LinkStatsController(LinkStatsService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/links/{code}/stats")
    public LinkStatsResponse stats(@PathVariable String code, @RequestParam(required = false) Integer days) {
        return LinkStatsResponse.from(service.stats(code, days));
    }
}
