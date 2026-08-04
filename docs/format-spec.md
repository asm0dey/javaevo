# evo — Wire Format Specification

Byte-level spec for the `evo` binary format, with worked examples. This
describes what `Evo.write` produces and `Evo.read` consumes. For design
rationale and compatibility rules see
`superpowers/specs/2026-08-04-binary-format-design.md`.

All multi-byte fixed-width fields are **big-endian**. All lengths and counts
are unsigned **LEB128 varints**. Signed integers use **zigzag** then varint.

---

## 1. A value = tag + payload

Every value on the wire is one **tag byte** followed by a payload whose shape
the tag determines:

```
+---------+========================+
|  tag    |        payload         |
| 1 byte  |   0..N bytes           |
+---------+========================+
```

A stream/file is just values written back-to-back. Read in a loop until
`EOFException`:

```
+-------+-------+-------+-----
| val 0 | val 1 | val 2 |  ...   (until end of stream)
+-------+-------+-------+-----
```

There is no file header or magic number.

---

## 2. The tag byte

The tag byte packs two fields:

```
 bit:   7   6   5   4   3   2   1   0
      +---+---+---+---+---+---+---+---+
      |  size class   |    type id    |
      |   (0..7)      |    (0..31)    |
      +---+---+---+---+---+---+---+---+
       \___ 3 bits __/ \___ 5 bits __/
```

- **size class** (`tag >> 5`) tells any reader *how to skip* the value even if
  it does not know the type. This is what keeps old readers working when new
  types are added.
- **type id** (`tag & 0x1F`) identifies the concrete type within the class.

### Size classes and their skip rule

| Class | Name    | How to skip the payload                     |
|:-----:|---------|---------------------------------------------|
| 0     | empty   | nothing (0 payload bytes)                   |
| 1     | fixed1  | skip 1 byte                                 |
| 2     | fixed2  | skip 2 bytes                                |
| 3     | fixed4  | skip 4 bytes                                |
| 4     | fixed8  | skip 8 bytes                                |
| 5     | varint  | read one varint, discard                    |
| 6     | bytes   | read a varint length N, skip N bytes        |
| 7     | values  | read a varint count K, skip K nested values |

A reader that meets an unknown type id still knows its class from the top 3
bits, applies the skip rule, and returns an `Unknown(tag, raw)` placeholder.

---

## 3. Type table (all currently-defined tags)

| Type   | Tag  | bits `ccc ttttt`   | Class    | Payload                          |
|--------|:----:|--------------------|----------|----------------------------------|
| NULL   | 0x00 | `000 00000`        | 0 empty  | none                             |
| FALSE  | 0x01 | `000 00001`        | 0 empty  | none                             |
| TRUE   | 0x02 | `000 00010`        | 0 empty  | none                             |
| CHAR   | 0x40 | `010 00000`        | 2 fixed2 | 2 bytes, big-endian UTF-16 unit  |
| FLOAT  | 0x60 | `011 00000`        | 3 fixed4 | 4 bytes, `floatToIntBits` BE     |
| DOUBLE | 0x80 | `100 00000`        | 4 fixed8 | 8 bytes, `doubleToLongBits` BE   |
| BYTE   | 0xA0 | `101 00000`        | 5 varint | zigzag varint                    |
| SHORT  | 0xA1 | `101 00001`        | 5 varint | zigzag varint                    |
| INT    | 0xA2 | `101 00010`        | 5 varint | zigzag varint                    |
| LONG   | 0xA3 | `101 00011`        | 5 varint | zigzag varint                    |
| STRING | 0xC0 | `110 00000`        | 6 bytes  | varint length + UTF-8 bytes      |
| BYTES  | 0xC1 | `110 00001`        | 6 bytes  | varint length + raw bytes        |
| LIST   | 0xE0 | `111 00000`        | 7 values | varint count + that many values  |
| MAP    | 0xE1 | `111 00001`        | 7 values | varint count + key,value pairs   |

Class 1 (`fixed1`) is reserved for a future 1-byte type.

---

## 4. Varint (LEB128) and zigzag

### Varint — unsigned LEB128

Seven payload bits per byte, low group first. The high bit (`C`) is 1 while
more bytes follow, 0 on the last byte:

```
 byte:  C bbbbbbb   C bbbbbbb   0 bbbbbbb
        \_______/   \_______/   \_______/
         group 0     group 1     group 2 (last)
 value = group0 | group1<<7 | group2<<14 | ...
```

Examples:

```
      1  ->  01
    127  ->  7F
    128  ->  80 01          (128 = 0<<0 | 1<<7)
    300  ->  AC 02          (300 = 0x2C | 0x02<<7)
```

`Evo.readVarint` rejects a varint longer than 10 bytes.

### Zigzag — signed to unsigned

Maps small-magnitude signed numbers to small unsigned numbers so they varint
compactly:

```
  encode: (n << 1) ^ (n >> 63)          decode: (u >>> 1) ^ -(u & 1)

    n:   0   -1    1   -2    2   -3    3
    u:   0    1    2    3    4    5    6
```

So `int 1` and `int -1` both cost a single payload byte.

Lengths and counts (STRING/BYTES/LIST/MAP) are plain unsigned varints — **not**
zigzagged. Only the numeric types BYTE/SHORT/INT/LONG use zigzag.

---

## 5. Worked byte layouts

Bytes shown in hex. `|` separates the tag from the payload.

### Scalars

```
 null                 00
 false                01
 true                 02

 byte  -7             A0 | 0D                 zigzag(-7)=13=0x0D
 int    1             A2 | 02                 zigzag(1)=2
 int   -1             A2 | 01                 zigzag(-1)=1
 int  300             A2 | D8 04              zigzag(300)=600 -> varint D8 04
 long   1             A3 | 02
 short 30000          A1 | E0 D4 03           zigzag(30000)=60000

 char 'Z' (0x005A)    40 | 00 5A              2 bytes, big-endian
 float 3.5            60 | 40 60 00 00        floatToIntBits(3.5)=0x40600000
 double 1.0           80 | 3F F0 00 00 00 00 00 00
```

### String and bytes (class 6: varint length, then bytes)

```
 ""            C0 | 00
 "hi"          C0 | 02 | 68 69                len=2, 'h'=0x68 'i'=0x69
 "héllo €"     C0 | 0A | 68 C3 A9 6C 6C 6F 20 E2 82 AC    len=10 UTF-8 bytes

 byte[]{1,2,3} C1 | 03 | 01 02 03             len=3, raw bytes
```

Note the string length is the **UTF-8 byte** count (10 here), not the character
count (7 — `é` and `€` are multi-byte).

### List (class 7: varint count, then that many values)

`List.of(1, "hi")`:

```
 E0 | 02 |  A2 02  |  C0 02 68 69
 ^     ^   \_____/   \__________/
 |     |    int 1      "hi"
 LIST  count=2
```

### Map (class 7: varint entry count, then key,value pairs)

`{"a": 1}`:

```
 E1 | 01 |  C0 01 61  |  A2 02
 ^     ^   \________/   \____/
 |     |    key "a"      val 1
 MAP   count=1 entry (so 2 values follow)
```

Nesting is just recursion — a list value can be another list, a map value can
be another map, to any depth.

---

## 6. Forward compatibility: skipping an unknown type

Suppose a future version adds a type with tag `0xDF` (class 6 = bytes, an
unused type id `0x1F`). An **old** reader has never heard of `0xDF`, but the
class tells it the payload is `varint length + that many bytes`:

```
 stream:   DF | 02 | 05 06 |  C0 02 68 69
           ^    ^    \___/    \__________/
           |    |    payload    "after"  (next value, still readable)
           |    len=2
           unknown tag (class 6)

 old reader:  returns Unknown(tag=0xDF, raw=[05 06]),
              then reads "after" correctly — stream stayed in sync.
```

The same holds when the unknown value sits inside a List or Map: the skip
consumes exactly its bytes, so key/value pairing never desyncs. Every size
class 0–7 has a fixed skip rule, so **any** future type is skippable.

---

## 7. Objects (via `EvoMap`)

`EvoMap` has no wire format of its own — it maps records/POJOs to a `Map` of
`fieldName -> value` and hands that to `Evo`. So a `record Person(int age,
String name)` with `age=1, name="hi"` is on the wire exactly as the map
`{"age": 1, "name": "hi"}`:

```
 E1 | 02 |  C0 03 61 67 65  A2 02  |  C0 04 6E 61 6D 65  C0 02 68 69
 MAP  2     "age"           1         "name"             "hi"
      entries
```

Because fields are keyed by name, adding a field appends an entry (old readers
ignore it) and dropping a field omits one (readers default it) — schema
evolution with no wire-format change.
```
