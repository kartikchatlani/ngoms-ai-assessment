package com.shortener.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an RFC 9457 Problem Details body from a servlet filter, where @ControllerAdvice can't
 * reach. Keeps 401/403 from the security layer in exactly the same format as every other error
 * (ADR-0005).
 */
@Component
public class ProblemWriter {

    private final JsonMapper json;

    public ProblemWriter(JsonMapper json) {
        this.json = json;
    }

    public void write(HttpServletResponse response, int status, String title, String detail, String errorCode)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", title);
        body.put("status", status);
        body.put("detail", detail);
        body.put("errorCode", errorCode);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(json.writeValueAsString(body));
    }
}
