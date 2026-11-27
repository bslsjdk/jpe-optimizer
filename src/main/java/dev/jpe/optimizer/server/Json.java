package dev.jpe.optimizer.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer for the density server.
 *
 * <p>The service must run from a plain JDK without a build system or third-party
 * jars, so it carries its own parser. It accepts the subset the API needs:
 * objects, arrays, strings, numbers, booleans, and null.
 */
public final class Json {
    private final String text;
    private int index;

    private Json(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        Json parser = new Json(text);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.index != text.length()) {
            throw new IllegalArgumentException("Trailing content at offset " + parser.index);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) value;
    }

    private Object readValue() {
        skipWhitespace();
        if (index >= text.length()) throw new IllegalArgumentException("Unexpected end of input");
        char c = text.charAt(index);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> result = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') { index++; return result; }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            result.put(key, readValue());
            skipWhitespace();
            char c = peek();
            index++;
            if (c == '}') return result;
            if (c != ',') throw new IllegalArgumentException("Expected ',' or '}' at offset " + (index - 1));
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> result = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') { index++; return result; }
        while (true) {
            result.add(readValue());
            skipWhitespace();
            char c = peek();
            index++;
            if (c == ']') return result;
            if (c != ',') throw new IllegalArgumentException("Expected ',' or ']' at offset " + (index - 1));
        }
    }

    private String readString() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (index < text.length()) {
            char c = text.charAt(index++);
            if (c == '"') return out.toString();
            if (c != '\\') { out.append(c); continue; }
            char escape = text.charAt(index++);
            switch (escape) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                    index += 4;
                }
                default -> throw new IllegalArgumentException("Invalid escape at offset " + (index - 1));
            }
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    private Object readNumber() {
        int start = index;
        while (index < text.length() && "+-0123456789.eE".indexOf(text.charAt(index)) >= 0) index++;
        String raw = text.substring(start, index);
        if (raw.isEmpty()) throw new IllegalArgumentException("Unexpected character at offset " + start);
        if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ignored) {
                return Double.parseDouble(raw);
            }
        }
        return Double.parseDouble(raw);
    }

    private Object readLiteral(String literal, Object value) {
        if (!text.startsWith(literal, index)) throw new IllegalArgumentException("Invalid literal at offset " + index);
        index += literal.length();
        return value;
    }

    private void skipWhitespace() {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
    }

    private char peek() {
        if (index >= text.length()) throw new IllegalArgumentException("Unexpected end of input");
        return text.charAt(index);
    }

    private void expect(char expected) {
        if (peek() != expected) {
            throw new IllegalArgumentException("Expected '" + expected + "' at offset " + index);
        }
        index++;
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String string) {
            writeString(out, string);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            out.append(value);
        } else if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) out.append("null");
            else out.append(trimFloat(number));
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeString(out, String.valueOf(entry.getKey()));
                out.append(':');
                writeValue(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> list) {
            out.append('[');
            boolean first = true;
            for (Object element : list) {
                if (!first) out.append(',');
                first = false;
                writeValue(out, element);
            }
            out.append(']');
        } else if (value instanceof float[] floats) {
            out.append('[');
            for (int i = 0; i < floats.length; i++) {
                if (i > 0) out.append(',');
                out.append(trimFloat(floats[i]));
            }
            out.append(']');
        } else if (value instanceof int[] ints) {
            out.append('[');
            for (int i = 0; i < ints.length; i++) {
                if (i > 0) out.append(',');
                out.append(ints[i]);
            }
            out.append(']');
        } else if (value instanceof byte[] bytes) {
            out.append('[');
            for (int i = 0; i < bytes.length; i++) {
                if (i > 0) out.append(',');
                out.append(bytes[i] & 0xff);
            }
            out.append(']');
        } else {
            writeString(out, value.toString());
        }
    }

    private static String trimFloat(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) return Long.toString((long) value);
        return Double.toString(value);
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
