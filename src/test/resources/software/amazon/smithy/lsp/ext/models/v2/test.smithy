$version: "2.0"

namespace com.example

use com.foo#emptyTraitStruct
use com.extras#Extra

@emptyTraitStruct
structure OtherStructure {
    foo: String
    bar: String
    baz: Integer
    qux: Extra
}



