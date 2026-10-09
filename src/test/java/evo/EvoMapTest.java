package evo;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EvoMapTest {

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

    record Arrays1(int[] xs, String[] names, int[][] grid) {}
    record Blob(byte[] data) {}

    static <T> T roundtrip(Object o, Class<T> type) throws IOException {
        var b = new ByteArrayOutputStream();
        EvoMap.writeObject(b, o);
        return EvoMap.readObject(new ByteArrayInputStream(b.toByteArray()), type);
    }

    @Test
    void testScalarPassthrough() throws IOException {
        assertTrue(roundtrip("hi", String.class).equals("hi"), "scalar string");
        assertTrue(roundtrip(42, Integer.class).equals(42), "scalar int");
    }

    @Test
    void testEnum() throws IOException {
        assertTrue(roundtrip(Color.GREEN, Color.class) == Color.GREEN, "enum");
    }

    @Test
    void testRecord() throws IOException {
        var p = new Person(30, "Ada", Color.BLUE, new Address("London", 12345));
        Person r = roundtrip(p, Person.class);
        assertTrue(r.equals(p), "record deep equal");
        assertTrue(r.address().city().equals("London"), "nested record field");
    }

    @Test
    void testPojo() throws IOException {
        var p = new Point(3, 4, "corner");
        Point r = roundtrip(p, Point.class);
        assertTrue(r.x == 3 && r.y == 4 && "corner".equals(r.label), "pojo fields");
    }

    static class Base { int a; String b; Base() {} }
    static class Derived extends Base { String c; Derived() {} }

    @Test
    void testPojoInheritance() throws IOException {
        var d = new Derived();
        d.a = 5; d.b = "base"; d.c = "derived";
        var r = roundtrip(d, Derived.class);
        assertTrue(r.a == 5, "inherited int field survives");
        assertTrue("base".equals(r.b), "inherited String field survives");
        assertTrue("derived".equals(r.c), "own field survives");
    }

    static class ShadowBase { String v; ShadowBase() {} }
    static class ShadowSub extends ShadowBase { String v; ShadowSub() {} }  // hides ShadowBase.v (same name+type)

    @Test
    void testPojoShadowedFieldSubclassWins() throws Exception {
        var s = new ShadowSub();
        s.v = "sub";                                              // subclass field
        var hidden = ShadowBase.class.getDeclaredField("v");      // hidden superclass field
        hidden.setAccessible(true);
        hidden.set(s, "base");
        var r = roundtrip(s, ShadowSub.class);
        assertTrue("sub".equals(r.v), "subclass field wins on same-name shadowing (ADR-0007)");
    }

    @Test
    void testNestedCollections() throws IOException {
        var team = new Team(
            "core",
            List.of(new Person(1, "A", Color.RED, new Address("NYC", 1)),
                    new Person(2, "B", Color.GREEN, new Address("LA", 2))),
            Map.of("hq", new Address("SF", 3)));
        Team r = roundtrip(team, Team.class);
        assertTrue(r.name().equals("core"), "team name");
        assertTrue(r.members().size() == 2, "list<record> size");
        assertTrue(r.members().get(0).name().equals("A"), "list<record> element field");
        assertTrue(r.offices().get("hq").city().equals("SF"), "map<string,record> value");
    }

    @Test
    void testEvolution() throws IOException {
        // OLD data (V1) read by NEW code (V2): missing 'email' -> null
        var b1 = new ByteArrayOutputStream();
        EvoMap.writeObject(b1, new V1(20, "Old"));
        V2 upgraded = EvoMap.readObject(new ByteArrayInputStream(b1.toByteArray()), V2.class);
        assertTrue(upgraded.age() == 20 && upgraded.name().equals("Old"), "v1->v2 kept fields");
        assertTrue(upgraded.email() == null, "v1->v2 missing field defaults null");

        // NEW data (V2) read by OLD code (V1): extra 'email' ignored
        var b2 = new ByteArrayOutputStream();
        EvoMap.writeObject(b2, new V2(21, "New", "x@y.z"));
        V1 downgraded = EvoMap.readObject(new ByteArrayInputStream(b2.toByteArray()), V1.class);
        assertTrue(downgraded.age() == 21 && downgraded.name().equals("New"), "v2->v1 extra field ignored");

        // missing primitive defaults to 0
        var b3 = new ByteArrayOutputStream();
        EvoMap.writeObject(b3, Map.of("name", "NoAge"));     // a map with only 'name'
        V1 partial = EvoMap.readObject(new ByteArrayInputStream(b3.toByteArray()), V1.class);
        assertTrue(partial.age() == 0 && partial.name().equals("NoAge"), "missing primitive -> 0");
    }

    @Test
    void testNullNested() throws IOException {
        var p = new Person(5, "NoAddr", Color.RED, null);   // null nested record
        Person r = roundtrip(p, Person.class);
        assertTrue(r.address() == null, "null nested object round-trips null");
    }

    @Test
    void testArrayFields() throws IOException {
        var a = new Arrays1(new int[]{1, 2, 3}, new String[]{"a", "b"}, new int[][]{{1, 2}, {3}});
        var r = roundtrip(a, Arrays1.class);
        assertTrue(java.util.Arrays.equals(r.xs(), new int[]{1, 2, 3}), "int[] survives");
        assertTrue(java.util.Arrays.equals(r.names(), new String[]{"a", "b"}), "String[] survives");
        assertTrue(java.util.Arrays.deepEquals(r.grid(), new int[][]{{1, 2}, {3}}), "int[][] survives");
    }

    @Test
    void testByteArrayStaysBytes() throws IOException {
        var b = new ByteArrayOutputStream();
        EvoMap.writeObject(b, new Blob(new byte[]{9, 8, 7}));
        // The wire is a MAP {data: <value>}; the value MUST be byte[] (BYTES),
        // not a List (LIST) — the ADR-0005 carve-out.
        Object wire = Evo.read(new ByteArrayInputStream(b.toByteArray()));
        Object data = ((Map<?, ?>) wire).get("data");
        assertTrue(data instanceof byte[], "byte[] field must stay BYTES, not become a LIST");
        var r = roundtrip(new Blob(new byte[]{9, 8, 7}), Blob.class);
        assertTrue(java.util.Arrays.equals(r.data(), new byte[]{9, 8, 7}), "byte[] round-trips");
    }

    record GenHolder(List<Person>[] groups, Map<String, Person>[] byCity) {}

    @Test
    @SuppressWarnings("unchecked")
    void testArrayOfParameterizedTypes() throws IOException {
        Person ada = new Person(1, "Ada", Color.RED, new Address("London", 1));
        Person bob = new Person(2, "Bob", Color.BLUE, new Address("Paris", 2));
        List<Person>[] groups = new List[]{ List.of(ada), List.of(bob) };
        Map<String, Person>[] byCity = new Map[]{ Map.of("london", ada) };
        var r = roundtrip(new GenHolder(groups, byCity), GenHolder.class);
        Object firstElem = r.groups()[0].get(0);
        assertTrue(firstElem instanceof Person, "List<Person>[] element must decode as Person, not Map");
        assertTrue(((Person) firstElem).name().equals("Ada"), "generic-array element value survives");
        Object mapVal = r.byCity()[0].get("london");
        assertTrue(mapVal instanceof Person, "Map<String,Person>[] value must decode as Person");
        assertTrue(((Person) mapVal).name().equals("Ada"), "map-in-array value survives");
    }

    @Test
    void testFileHelpers(@TempDir Path dir) throws IOException {
        var team = new Team(
            "core",
            List.of(new Person(1, "A", Color.RED, new Address("NYC", 1))),
            Map.of("hq", new Address("SF", 3)));
        Path f = dir.resolve("team.evo");
        EvoMap.writeToFile(f, team);
        Team r = EvoMap.readFromFile(f, Team.class);
        assertTrue(r.name().equals("core"), "file round-trip name");
        assertTrue(r.members().get(0).name().equals("A"), "file round-trip nested");
        assertTrue(r.offices().get("hq").city().equals("SF"), "file round-trip map");
    }

    @Test
    void testReadObjectFromBytes() throws IOException {
        var team = new Team("core",
            List.of(new Person(1, "A", Color.RED, new Address("NYC", 1))),
            Map.of("hq", new Address("SF", 3)));
        var b = new ByteArrayOutputStream();
        EvoMap.writeObject(b, team);
        Team r = EvoMap.readObject(b.toByteArray(), Team.class);
        assertTrue(r.equals(team), "byte[] round-trip");
    }

    record HasUuid(java.util.UUID id) {}

    @Test
    void testUnsupportedTypeThrowsCleanly() throws IOException {
        boolean cleanIae = false;
        try {
            var b = new ByteArrayOutputStream();
            EvoMap.writeObject(b, new HasUuid(java.util.UUID.randomUUID()));
        } catch (IllegalArgumentException e) {
            cleanIae = e.getMessage() != null && e.getMessage().contains("UUID");
        }
        assertTrue(cleanIae,
            "unsupported type must throw IllegalArgumentException naming the class, not InaccessibleObjectException");
    }
}
