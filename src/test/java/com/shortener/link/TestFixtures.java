package com.shortener.link;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shortener.abuse.BlockedDomain;
import com.shortener.abuse.BlockedDomainRepository;
import com.shortener.abuse.UrlScreener;
import com.shortener.config.AbuseProperties;
import com.shortener.config.AppProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

public final class TestFixtures {

    public static final String BASE_URL = "http://sho.rt";

    private TestFixtures() {
    }

    public static AppProperties props() {
        return new AppProperties(BASE_URL, 7, 5, 2048, 100);
    }

    public static AbuseProperties abuseProps() {
        return new AbuseProperties(10, 1000, List.of("bit.ly", "tinyurl.com"), 30);
    }

    /** A screener whose admin blocklist holds exactly these domains. */
    public static UrlScreener screener(String... blockedDomains) {
        BlockedDomainRepository repo = mock(BlockedDomainRepository.class);
        when(repo.findAll()).thenReturn(Arrays.stream(blockedDomains)
                .map(d -> new BlockedDomain(d, "test", Instant.EPOCH, null)).toList());
        UrlScreener screener = new UrlScreener(repo, Clock.systemUTC(), abuseProps());
        screener.refresh();
        return screener;
    }
}
