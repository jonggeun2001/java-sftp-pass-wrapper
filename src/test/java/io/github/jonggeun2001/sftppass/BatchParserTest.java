package io.github.jonggeun2001.sftppass;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchParserTest {
    @Test
    void parsesQuotedArgumentsAndComments() {
        BatchParser.ParsedLine line = BatchParser.parse("put 'local file.txt' \"remote file.txt\" # comment");

        assertFalse(line.empty());
        assertEquals("put", line.tokens().get(0));
        assertEquals("local file.txt", line.tokens().get(1));
        assertEquals("remote file.txt", line.tokens().get(2));
    }

    @Test
    void parsesOpenSshBatchPrefixes() {
        BatchParser.ParsedLine line = BatchParser.parse("-@rm missing.txt");

        assertTrue(line.ignoreErrors());
        assertTrue(line.silent());
        assertEquals("rm", line.tokens().get(0));
    }

    @Test
    void rejectsUnclosedQuotes() {
        assertThrows(IllegalArgumentException.class, () -> BatchParser.parse("put 'broken"));
    }
}
