package Models.Xtream;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.util.List;

/** Writes category_ids as a JSON array of numbers, even though ids are stored as strings. */
public class NumericCategoryIdsSerializer extends JsonSerializer<List<String>> {

    @Override
    public void serialize(List<String> value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        gen.writeStartArray();
        for (Long id : XtreamIds.numeric(value)) {
            gen.writeNumber(id);
        }
        gen.writeEndArray();
    }
}
