package Models.Xtream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Xtream category ids are strings on the wire ({@code category_id}), but the
 * membership array ({@code category_ids}) is typed as numbers by several
 * clients (XUI returns {@code [449]}; the Go client declares {@code []int}).
 * These helpers convert JMedia's internally-numeric ids for serialization.
 */
public final class XtreamIds {

    private static final Logger LOG = LoggerFactory.getLogger(XtreamIds.class);

    private XtreamIds() {
    }

    public static List<Long> numeric(List<String> ids) {
        List<Long> out = new ArrayList<>(ids == null ? 0 : ids.size());
        if (ids == null) {
            return out;
        }
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            try {
                out.add(Long.parseLong(id.trim()));
            } catch (NumberFormatException e) {
                LOG.warn("Dropping non-numeric Xtream category id '{}'", id);
            }
        }
        return out;
    }

    public static Long numericOrNull(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            LOG.warn("Dropping non-numeric Xtream category id '{}'", id);
            return null;
        }
    }
}
