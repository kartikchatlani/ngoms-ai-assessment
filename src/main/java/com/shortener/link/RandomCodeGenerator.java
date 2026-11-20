package com.shortener.link;

import com.shortener.config.AppProperties;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Uniformly random base62 codes from a CSPRNG (ADR-0003).
 *
 * <p>Why random and not a counter: sequential codes are enumerable (anyone can walk
 * /aaaaaa1, /aaaaaa2 ... and harvest every link) and need coordination across instances.
 * Random codes need neither; the cost is a tiny, retryable collision probability.
 */
@Component
public class RandomCodeGenerator implements CodeGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final SecureRandom random = new SecureRandom();
    private final int length;

    public RandomCodeGenerator(AppProperties props) {
        this.length = props.codeLength();
    }

    @Override
    public String generate() {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            // nextInt(bound) is unbiased (rejection sampling internally), unlike nextInt() % 62.
            out[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(out);
    }
}
