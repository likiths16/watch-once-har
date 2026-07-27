package com.watchonce.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CsvUtilTest {

    @Test
    void parsesHeaderAndRowsIntoNamedMaps() {
        String csv = "name,amount\nAcme Supplies,100\nInitech,200\n";
        List<Map<String, String>> rows = CsvUtil.parseRows(csv);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("name", "Acme Supplies").containsEntry("amount", "100");
        assertThat(rows.get(1)).containsEntry("name", "Initech").containsEntry("amount", "200");
    }

    @Test
    void handlesQuotedFieldsWithEmbeddedCommasAndQuotes() {
        String csv = "name,note\n\"Acme, Inc.\",\"He said \"\"hi\"\"\"\n";
        List<Map<String, String>> rows = CsvUtil.parseRows(csv);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("name", "Acme, Inc.").containsEntry("note", "He said \"hi\"");
    }

    @Test
    void handlesCrlfLineEndingsAndTrailingBlankLine() {
        String csv = "a,b\r\n1,2\r\n3,4\r\n";
        List<Map<String, String>> rows = CsvUtil.parseRows(csv);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsEntry("a", "3").containsEntry("b", "4");
    }

    @Test
    void missingTrailingColumnsBecomeEmptyStrings() {
        String csv = "a,b,c\n1,2\n";
        List<Map<String, String>> rows = CsvUtil.parseRows(csv);
        assertThat(rows.get(0)).containsEntry("a", "1").containsEntry("b", "2").containsEntry("c", "");
    }

    @Test
    void emptyInputProducesNoRows() {
        assertThat(CsvUtil.parseRows("")).isEmpty();
    }
}
