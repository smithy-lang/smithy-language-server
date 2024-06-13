$version: "1.0"

namespace some.test

use alloy.proto#protoIndex

structure MyStruct {
    @required
    @protoIndex(1)
    name: String

    @required
    @protoIndex(1)
    age: Integer
}
