package evo;

import java.io.*;
import java.util.*;

public class EvoMapTest {
    static int checks = 0;
    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
        checks++;
    }

    enum Color { RED, GREEN, BLUE }

    record Address(String city, int zip) {}
    record Person(int age, String name, Color color, Address address) {}

    static class Point {          // POJO: no-arg ctor, non-final fields
        int x;
        int y;
        String label;
        Point() {}
        Point(int x, int y, String label) { this.x = x; this.y = y; this.label = label; }
    }

    record Team(String name, List<Person> members, Map<String, Address> offices) {}

    record V1(int age, String name) {}
    record V2(int age, String name, String email) {}

    static <T> T roundtrip(Object o, Class<T> type) throws IOException {
        var b = new ByteArrayOutputStream();
        EvoMap.writeObject(b, o);
        return EvoMap.readObject(new ByteArrayInputStream(b.toByteArray()), type);
    }

    static void testScalarPassthrough() throws IOException {
        check(roundtrip("hi", String.class).equals("hi"), "scalar string");
        check(roundtrip(42, Integer.class).equals(42), "scalar int");
    }

    static void testEnum() throws IOException {
        check(roundtrip(Color.GREEN, Color.class) == Color.GREEN, "enum");
    }

    static void testRecord() throws IOException {
        var p = new Person(30, "Ada", Color.BLUE, new Address("London", 12345));
        Person r = roundtrip(p, Person.class);
        check(r.equals(p), "record deep equal");
        check(r.address().city().equals("London"), "nested record field");
    }

    static void testPojo() throws IOException {
        var p = new Point(3, 4, "corner");
        Point r = roundtrip(p, Point.class);
        check(r.x == 3 && r.y == 4 && "corner".equals(r.label), "pojo fields");
    }

    static void testNestedCollections() throws IOException {
        var team = new Team(
            "core",
            List.of(new Person(1, "A", Color.RED, new Address("NYC", 1)),
                    new Person(2, "B", Color.GREEN, new Address("LA", 2))),
            Map.of("hq", new Address("SF", 3)));
        Team r = roundtrip(team, Team.class);
        check(r.name().equals("core"), "team name");
        check(r.members().size() == 2, "list<record> size");
        check(r.members().get(0).name().equals("A"), "list<record> element field");
        check(r.offices().get("hq").city().equals("SF"), "map<string,record> value");
    }

    static void testEvolution() throws IOException {
        // OLD data (V1) read by NEW code (V2): missing 'email' -> null
        var b1 = new ByteArrayOutputStream();
        EvoMap.writeObject(b1, new V1(20, "Old"));
        V2 upgraded = EvoMap.readObject(new ByteArrayInputStream(b1.toByteArray()), V2.class);
        check(upgraded.age() == 20 && upgraded.name().equals("Old"), "v1->v2 kept fields");
        check(upgraded.email() == null, "v1->v2 missing field defaults null");

        // NEW data (V2) read by OLD code (V1): extra 'email' ignored
        var b2 = new ByteArrayOutputStream();
        EvoMap.writeObject(b2, new V2(21, "New", "x@y.z"));
        V1 downgraded = EvoMap.readObject(new ByteArrayInputStream(b2.toByteArray()), V1.class);
        check(downgraded.age() == 21 && downgraded.name().equals("New"), "v2->v1 extra field ignored");

        // missing primitive defaults to 0
        var b3 = new ByteArrayOutputStream();
        EvoMap.writeObject(b3, Map.of("name", "NoAge"));     // a map with only 'name'
        V1 partial = EvoMap.readObject(new ByteArrayInputStream(b3.toByteArray()), V1.class);
        check(partial.age() == 0 && partial.name().equals("NoAge"), "missing primitive -> 0");
    }

    static void testNullNested() throws IOException {
        var p = new Person(5, "NoAddr", Color.RED, null);   // null nested record
        Person r = roundtrip(p, Person.class);
        check(r.address() == null, "null nested object round-trips null");
    }

    public static void main(String[] args) throws Exception {
        testScalarPassthrough();
        testEnum();
        testRecord();
        testPojo();
        testNestedCollections();
        testEvolution();
        testNullNested();
        System.out.println("EvoMapTest OK (" + checks + " checks)");
    }
}
