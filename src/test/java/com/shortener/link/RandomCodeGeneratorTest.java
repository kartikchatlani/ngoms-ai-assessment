package com.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortener.config.AppProperties;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RandomCodeGeneratorTest {

    private final RandomCodeGenerator generator = new RandomCodeGenerator(TestFixtures.props());

    @Test
    void generatesCodesOfConfiguredLengthFromBase62Alphabet() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(generator.generate()).hasSize(7).matches("[0-9A-Za-z]{7}");
        }
    }

    @Test
    void respectsConfiguredLength() {
        var longer = new RandomCodeGenerator(new AppProperties("http://x", 10, 5, 2048, 100));
        assertThat(longer.generate()).hasSize(10);
    }

    @Test
    void doesNotRepeatInPractice() {
        // 20k draws from 62^7: P(any duplicate) ~ 6e-5. A failure here means the RNG is broken.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            assertThat(seen.add(generator.generate())).isTrue();
        }
    }

    @Test
    void usesTheWholeAlphabetRoughlyUniformly() {
        Map<Character, Integer> counts = new HashMap<>();
        int draws = 10_000; // 70k characters, ~1129 expected per symbol (std dev ~33)
        for (int i = 0; i < draws; i++) {
            for (char c : generator.generate().toCharArray()) {
                counts.merge(c, 1, Integer::sum);
            }
        }
        assertThat(counts).hasSize(62);
        // Loose bounds (+/- ~30%, i.e. ~10 std devs) catch gross bias without flakiness.
        assertThat(counts.values()).allSatisfy(n -> assertThat(n).isBetween(790, 1470));
    }
}
