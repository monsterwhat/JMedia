package Models.Xtream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** category_ids must serialize as numbers even though ids are strings internally. */
class XtreamIdsTest {

    @Test
    void convertsNumericIds() {
        assertEquals(List.of(1L, 20000003L), XtreamIds.numeric(List.of("1", "20000003")));
    }

    @Test
    void dropsBlankAndMalformedIds() {
        assertTrue(XtreamIds.numeric(null).isEmpty());
        assertEquals(List.of(5L), XtreamIds.numeric(List.of("", "abc", "5")));
    }

    @Test
    void vodStreamSerializesCategoryIdsAsNumbers() throws Exception {
        XtreamVodStream stream = new XtreamVodStream();
        stream.categoryIds = List.of("42", "20000003");

        String json = new ObjectMapper().writeValueAsString(stream);

        assertTrue(json.contains("\"category_ids\":[42,20000003]"), json);
    }

    @Test
    void seriesSerializesCategoryIdsAsNumbers() throws Exception {
        XtreamSeries series = new XtreamSeries();
        series.categoryIds = List.of("42");

        String json = new ObjectMapper().writeValueAsString(series);

        assertTrue(json.contains("\"category_ids\":[42]"), json);
    }
}
