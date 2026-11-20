package com.shortener.api;

import com.shortener.config.AppProperties;
import com.shortener.link.Link;
import com.shortener.link.LinkService;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Management API, versioned under /api/v1 (ADR-0005). */
@RestController
@RequestMapping("/api/v1/links")
public class LinkController {

    private final LinkService service;
    private final Clock clock;
    private final String baseUrl;

    public LinkController(LinkService service, Clock clock, AppProperties props) {
        this.service = service;
        this.clock = clock;
        this.baseUrl = props.normalizedBaseUrl();
    }

    @PostMapping
    public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest request) {
        Link link = service.create(new LinkService.CreateCommand(request.url(), request.alias(), request.expiresAt()));
        return ResponseEntity
                .created(URI.create("/api/v1/links/" + link.code()))
                .body(toResponse(link));
    }

    @GetMapping("/{code}")
    public LinkResponse get(@PathVariable String code) {
        return toResponse(service.get(code));
    }

    @GetMapping
    public LinkPageResponse list(@RequestParam(required = false) String cursor,
                                 @RequestParam(required = false) Integer limit) {
        LinkService.Page page = service.list(cursor, limit);
        Instant now = clock.instant();
        return new LinkPageResponse(
                page.items().stream().map(l -> LinkResponse.from(l, baseUrl, now)).toList(),
                page.nextCursor());
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        service.delete(code);
        return ResponseEntity.noContent().build();
    }

    private LinkResponse toResponse(Link link) {
        return LinkResponse.from(link, baseUrl, clock.instant());
    }
}
