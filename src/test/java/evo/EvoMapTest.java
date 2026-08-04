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

    public static void main(String[] args) throws Exception {
        testScalarPassthrough();
        testEnum();
        testRecord();
        System.out.println("EvoMapTest OK (" + checks + " checks)");
    }
}
