package ar.edu.itba.sds.tp5.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser JSON minimo (sin dependencias). Devuelve Map (LinkedHashMap), List, String,
 * Double, Boolean o null. Suficiente para configuraciones y listas de corridas.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        try {
            p.ws();
            Object v = p.value();
            p.ws();
            if (p.i != p.s.length()) {
                throw p.err("contenido inesperado luego del valor JSON");
            }
            return v;
        } catch (IndexOutOfBoundsException e) {
            throw p.err("fin de entrada inesperado (JSON incompleto)");
        }
    }

    /** Aplana objetos anidados a claves con puntos: {"a":{"b":1}} -> a.b=1. Las listas no se admiten. */
    public static Map<String, String> flatten(Object parsed) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("La configuracion JSON debe ser un objeto");
        }
        flattenInto("", parsed, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void flattenInto(String prefix, Object v, Map<String, String> out) {
        if (v instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                flattenInto(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), out);
            }
        } else if (v instanceof List) {
            throw new IllegalArgumentException("Listas no soportadas en configuracion (clave '" + prefix + "')");
        } else if (v instanceof Double) {
            double d = (Double) v;
            out.put(prefix, (d == Math.rint(d) && Math.abs(d) < 1e15) ? Long.toString((long) d) : Double.toString(d));
        } else if (v == null) {
            throw new IllegalArgumentException("Valor null no permitido (clave '" + prefix + "')");
        } else {
            out.put(prefix, v.toString());
        }
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException("JSON invalido en posicion " + i + ": " + msg);
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private Object value() {
        if (i >= s.length()) {
            throw err("fin de entrada inesperado");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                return number();
        }
    }

    private Object literal(String word, Object val) {
        if (!s.startsWith(word, i)) {
            throw err("literal invalido");
        }
        i += word.length();
        return val;
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (s.charAt(i) != '"') {
                throw err("se esperaba una clave string");
            }
            String k = string();
            ws();
            if (s.charAt(i) != ':') {
                throw err("se esperaba ':'");
            }
            i++;
            ws();
            if (m.containsKey(k)) {
                throw err("clave duplicada '" + k + "'");
            }
            m.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw err("se esperaba ',' o '}'");
            }
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') {
                return l;
            }
            if (c != ',') {
                throw err("se esperaba ',' o ']'");
            }
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) {
                throw err("string sin cerrar");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Double number() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (st == i) {
            throw err("valor inesperado '" + s.charAt(i) + "'");
        }
        try {
            return Double.valueOf(s.substring(st, i));
        } catch (NumberFormatException ex) {
            throw err("numero invalido '" + s.substring(st, i) + "'");
        }
    }
}
