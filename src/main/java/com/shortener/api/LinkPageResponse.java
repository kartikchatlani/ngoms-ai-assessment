package com.shortener.api;

import java.util.List;

/** @param nextCursor pass back as ?cursor= to get the next page; null on the last page */
public record LinkPageResponse(List<LinkResponse> items, String nextCursor) {
}
