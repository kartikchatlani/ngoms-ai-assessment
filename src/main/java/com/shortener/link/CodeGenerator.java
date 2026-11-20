package com.shortener.link;

/** Produces candidate short codes. Uniqueness is enforced by the database, not here (ADR-0003). */
public interface CodeGenerator {

    String generate();
}
