package io.kestra.plugin.airbyte.endpoints;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import io.kestra.core.plugins.endpoint.PluginEndpointContext;

/**
 * Pagination envelope shared by the endpoints. {@code page} is one-based, matching the Kestra API.
 */
record EndpointPage<T>(int page, int size, long total, boolean totalCapped, List<T> results) {

    static final int DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 500;
    static final int MAX_COUNTED = 10_000;

    static <T> EndpointPage<T> of(PluginEndpointContext context, List<T> all) {
        return of(context, Stream.ofNullable(all).flatMap(List::stream));
    }

    /** Single pass over {@code rows} (closed after): counts up to {@link #MAX_COUNTED} rows, but always until the requested window is full. */
    static <T> EndpointPage<T> of(PluginEndpointContext context, Stream<T> rows) {
        var size = Math.min(positiveInt(context.param("size"), DEFAULT_SIZE), MAX_SIZE);
        var page = positiveInt(context.param("page"), 1);
        var skip = (long) (page - 1) * size;

        var window = new ArrayList<T>();
        var total = 0L;
        var capped = false;
        try (rows) {
            var iterator = rows.iterator();
            while (iterator.hasNext()) {
                if (total >= MAX_COUNTED && window.size() >= size) {
                    capped = true;
                    break;
                }
                var row = iterator.next();
                if (total >= skip && window.size() < size) {
                    window.add(row);
                }
                total++;
            }
        }

        return new EndpointPage<>(page, size, total, capped, List.copyOf(window));
    }

    private static int positiveInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            var parsed = Integer.parseInt(value.strip());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
