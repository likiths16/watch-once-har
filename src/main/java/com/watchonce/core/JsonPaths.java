package com.watchonce.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tiny JSONPath-like dialect used only internally, in exactly two places that must agree
 * on syntax: {@link Generalizer} flattens a captured response into {@code "$.foo.bar"} /
 * {@code "$.items[0].id"} style paths at generalize time, and the run engine resolves those
 * same path strings against a live response at replay time. Not a general JSONPath
 * implementation — just enough to round-trip what {@link #flatten} produces.
 */
public final class JsonPaths {

    private JsonPaths() {}

    /** Every scalar leaf in {@code root}, keyed by its path (e.g. {@code "$.token"}, {@code "$.items[1].id"}). */
    public static Map<String, String> flatten(JsonNode root) {
        Map<String, String> out = new LinkedHashMap<>();
        flattenInto(root, "$", out);
        return out;
    }

    private static void flattenInto(JsonNode node, String path, Map<String, String> out) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isObject()) {
            var it = node.fields();
            while (it.hasNext()) {
                var e = it.next();
                flattenInto(e.getValue(), path + "." + e.getKey(), out);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flattenInto(node.get(i), path + "[" + i + "]", out);
            }
        } else {
            out.put(path, node.asText());
        }
    }

    /** Navigates {@code root} to the node at {@code path}; null if any segment is missing. */
    public static JsonNode resolve(JsonNode root, String path) {
        if (root == null) return null;
        String rest = path.startsWith("$") ? path.substring(1) : path;
        JsonNode cur = root;
        int i = 0;
        while (i < rest.length() && cur != null) {
            char c = rest.charAt(i);
            if (c == '.') {
                int start = ++i;
                while (i < rest.length() && rest.charAt(i) != '.' && rest.charAt(i) != '[') i++;
                cur = cur.get(rest.substring(start, i));
            } else if (c == '[') {
                int end = rest.indexOf(']', i);
                if (end < 0) return null;
                int idx = Integer.parseInt(rest.substring(i + 1, end));
                cur = cur.get(idx);
                i = end + 1;
            } else {
                i++;
            }
        }
        return cur;
    }
}
