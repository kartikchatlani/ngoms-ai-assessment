package com.shortener.link;

import com.shortener.config.AppProperties;

public final class TestFixtures {

    public static final String BASE_URL = "http://sho.rt";

    private TestFixtures() {
    }

    public static AppProperties props() {
        return new AppProperties(BASE_URL, 7, 5, 2048, 100);
    }
}
