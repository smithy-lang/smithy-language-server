$version: "1.0"

namespace test

@trait
structure test {
    @required
    blob: Blob,

    @required
    bool: Boolean,

    @requird
    byte: Byte,

    @required
    short: Short,

    @required
    integer: Integer,

    @required
    long: Long,

    @required
    float: Float,

    @required
    double: Double,

    @required
    bigDecimal: BigDecimal,

    @required
    bigInteger: BigInteger,

    @required
    string: String,

    @required
    timestamp: Timestamp,

    @required
    list: ListA,

    @required
    set: SetA,

    @required
    map: MapA,

    @required
    struct: StructureA,

    @required
    union: UnionA
}

list ListA {
    member: String
}

set SetA {
    member: String
}

map MapA {
    key: String,
    value: Integer
}

structure StructureA {
    @required
    nested: StructureB
}

structure StructureB {
    @required
    nestedMember: String
}

union UnionA {
    a: Integer,
    b: String,
    c: Timestamp
}
