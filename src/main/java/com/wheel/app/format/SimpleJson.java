package com.wheel.app.format;

import java.util.*;

public final class SimpleJson {
    private SimpleJson() {}

    public static Object parse(String text) {
        return new Parser(text).parse();
    }

    public static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder out, Object value) {
        if (value == null) out.append("null");
        else if (value instanceof String s) writeString(out, s);
        else if (value instanceof Number || value instanceof Boolean) out.append(value);
        else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeString(out, String.valueOf(e.getKey()));
                out.append(':');
                write(out, e.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> it) {
            out.append('[');
            boolean first = true;
            for (Object item : it) {
                if (!first) out.append(',');
                first = false;
                write(out, item);
            }
            out.append(']');
        } else {
            writeString(out, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String s;
        private int p;

        Parser(String s) { this.s = s; }

        Object parse() {
            Object v = value();
            ws();
            if (p != s.length()) throw error("Unexpected trailing text");
            return v;
        }

        private Object value() {
            ws();
            if (p >= s.length()) throw error("Unexpected end");
            char c = s.charAt(p);
            if (c == '"') return string();
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == 't' && s.startsWith("true", p)) { p += 4; return true; }
            if (c == 'f' && s.startsWith("false", p)) { p += 5; return false; }
            if (c == 'n' && s.startsWith("null", p)) { p += 4; return null; }
            if (c == '-' || Character.isDigit(c)) return number();
            throw error("Unexpected character '" + c + "'");
        }

        private Map<String, Object> object() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            p++;
            ws();
            if (eat('}')) return map;
            do {
                ws();
                String key = string();
                ws();
                if (!eat(':')) throw error("Expected ':'");
                map.put(key, value());
                ws();
            } while (eat(','));
            if (!eat('}')) throw error("Expected '}'");
            return map;
        }

        private List<Object> array() {
            ArrayList<Object> list = new ArrayList<>();
            p++;
            ws();
            if (eat(']')) return list;
            do {
                list.add(value());
                ws();
            } while (eat(','));
            if (!eat(']')) throw error("Expected ']'");
            return list;
        }

        private String string() {
            if (!eat('"')) throw error("Expected string");
            StringBuilder out = new StringBuilder();
            while (p < s.length()) {
                char c = s.charAt(p++);
                if (c == '"') return out.toString();
                if (c == '\\') {
                    if (p >= s.length()) throw error("Bad escape");
                    char e = s.charAt(p++);
                    switch (e) {
                        case '"' -> out.append('"');
                        case '\\' -> out.append('\\');
                        case '/' -> out.append('/');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (p + 4 > s.length()) throw error("Bad unicode escape");
                            out.append((char) Integer.parseInt(s.substring(p, p + 4), 16));
                            p += 4;
                        }
                        default -> throw error("Bad escape");
                    }
                } else out.append(c);
            }
            throw error("Unclosed string");
        }

        private Number number() {
            int start = p;
            if (s.charAt(p) == '-') p++;
            while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
            if (p < s.length() && s.charAt(p) == '.') {
                p++;
                while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
            }
            if (p < s.length() && (s.charAt(p) == 'e' || s.charAt(p) == 'E')) {
                p++;
                if (p < s.length() && (s.charAt(p) == '+' || s.charAt(p) == '-')) p++;
                while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
            }
            String n = s.substring(start, p);
            return (n.contains(".") || n.contains("e") || n.contains("E")) ? Double.parseDouble(n) : Long.parseLong(n);
        }

        private void ws() { while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++; }
        private boolean eat(char c) { if (p < s.length() && s.charAt(p) == c) { p++; return true; } return false; }
        private RuntimeException error(String m) { return new IllegalArgumentException(m + " at " + p); }
    }
}
