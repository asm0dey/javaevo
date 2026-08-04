package evo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Serialize a deep nested record with EvoMap, then annotate the bytes. */
public class Annotate {

    // ---- a relatively complex domain ----
    record Money(String currency, long cents) {}
    record Address(String street, String city, String country, List<String> tags) {}
    record Person(String name, int age, boolean active, Address address, Money balance) {}
    record Department(String name, Person manager, List<Person> members, Map<String, Integer> headcount) {}
    record Company(String name, long founded, List<Department> departments,
                   Map<String, Object> metadata, byte[] logo) {}

    static Company sample() {
        var alice = new Person("Alice", 30, true,
            new Address("1 St", "Springfield", "US", List.of("hq", "primary")),
            new Money("USD", 123456));
        var bob = new Person("Bob", 25, false,
            new Address("2 Ave", "Shelbyville", "US", List.of("remote")),
            new Money("EUR", -500));
        var eng = new Department("Eng", alice, List.of(alice, bob),
            new LinkedHashMap<>(Map.of("dev", 3)));   // single entry -> deterministic order
        var meta = new LinkedHashMap<String, Object>();
        meta.put("public", true);
        meta.put("employees", 42);
        meta.put("note", null);
        meta.put("regions", List.of("EU", "US"));
        return new Company("Acme", 1999L, List.of(eng), meta,
            new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
    }

    // ---- annotating walker over the byte[] ----
    static byte[] data;
    static int pos;

    static int u8() { return data[pos++] & 0xFF; }

    static long varint() {
        long r = 0; int shift = 0, b;
        do { b = u8(); r |= (long) (b & 0x7F) << shift; shift += 7; } while ((b & 0x80) != 0);
        return r;
    }
    static long unzig(long u) { return (u >>> 1) ^ -(u & 1); }

    static void line(int start, String indent, String desc) {
        StringBuilder hex = new StringBuilder();
        for (int i = start; i < pos && i < start + 12; i++) hex.append(String.format("%02X ", data[i]));
        if (pos - start > 12) hex.append("...");
        System.out.printf("%04X  %-30s %s%s%n", start, hex.toString().trim(), indent, desc);
    }

    static void walk(String indent, String label) {
        int start = pos;
        int tag = u8();
        int cls = tag >> 5, id = tag & 0x1F;
        String pfx = label.isEmpty() ? "" : label + ": ";
        switch (tag) {
            case Evo.NULL:  line(start, indent, pfx + "NULL"); break;
            case Evo.FALSE: line(start, indent, pfx + "BOOL false"); break;
            case Evo.TRUE:  line(start, indent, pfx + "BOOL true"); break;
            case Evo.CHAR:  { int c = (u8() << 8) | u8(); line(start, indent, pfx + "CHAR '" + (char) c + "'"); break; }
            case Evo.BYTE:  { long v = unzig(varint()); line(start, indent, pfx + "BYTE " + v); break; }
            case Evo.SHORT: { long v = unzig(varint()); line(start, indent, pfx + "SHORT " + v); break; }
            case Evo.INT:   { long v = unzig(varint()); line(start, indent, pfx + "INT " + v + "   (tag A2, zigzag varint)"); break; }
            case Evo.LONG:  { long v = unzig(varint()); line(start, indent, pfx + "LONG " + v + "   (tag A3, zigzag varint)"); break; }
            case Evo.FLOAT:  { pos += 4; line(start, indent, pfx + "FLOAT"); break; }
            case Evo.DOUBLE: { pos += 8; line(start, indent, pfx + "DOUBLE"); break; }
            case Evo.STRING: {
                int len = (int) varint();
                String s = new String(data, pos, len, StandardCharsets.UTF_8);
                pos += len;
                line(start, indent, pfx + "STRING len=" + len + " \"" + s + "\"");
                break;
            }
            case Evo.BYTES: {
                int len = (int) varint();
                StringBuilder h = new StringBuilder();
                for (int i = 0; i < len; i++) h.append(String.format("%02X", data[pos + i] & 0xFF));
                pos += len;
                line(start, indent, pfx + "BYTES len=" + len + " 0x" + h);
                break;
            }
            case Evo.LIST: {
                int n = (int) varint();
                line(start, indent, pfx + "LIST count=" + n + "   (tag E0)");
                for (int i = 0; i < n; i++) walk(indent + "    ", "[" + i + "]");
                break;
            }
            case Evo.MAP: {
                int n = (int) varint();
                line(start, indent, pfx + "MAP entries=" + n + "   (tag E1)  <-- a record / map");
                for (int i = 0; i < n; i++) {
                    walk(indent + "    ", "key");
                    walk(indent + "    ", "val");
                }
                break;
            }
            default:
                line(start, indent, pfx + "UNKNOWN tag=0x" + Integer.toHexString(tag) + " (class " + cls + ", id " + id + ")");
                // skip by size class
                switch (cls) {
                    case 1: pos += 1; break; case 2: pos += 2; break; case 3: pos += 4; break; case 4: pos += 8; break;
                    case 5: varint(); break; case 6: { int l = (int) varint(); pos += l; break; }
                    case 7: { int n = (int) varint(); for (int i = 0; i < n; i++) walk(indent + "    ", ""); break; }
                }
        }
    }

    public static void main(String[] a) throws Exception {
        var b = new ByteArrayOutputStream();
        EvoMap.writeObject(b, sample());
        data = b.toByteArray();
        pos = 0;
        System.out.println("total bytes: " + data.length);
        System.out.println("offset hex-bytes(token)          layout");
        System.out.println("----------------------------------------------------------------");
        walk("", "Company");
        System.out.println("\nparsed through offset " + pos + " of " + data.length);
    }
}
