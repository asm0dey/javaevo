# evo — tiny binary format for Java

Zero-dependency, self-describing binary serialization with schema evolution.
Two files: `Evo.java` (codec), `EvoMap.java` (reflection mapper).

## Codec

    Evo.write(out, anyValue);   // null, primitives, String, byte[], List, Map
    Object v = Evo.read(in);    // exact type back; loop until EOFException

## Objects

    EvoMap.writeObject(out, person);
    Person p = EvoMap.readObject(in, Person.class);

Records and POJOs (POJO needs a no-arg constructor, non-final fields). Nested
records/POJOs, `List<T>`, `Map<K,V>`, and enums are handled. Add/drop fields
freely — old readers skip unknown data, missing fields default. Reflective
metadata is cached per class, so repeated calls are fast.

Buffered file convenience:

    EvoMap.writeToFile(path, person);
    Person p = EvoMap.readFromFile(path, Person.class);

**Buffering:** the codec does byte-at-a-time IO. Wrap file/socket streams in
`BufferedOutputStream`/`BufferedInputStream` (the `*File` helpers above already
do). In-memory `ByteArrayOutputStream` needs nothing. Unbuffered file IO is
~10× slower — see `benchmark/`.

See `docs/format-spec.md` for the wire format with byte-layout diagrams, and
`docs/superpowers/specs/2026-08-04-binary-format-design.md` for the design and
compatibility guarantees.

The shipped code (`Evo`, `EvoMap`) has zero dependencies. JUnit is test-scope only.

## Build & test

    mvn test        # runs the JUnit suites
    mvn package     # builds the zero-dependency jar

No-Maven fallback (plain JDK):

    javac -d out src/main/java/evo/*.java
    # (tests need JUnit; use `mvn test` for those)
