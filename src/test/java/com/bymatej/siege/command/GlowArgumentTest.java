package com.bymatej.siege.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GlowArgumentTest {
    @Test void noArgumentToggles() {
        assertEquals(GlowArgument.TOGGLE, GlowArgument.parse(null));
        assertEquals(GlowArgument.TOGGLE, GlowArgument.parse(""));
        assertEquals(GlowArgument.TOGGLE, GlowArgument.parse("  "));
    }

    @Test void onIsRecognised() {
        assertEquals(GlowArgument.ON, GlowArgument.parse("on"));
        assertEquals(GlowArgument.ON, GlowArgument.parse("ON"));
        assertEquals(GlowArgument.ON, GlowArgument.parse("true"));
        assertEquals(GlowArgument.ON, GlowArgument.parse("yes"));
    }

    @Test void offIsRecognised() {
        assertEquals(GlowArgument.OFF, GlowArgument.parse("off"));
        assertEquals(GlowArgument.OFF, GlowArgument.parse("OFF"));
        assertEquals(GlowArgument.OFF, GlowArgument.parse("false"));
        assertEquals(GlowArgument.OFF, GlowArgument.parse("no"));
    }

    @Test void anythingElseIsInvalid() {
        assertEquals(GlowArgument.INVALID, GlowArgument.parse("maybe"));
        assertEquals(GlowArgument.INVALID, GlowArgument.parse("1"));
    }
}
