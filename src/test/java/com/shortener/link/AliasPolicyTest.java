package com.shortener.link;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AliasPolicyTest {

    private final AliasPolicy policy = new AliasPolicy();

    @ParameterizedTest
    @ValueSource(strings = {"abc", "my-promo", "Black_Friday_2026", "a1B2c3", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void acceptsValidAliases(String alias) {
        assertThatCode(() -> policy.validate(alias)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "has space", "slash/es", "dot.s", "ünïcode", "%41"})
    void rejectsMalformedAliases(String alias) {
        assertThatThrownBy(() -> policy.validate(alias)).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("must be 3-32 characters");
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "API", "Actuator", "health", "admin", "favicon"})
    void rejectsReservedWordsCaseInsensitively(String alias) {
        assertThatThrownBy(() -> policy.validate(alias)).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void rejectsTooLong() {
        assertThatThrownBy(() -> policy.validate("x".repeat(33))).isInstanceOf(InvalidRequestException.class);
    }
}
