package com.qian.categorize.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderAliasTest {

    @Test
    void openaiCompatibleAliasesResolveToOpenai() {
        assertEquals(CommandLine.PROTOCOL_OPENAI, CommandLine.resolveProtocol("openai"));
        assertEquals(CommandLine.PROTOCOL_OPENAI, CommandLine.resolveProtocol(" OpenAI "));
        assertEquals(CommandLine.PROTOCOL_OPENAI, CommandLine.resolveProtocol("火山引擎"));
        assertEquals(CommandLine.PROTOCOL_OPENAI, CommandLine.resolveProtocol("volcengine"));
        assertEquals(CommandLine.PROTOCOL_OPENAI, CommandLine.resolveProtocol("ark"));
        assertEquals(CommandLine.PROTOCOL_OPENAI, CommandLine.resolveProtocol("豆包"));
    }

    @Test
    void httpAndNoneResolved() {
        assertEquals(CommandLine.PROTOCOL_HTTP, CommandLine.resolveProtocol("http"));
        assertEquals(CommandLine.PROTOCOL_NONE, CommandLine.resolveProtocol("none"));
        assertEquals(CommandLine.PROTOCOL_NONE, CommandLine.resolveProtocol(""));
        assertEquals(CommandLine.PROTOCOL_NONE, CommandLine.resolveProtocol(null));
        assertEquals(CommandLine.PROTOCOL_NONE, CommandLine.resolveProtocol("off"));
    }

    @Test
    void unknownProviderYieldsEmpty() {
        assertEquals("", CommandLine.resolveProtocol("某不存在的厂商"));
    }
}
